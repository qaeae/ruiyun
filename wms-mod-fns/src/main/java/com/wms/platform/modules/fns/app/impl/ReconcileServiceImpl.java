package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.fns.api.ro.ReconcileCreateRo;
import com.wms.platform.modules.fns.api.vo.ReconcileItemVo;
import com.wms.platform.modules.fns.api.vo.ReconcileVo;
import com.wms.platform.modules.fns.app.ReconcileService;
import com.wms.platform.modules.fns.domain.HandleResult;
import com.wms.platform.modules.fns.domain.Liability;
import com.wms.platform.modules.fns.domain.ReconcileBillRule;
import com.wms.platform.modules.fns.domain.ReconcileDiffRule;
import com.wms.platform.modules.fns.infrastructure.mapper.FnFeeOrderMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.FnReconcileBillMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.FnReconcileItemMapper;
import com.wms.platform.modules.fns.infrastructure.po.FnReconcileBillPo;
import com.wms.platform.modules.fns.infrastructure.po.FnReconcileItemPo;
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
 * 对账服务实现。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 工厂）、B5（带码异常）、B7（编号步骤注释）、B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ReconcileServiceImpl.class);

    /** 对账单 Mapper。 */
    private final FnReconcileBillMapper billMapper;

    /** 对账明细 Mapper。 */
    private final FnReconcileItemMapper itemMapper;

    /** 费用单 Mapper（取平台侧金额）。 */
    private final FnFeeOrderMapper feeOrderMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createBill(ReconcileCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 幂等：同一（承运商 + 账期）只有一张对账单
        FnReconcileBillPo exists = billMapper.selectByCarrierPeriod(tenant, ro.carrierCode(), ro.period());
        if (exists != null) {
            if (ReconcileBillRule.STATUS_CONFIRMED.equals(exists.getStatus())) {
                // 已确认的账期不允许再导入（否则"确认过的金额"可以被第二次导入悄悄改掉）
                throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM,
                        "该账期对账单已确认，不可重复导入：" + ro.carrierCode() + " / " + ro.period());
            }
            log.info("对账单已存在，返回既有单号 rclNo={}", exists.getRclNo());
            return exists.getRclNo();
        }
        // 2. 建单
        String rclNo = ReconcileBillRule.billNo(ro.carrierCode(), ro.period());
        FnReconcileBillPo bill = FnReconcileBillPo.of(idGenerator.nextId(), tenant, rclNo, ro.carrierCode(),
                ro.period(), TenantContext.userId());
        bill.setUpdatedBy(TenantContext.userId());
        try {
            billMapper.insert(bill);
        } catch (DuplicateKeyException ex) {
            // 并发导入：查回既有单（唯一键是最后防线）
            FnReconcileBillPo concurrent = billMapper.selectByCarrierPeriod(tenant, ro.carrierCode(), ro.period());
            if (concurrent == null) {
                throw ex;
            }
            return concurrent.getRclNo();
        }
        // 3. 逐单比对：平台侧取费用单，承运商侧取导入值
        int manualCount = 0;
        int autoCount = 0;
        for (ReconcileCreateRo.Item item : ro.items()) {
            BigDecimal platformFee = feeOrderMapper.selectLogisticsFee(tenant, item.waybillNo());
            ReconcileDiffRule.Result result = ReconcileDiffRule.check(
                    platformFee == null ? BigDecimal.ZERO : platformFee, item.carrierFee());
            // 3.1 平台侧无费用：不是"差异"，是**漏计费**，必须挂人工
            //     落库用 platform_fee=0 + diff_type=平台无对应费用 表达：01 §3.6 规定该列 NOT NULL，
            //     且 SUM(platform_fee) 会忽略 NULL —— 用 NULL 表示"没这笔费用"会**低估平台金额**，
            //     正好把漏计费这个异常在汇总里抹平（异常必须显眼，不能被聚合掩盖）。
            boolean platformMissing = platformFee == null;
            if (platformMissing) {
                result = new ReconcileDiffRule.Result(BigDecimal.ZERO, item.carrierFee(), result.diffAmount(),
                        result.diffRate(), HandleResult.MANUAL,
                        "平台无该运单的物流运费费用单（疑似漏计费），需人工核对", Liability.PLATFORM);
            }
            String diffType = ReconcileBillRule.diffType(platformFee, item.carrierFee());
            // 3.2 落明细（唯一键 uk_bill_waybill 兜住重复运单）
            //     归一化：diffAmount/diffRate 在"分母异常"等分支可能是 null，而 01 §3.6 规定这两列 NOT NULL。
            //     归 0 是安全的——差异**原因**由 diff_type 与 remark 承载（"分母为 0/负，无法计算差异率"）。
            FnReconcileItemPo po = FnReconcileItemPo.of(idGenerator.nextId(), tenant, bill.getId(), item.waybillNo(),
                    item.merchantId(), platformMissing ? BigDecimal.ZERO : platformFee, item.carrierFee(),
                    result.diffAmount() == null ? BigDecimal.ZERO : result.diffAmount(),
                    result.diffRate() == null ? BigDecimal.ZERO : result.diffRate(), diffType,
                    result.handleResult().getLabel());
            po.setLiability(result.liability() == null ? null : result.liability().getLabel());
            po.setRemark(result.message());
            po.setCreatedBy(TenantContext.userId());
            itemMapper.insert(po);
            // 3.3 计数
            if (result.handleResult() == HandleResult.AUTO) {
                autoCount++;
            }
            if (result.handleResult() == HandleResult.PENDING || result.handleResult() == HandleResult.MANUAL) {
                manualCount++;
            }
        }
        // 4. 汇总回写（条件更新：状态推导自未处理条数）
        FnReconcileItemMapper.SummaryRow summary = itemMapper.sumByBill(tenant, bill.getId());
        String status = ReconcileBillRule.statusOf(manualCount);
        billMapper.updateSummary(tenant, rclNo, status, (int) summary.totalCount(), summary.platformAmount(),
                summary.carrierAmount(), summary.diffAmount(), (int) summary.autoCount(), manualCount,
                TenantContext.userId());
        log.warn("生成对账单 rclNo={} 运单数={} 差异额={} 自动平账={} 待处理={}", rclNo, summary.totalCount(),
                summary.diffAmount(), autoCount, manualCount);
        return rclNo;
    }

    @Override
    @Transactional(readOnly = true)
    public ReconcileVo detail(String rclNo) {
        long tenant = TenantContext.tenantId();
        FnReconcileBillPo bill = requireBill(tenant, rclNo);
        List<FnReconcileItemPo> items = itemMapper.selectByBill(tenant, bill.getId());
        List<ReconcileItemVo> itemVos = new ArrayList<>(items.size());
        for (FnReconcileItemPo item : items) {
            itemVos.add(new ReconcileItemVo(item.getId(), item.getWaybillNo(), item.getPlatformFee(),
                    item.getCarrierFee(), item.getDiffAmount(), item.getDiffRate(), item.getDiffType(),
                    item.getHandleResult(), item.getLiability(), item.getRemark()));
        }
        return toVo(bill, itemVos);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconcileVo> list(String carrierCode, String period) {
        long tenant = TenantContext.tenantId();
        List<FnReconcileBillPo> bills = billMapper.selectListBy(tenant, carrierCode, period);
        List<ReconcileVo> vos = new ArrayList<>(bills.size());
        for (FnReconcileBillPo bill : bills) {
            vos.add(toVo(bill, null));
        }
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long handleItem(String rclNo, long itemId, Liability liability, String remark) {
        long tenant = TenantContext.tenantId();
        // 1. 账单必须存在且未确认（确认过的账不许再改差异判定）
        FnReconcileBillPo bill = requireBill(tenant, rclNo);
        if (ReconcileBillRule.STATUS_CONFIRMED.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM, "对账单已确认，不可再处理差异");
        }
        // 2. 明细必须属于该账单
        FnReconcileItemPo item = itemMapper.selectByIdSafe(tenant, itemId);
        if (item == null || !item.getBillId().equals(bill.getId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "差异条目不存在或不属于该对账单：" + itemId);
        }
        // 3. 领域校验：不能改已判定的、自动平账的无需处理；责任方与说明必填
        ReconcileBillRule.assertHandleable(item.getHandleResult(), liability, remark);
        // 4. 条件更新（仅挂账/人工状态可改，防"改到能过为止"）
        int affected = itemMapper.handleManual(tenant, itemId, HandleResult.LIABILITY.getLabel(),
                liability.getLabel(), remark, TenantContext.userId());
        if (affected == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该差异条目已被处理，请刷新后重试");
        }
        // 5. 重算未处理条数并回写状态
        long open = itemMapper.countOpenManual(tenant, bill.getId());
        billMapper.updateManualCount(tenant, rclNo, ReconcileBillRule.statusOf(open), (int) open,
                TenantContext.userId());
        log.info("差异人工处理完成 rclNo={} itemId={} 责任方={} 剩余未处理={}", rclNo, itemId, liability.getLabel(),
                open);
        return open;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String rclNo, long operatorId) {
        long tenant = TenantContext.tenantId();
        // 1. 账单存在
        FnReconcileBillPo bill = requireBill(tenant, rclNo);
        // 2. 未处理差异必须为 0（口径：确认即认账，之后会付款）
        long open = itemMapper.countOpenManual(tenant, bill.getId());
        ReconcileBillRule.assertConfirmable(bill.getStatus(), open);
        // 3. 条件更新确认（仅「待确认」→「已确认」，避免并发重复确认）
        int affected = billMapper.confirm(tenant, rclNo, ReconcileBillRule.STATUS_CONFIRMED, operatorId,
                LocalDateTime.now());
        if (affected == 0) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM, "对账单状态已变化，请刷新后重试");
        }
        log.warn("对账单确认 rclNo={} 确认人={} 运单数={} 差异额={}（差异已全部处理）", rclNo, operatorId,
                bill.getTotalWaybills(), bill.getDiffAmount());
    }

    /**
     * 取对账单（不存在则报错）。
     *
     * @param tenantId 租户
     * @param rclNo    单号
     * @return 对账单
     */
    private FnReconcileBillPo requireBill(long tenantId, String rclNo) {
        FnReconcileBillPo bill = billMapper.selectByNo(tenantId, rclNo);
        if (bill == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "对账单不存在：" + rclNo);
        }
        return bill;
    }

    /**
     * 对账单 → Vo。
     *
     * @param bill  账单
     * @param items 明细（可空）
     * @return Vo
     */
    private ReconcileVo toVo(FnReconcileBillPo bill, List<ReconcileItemVo> items) {
        return new ReconcileVo(bill.getRclNo(), bill.getCarrierCode(), bill.getPeriod(), bill.getStatus(),
                bill.getTotalWaybills() == null ? 0 : bill.getTotalWaybills(),
                bill.getPlatformAmount() == null ? BigDecimal.ZERO : bill.getPlatformAmount(),
                bill.getCarrierAmount() == null ? BigDecimal.ZERO : bill.getCarrierAmount(),
                bill.getDiffAmount() == null ? BigDecimal.ZERO : bill.getDiffAmount(),
                bill.getAutoSettledCount() == null ? 0 : bill.getAutoSettledCount(),
                bill.getManualCount() == null ? 0 : bill.getManualCount(), items);
    }
}
