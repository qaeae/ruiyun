package com.wms.platform.modules.acct.app.impl;

import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.acct.app.FeeFreezeService;
import com.wms.platform.modules.acct.domain.FeeFreezeRule;
import com.wms.platform.modules.acct.infrastructure.mapper.AcFeeFreezeMapper;
import com.wms.platform.modules.acct.infrastructure.mapper.AcLedgerMapper;
import com.wms.platform.modules.acct.infrastructure.mapper.AcMerchantAccountMapper;
import com.wms.platform.modules.acct.infrastructure.po.AcFeeFreezePo;
import com.wms.platform.modules.acct.infrastructure.po.AcLedgerPo;
import com.wms.platform.modules.acct.infrastructure.po.AcMerchantAccountPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用冻结应用服务实现（{@code 04} §4.3）。
 *
 * <p><b>事务边界在这里</b>：账户 CAS + 冻结单 + 流水三件事必须同事务，否则会出现
 * "账户冻住了但冻结单没落库"（无法释放、对账对不上）或反之。
 *
 * <p><b>幂等三层</b>：调用方按 {@code Idempotency-Key} 判重（入口层）、本服务按
 * {@code uk_freeze_biz} 再查一次（业务键）、数据库唯一键兜底（并发）。
 */
@Service
@RequiredArgsConstructor
public class FeeFreezeServiceImpl implements FeeFreezeService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(FeeFreezeServiceImpl.class);

    /** 商家账户。 */
    private final AcMerchantAccountMapper accountMapper;

    /** 冻结单。 */
    private final AcFeeFreezeMapper freezeMapper;

    /** 资金流水。 */
    private final AcLedgerMapper ledgerMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 冻结费用。
     *
     * @param command 冻结命令
     * @return 冻结结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FreezeResult freeze(FreezeCommand command) {
        // 1. 计算冻结额（口径收敛在领域规则里：预估价 × (1 + 缓冲)，4 位小数）
        BigDecimal frozenAmount = FeeFreezeRule.frozenAmount(command.estimatedAmount(), command.bufferRate());
        // 2. 业务键幂等：该业务对象此前已冻结过 → 直接返回原冻结单（不重复冻）
        long tenantId = com.wms.platform.common.context.TenantContext.tenantId();
        AcFeeFreezePo exist = freezeMapper.selectByBiz(tenantId, command.bizType().name(), command.bizNo());
        if (exist != null) {
            log.info("费用冻结幂等命中 bizType={} bizNo={} freezeNo={}",
                    command.bizType(), command.bizNo(), exist.getFreezeNo());
            return FreezeResult.alreadyFrozen(exist.getFreezeNo(), exist.getFrozenAmount(),
                    availableAmount(tenantId, command.merchantId()));
        }
        // 3. 账户 CAS 冻结：可用（balance − freeze_amount）足够才冻得动；影响行数 = 0 即余额不足/无账户
        int affected = accountMapper.freeze(tenantId, command.merchantId(), frozenAmount);
        if (affected == 0) {
            BigDecimal available = availableAmount(tenantId, command.merchantId());
            log.warn("费用冻结被拒（可用不足）merchantId={} bizNo={} 需冻结={} 可用={}",
                    command.merchantId(), command.bizNo(), frozenAmount, available);
            return FreezeResult.insufficient(frozenAmount, available);
        }
        // 4. 冻结成功：落冻结单（时间点与操作人留痕）
        LocalDateTime now = LocalDateTime.now();
        long freezeId = idGenerator.nextId();
        String freezeNo = FeeFreezeRule.freezeNo(freezeId);
        freezeMapper.insert(AcFeeFreezePo.frozen(freezeId, tenantId, freezeNo, command.merchantId(),
                command.warehouseId(), command.bizType().name(), command.bizNo(), command.doId(),
                command.shipmentId(), command.estimatedAmount(),
                command.bufferRate() == null ? BigDecimal.ZERO : command.bufferRate(),
                frozenAmount, now, command.operatorId()));
        // 5. 写资金流水（冻结不动 balance，故变动额为 0，冻后冻结额体现在 freezeAfter）
        AcMerchantAccountPo account = accountMapper.selectByMerchant(tenantId, command.merchantId());
        long ledgerId = idGenerator.nextId();
        ledgerMapper.insert(AcLedgerPo.freezeChanged(ledgerId, tenantId, FeeFreezeRule.ledgerNo(ledgerId),
                command.merchantId(), AcLedgerPo.BIZ_FREEZE,
                account == null ? BigDecimal.ZERO : account.getBalance(),
                account == null ? frozenAmount : account.getFreezeAmount(),
                AcLedgerPo.REF_FREEZE, freezeId, freezeNo,
                command.bizType().getLabel() + "冻结（业务单号 " + command.bizNo() + "）", now, command.operatorId()));
        log.info("费用冻结成功 bizType={} bizNo={} freezeNo={} 冻结额={}",
                command.bizType(), command.bizNo(), freezeNo, frozenAmount);
        return FreezeResult.frozen(freezeNo, frozenAmount,
                account == null ? BigDecimal.ZERO : account.availableAmount());
    }

    /**
     * 释放冻结（幂等）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param bizType    费用类型
     * @param bizNo      业务单号
     * @param reason     释放原因
     * @param operatorId 操作人
     * @return 本次是否真的释放
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean release(long tenantId, long merchantId, com.wms.platform.modules.acct.domain.FeeBizType bizType,
                           String bizNo, String reason, Long operatorId) {
        // 1. 找冻结单：不存在说明本就没冻过 → 按"已处理"返回（调用方幂等重试不应报错）
        AcFeeFreezePo freeze = freezeMapper.selectByBiz(tenantId, bizType.name(), bizNo);
        if (freeze == null) {
            log.info("释放冻结：未找到冻结单（按已处理对待）merchantId={} bizType={} bizNo={}",
                    merchantId, bizType, bizNo);
            return false;
        }
        // 2. 已释放 → 幂等返回
        if (!freeze.isFrozen()) {
            log.info("释放冻结：冻结单已非「已冻结」状态（幂等）freezeNo={} status={}",
                    freeze.getFreezeNo(), freeze.getStatus());
            return false;
        }
        BigDecimal amount = freeze.getFrozenAmount() == null ? BigDecimal.ZERO : freeze.getFrozenAmount();
        // 3. 账户侧先释放（freeze_amount −= frozenAmount，balance 不变）；冻结额不足属数据异常，需人工核对
        int affected = accountMapper.release(tenantId, merchantId, amount);
        if (affected == 0) {
            throw new com.wms.platform.common.api.BizException(
                    com.wms.platform.common.api.ErrorCode.SYSTEM_ERROR,
                    "释放冻结失败：账户冻结额小于冻结单金额，需人工核对（freezeNo=" + freeze.getFreezeNo() + "）");
        }
        // 4. 冻结单置已释放（条件更新：仅"已冻结"可改，防止并发重复释放）
        LocalDateTime now = LocalDateTime.now();
        freezeMapper.markReleased(tenantId, freeze.getFreezeNo(), amount, now, operatorId);
        // 5. 写流水（解冻同样不动 balance）
        AcMerchantAccountPo account = accountMapper.selectByMerchant(tenantId, merchantId);
        long ledgerId = idGenerator.nextId();
        ledgerMapper.insert(AcLedgerPo.freezeChanged(ledgerId, tenantId, FeeFreezeRule.ledgerNo(ledgerId),
                merchantId, AcLedgerPo.BIZ_UNFREEZE,
                account == null ? BigDecimal.ZERO : account.getBalance(),
                account == null ? BigDecimal.ZERO : account.getFreezeAmount(),
                AcLedgerPo.REF_FREEZE, freeze.getId(), freeze.getFreezeNo(),
                (reason == null || reason.isBlank() ? "释放冻结" : reason), now, operatorId));
        log.info("释放冻结成功 freezeNo={} 释放额={} 原因={}", freeze.getFreezeNo(), amount, reason);
        return true;
    }

    /**
     * 查可用余额。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 可用余额（无账户返回 0）
     */
    @Override
    public BigDecimal availableAmount(long tenantId, long merchantId) {
        AcMerchantAccountPo account = accountMapper.selectByMerchant(tenantId, merchantId);
        return account == null ? BigDecimal.ZERO : account.availableAmount();
    }
}
