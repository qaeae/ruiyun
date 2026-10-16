package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.fns.api.vo.FallbackCandidateVo;
import com.wms.platform.modules.fns.app.FallbackDeductionService;
import com.wms.platform.modules.fns.domain.FallbackDeductionRule;
import com.wms.platform.modules.fns.infrastructure.mapper.FeeOrderWriteMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.LogisticsShipmentSeamMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 兜底扣减服务实现（03 API-FNS-05，`01` §3.9）。
 *
 * <p><b>口径（2026-10-14 张三确认）</b>：揽收后**达到 7 天**仍未回传实际运费 → 按预估运费兜底扣减；
 * 后续回传时按"多退少补"结算（{@link FallbackDeductionRule#settle}）。
 *
 * <p><b>为什么兜底扣减必须幂等（而不是"扫到就扣"）</b>：定时扫描会重复运行（重试、双实例、人工补跑），
 * 「先查再插」在并发下会双写。因此这里以 {@code fn_fee_order.uk_fee_biz(tenant, fee_type, biz_type, biz_id)}
 * 作为**唯一性防线**：撞唯一键即代表"已经扣过了"，按幂等成功处理。
 * 这也是"结构防线优先于 if 判断"的又一次应用。
 */
@Service
@RequiredArgsConstructor
public class FallbackDeductionServiceImpl implements FallbackDeductionService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(FallbackDeductionServiceImpl.class);

    /** 费用类型：物流运费。 */
    private static final String FEE_TYPE = "LOGISTICS";

    /** 业务类型：兜底扣减。 */
    private static final String BIZ_TYPE_FALLBACK = "WAYBILL_FALLBACK";

    /** 业务类型：回传结算（多退少补）。 */
    private static final String BIZ_TYPE_SETTLE = "WAYBILL_SETTLE";

    /** 单次扫描上限（防止一次捞太多撑爆事务；剩余的下一次扫描继续处理）。 */
    private static final int SCAN_LIMIT = 500;

    /** 运单只读缝隙。 */
    private final LogisticsShipmentSeamMapper shipmentMapper;

    /** 费用单写入。 */
    private final FeeOrderWriteMapper feeOrderMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<FallbackCandidateVo> sweep(LocalDateTime now) {
        long tenant = TenantContext.tenantId();
        // 1. 取到期候选：揽收 <= now - 7 天、未回传、有预估价
        LocalDateTime deadline = now.minusDays(FallbackDeductionRule.FALLBACK_DAYS);
        List<LogisticsShipmentSeamMapper.ShipmentRow> rows =
                shipmentMapper.selectFallbackCandidates(tenant, deadline, SCAN_LIMIT);
        List<FallbackCandidateVo> result = new ArrayList<>(rows.size());
        for (LogisticsShipmentSeamMapper.ShipmentRow row : rows) {
            // 2. 领域判定（含"已回传永不兜底"的兜底保护）
            FallbackDeductionRule.Result verdict =
                    FallbackDeductionRule.evaluate(row.pickedUpAt(), now, false);
            if (!verdict.shouldFallback()) {
                continue;
            }
            // 3. 幂等落账：撞 uk_fee_biz 即代表已扣过
            boolean already = isAlreadyDeducted(tenant, row.id());
            if (!already) {
                already = insertFallbackFee(tenant, row, verdict);
            }
            result.add(new FallbackCandidateVo(row.waybillNo(), row.merchantId(), row.estimatedFee(),
                    row.pickedUpAt(), verdict.deadline(), already, verdict.message()));
        }
        log.warn("兜底扣减扫描完成：候选 {} 条（其中已扣过 {} 条）", result.size(),
                result.stream().filter(FallbackCandidateVo::alreadyDeducted).count());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FallbackDeductionRule.Adjustment settleLateReturn(String waybillNo, BigDecimal actualFee) {
        long tenant = TenantContext.tenantId();
        // 1. 运单必须存在
        LogisticsShipmentSeamMapper.ShipmentRow row = shipmentMapper.selectByWaybill(tenant, waybillNo);
        if (row == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "运单不存在：" + waybillNo);
        }
        // 2. 必须先兜底扣过才能结算（没扣过就"退"，等于凭空付钱给商家）
        BigDecimal fallbackDeducted = feeOrderMapper.selectAmount(tenant, FEE_TYPE, BIZ_TYPE_FALLBACK, row.id());
        if (fallbackDeducted == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该运单尚未兜底扣减，不能执行回传结算：" + waybillNo + "（先由兜底扫描扣减，再按实际费用多退少补）");
        }
        // 3. 多退少补（领域规则）
        FallbackDeductionRule.Adjustment adjustment = FallbackDeductionRule.settle(fallbackDeducted, actualFee);
        // 4. 落调整费用单（bizType=WAYBILL_SETTLE，uk_fee_biz 保证同一运单只结算一次）
        if (adjustment.action() != FallbackDeductionRule.Action.NONE) {
            insertAdjustFee(tenant, row, actualFee);
        }
        log.warn("回传结算 waybill={} 兜底={} 实际={} 结论={} 调整额={}", waybillNo, fallbackDeducted, actualFee,
                adjustment.action(), adjustment.adjustAmount());
        return adjustment;
    }

    /**
     * 是否已兜底扣减过。
     *
     * @param tenantId 租户
     * @param bizId    运单（发货单）ID
     * @return 已扣返回 {@code true}
     */
    private boolean isAlreadyDeducted(long tenantId, long bizId) {
        return feeOrderMapper.selectStatus(tenantId, FEE_TYPE, BIZ_TYPE_FALLBACK, bizId) != null;
    }

    /**
     * 写兜底扣减费用单。
     *
     * @param tenantId 租户
     * @param row      运单行
     * @param verdict  判定结果
     * @return 是否"已存在"（撞唯一键返回 {@code true}）
     */
    private boolean insertFallbackFee(long tenantId, LogisticsShipmentSeamMapper.ShipmentRow row,
                                      FallbackDeductionRule.Result verdict) {
        try {
            feeOrderMapper.insertFeeOrder(idGenerator.nextId(), tenantId, "FEE-FB-" + row.waybillNo(),
                    row.merchantId(), row.warehouseId(), FEE_TYPE, BIZ_TYPE_FALLBACK, row.id(), row.waybillNo(),
                    row.estimatedFee(), periodOf(row.pickedUpAt()), "兜底扣减", verdict.message(),
                    TenantContext.userId(), LocalDateTime.now());
            return false;
        } catch (DuplicateKeyException ex) {
            // 撞唯一键 = 已扣过（并发/重试）：幂等成功，不重复扣
            log.info("兜底扣减命中唯一键，视为已扣过 waybill={}", row.waybillNo());
            return true;
        }
    }

    /**
     * 写回传结算调整费用单。
     *
     * @param tenantId  租户
     * @param row       运单行
     * @param actualFee 实际运费
     */
    private void insertAdjustFee(long tenantId, LogisticsShipmentSeamMapper.ShipmentRow row, BigDecimal actualFee) {
        try {
            feeOrderMapper.insertFeeOrder(idGenerator.nextId(), tenantId, "FEE-ST-" + row.waybillNo(),
                    row.merchantId(), row.warehouseId(), FEE_TYPE, BIZ_TYPE_SETTLE, row.id(), row.waybillNo(),
                    actualFee, periodOf(row.pickedUpAt()), "回传结算", "回传实际运费后多退少补",
                    TenantContext.userId(), LocalDateTime.now());
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该运单已完成回传结算，不可重复结算：" + row.waybillNo()
                            + "（重复结算会造成多退或少补，必须走人工差异流程）");
        }
    }

    /**
     * 账期（yyyyMM）。
     *
     * @param time 时间
     * @return 账期
     */
    private String periodOf(LocalDateTime time) {
        LocalDateTime base = time == null ? LocalDateTime.now() : time;
        return String.format("%04d%02d", base.getYear(), base.getMonthValue());
    }
}
