package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.fns.api.ro.AdvanceRegisterRo;
import com.wms.platform.modules.fns.api.vo.AdvanceLimitVo;
import com.wms.platform.modules.fns.api.vo.AdvanceVo;
import com.wms.platform.modules.fns.app.AdvancePaymentService;
import com.wms.platform.modules.fns.domain.AdvancePaymentRule;
import com.wms.platform.modules.fns.infrastructure.mapper.FnAdvancePaymentMapper;
import com.wms.platform.modules.fns.infrastructure.po.FnAdvancePaymentPo;
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
 * 垫付台账服务实现（03 API-FNS-06，`01` §3.9）。
 *
 * <p><b>垫付是什么</b>：商家账户余额不足以支付本次实际运费时，平台先替它付给承运商，
 * 形成对商家的应收（{@code fn_advance_payment}）。限额口径（2026-10-14 张三确认，照 PoC-5 默认）：
 * **累计垫付严格大于 ¥2000 或连续 3 单**即拦截转人工——垫付本质是平台在放账，
 * 不限额就等于允许一个商家把平台当无息贷款用。
 *
 * <p><b>"连续 3 单"的口径（必须写清楚，否则无法复现）</b>：
 * 一期近似为"**近 7 天内的垫付笔数 ≥ 3**"（不再纠结"是否被正常扣款打断"）。
 * 理由：真实"连续"需要与扣款成功/失败流水联动，而资金流水域（`ac_*`）尚未落地（K2 阻塞）。
 * **风险已知并接受**：正常经营但恰好一周内垫付 3 次的商家会被误拦——
 * 误拦的代价是转人工确认（可接受），而漏拦的代价是持续放账（不可接受）。
 * 待资金域落地后，把口径换成"近 N 笔垫付中无一次正常扣款成功"。
 */
@Service
@RequiredArgsConstructor
public class AdvancePaymentServiceImpl implements AdvancePaymentService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AdvancePaymentServiceImpl.class);

    /** 近期垫付统计窗口（天）：一期"连续"的近似口径，见类注释。 */
    private static final int RECENT_WINDOW_DAYS = 7;

    /** 垫付 Mapper。 */
    private final FnAdvancePaymentMapper advanceMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String register(AdvanceRegisterRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 幂等：同一运单只垫付一次（uk_advance）
        FnAdvancePaymentPo exists = advanceMapper.selectByWaybill(tenant, ro.waybillNo());
        if (exists != null) {
            log.info("运单已垫付过，返回既有记录 waybill={} amount={}", ro.waybillNo(), exists.getAmount());
            return ro.waybillNo();
        }
        // 2. 限额判定（**预判口径**：按本笔登记之后的状态判，理由见类注释）
        BigDecimal projected = advanceMapper.sumAdvance(tenant, ro.merchantId()).add(ro.amount());
        int projectedCount = (int) countRecent(tenant, ro.merchantId()) + 1;
        AdvancePaymentRule.Result limit = AdvancePaymentRule.evaluateLimit(projected, projectedCount);
        if (limit.limited()) {
            log.warn("垫付被限额拦截 merchant={} waybill={} 原因={}", ro.merchantId(), ro.waybillNo(), limit.message());
            throw new BizException(ErrorCode.ADVANCE_LIMIT_EXCEEDED, limit.message());
        }
        // 3. 落台账（撞唯一键 = 并发重复登记，按幂等成功返回）
        FnAdvancePaymentPo po = FnAdvancePaymentPo.of(idGenerator.nextId(), tenant, ro.merchantId(),
                ro.waybillNo(), ro.freezeId(), ro.amount(), ro.billingPeriod(), TenantContext.userId());
        po.setUpdatedBy(TenantContext.userId());
        try {
            advanceMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            log.info("垫付登记命中唯一键（并发重复），按幂等成功处理 waybill={}", ro.waybillNo());
            return ro.waybillNo();
        }
        log.warn("登记垫付 waybill={} merchant={} amount={} 累计={}", ro.waybillNo(), ro.merchantId(), ro.amount(),
                advanceMapper.sumAdvance(tenant, ro.merchantId()));
        return ro.waybillNo();
    }

    @Override
    @Transactional(readOnly = true)
    public AdvanceLimitVo checkLimit(long merchantId) {
        long tenant = TenantContext.tenantId();
        AdvancePaymentRule.Result result = evaluateLimit(tenant, merchantId);
        return new AdvanceLimitVo(merchantId, advanceMapper.sumAdvance(tenant, merchantId),
                countRecent(tenant, merchantId), result.limited(), AdvancePaymentRule.LIMIT_AMOUNT,
                AdvancePaymentRule.LIMIT_CONSECUTIVE_COUNT, result.message());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clear(String waybillNo) {
        long tenant = TenantContext.tenantId();
        // 1. 条件更新：仅「待核销」可核销（重复核销会让台账与资金流水对不上）
        int affected = advanceMapper.clear(tenant, waybillNo, TenantContext.userId());
        if (affected == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该垫付记录不存在或已核销：" + waybillNo + "（重复核销会让台账与资金流水对不上）");
        }
        log.info("垫付核销 waybill={}", waybillNo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvanceVo> list(Long merchantId) {
        long tenant = TenantContext.tenantId();
        List<FnAdvancePaymentPo> rows = advanceMapper.selectListByMerchant(tenant, merchantId);
        List<AdvanceVo> vos = new ArrayList<>(rows.size());
        for (FnAdvancePaymentPo row : rows) {
            vos.add(new AdvanceVo(row.getWaybillNo(), row.getMerchantId(), row.getAmount(), row.getStatus(),
                    row.getBillingPeriod(), row.getOccurredAt(), row.getClearedAt(), row.getRemark()));
        }
        return vos;
    }

    /**
     * 评估限额（**预检口径**：按已发生状态判，供 { checkLimit} 使用）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 判定结果
     */
    private AdvancePaymentRule.Result evaluateLimit(long tenantId, long merchantId) {
        BigDecimal cumulative = advanceMapper.sumAdvance(tenantId, merchantId);
        long recent = countRecent(tenantId, merchantId);
        return AdvancePaymentRule.evaluateLimit(cumulative, (int) recent);
    }

    /**
     * 近期垫付笔数（一期"连续"的近似口径，见类注释）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 笔数
     */
    private long countRecent(long tenantId, long merchantId) {
        return advanceMapper.countSince(tenantId, merchantId, LocalDateTime.now().minusDays(RECENT_WINDOW_DAYS));
    }
}
