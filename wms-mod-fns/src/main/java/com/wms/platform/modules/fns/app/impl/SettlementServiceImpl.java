package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.payment.dto.PaymentInstruction;
import com.wms.platform.common.payment.dto.PaymentInstructionResult;
import com.wms.platform.common.payment.port.PaymentInstructionPort;
import com.wms.platform.modules.fns.api.vo.SettleBatchVo;
import com.wms.platform.modules.fns.app.SettlementService;
import com.wms.platform.modules.fns.domain.ReconcileBillRule;
import com.wms.platform.modules.fns.infrastructure.mapper.FnReconcileBillMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.FnReconcileItemMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.FnSettleBatchMapper;
import com.wms.platform.modules.fns.infrastructure.po.FnReconcileBillPo;
import com.wms.platform.modules.fns.infrastructure.po.FnSettleBatchPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算批次服务实现。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper，列表查询用本类内的 Mapper 方法）、B4（Po 工厂）、
 * B5（带码异常）、B7（编号步骤注释）、B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(SettlementServiceImpl.class);

    /** 对账单 Mapper。 */
    private final FnReconcileBillMapper billMapper;

    /** 对账明细 Mapper（取运单数）。 */
    private final FnReconcileItemMapper itemMapper;

    /** 结算批次 Mapper。 */
    private final FnSettleBatchMapper batchMapper;

    /** 支付通道。 */
    private final PaymentInstructionPort paymentChannel;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generate(String rclNo) {
        long tenant = TenantContext.tenantId();
        // 1. 对账单必须存在**且已确认**（未确认就生成批次 = 拿没认过的账去付款）
        FnReconcileBillPo bill = billMapper.selectByNo(tenant, rclNo);
        if (bill == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "对账单不存在：" + rclNo);
        }
        if (!ReconcileBillRule.STATUS_CONFIRMED.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.SETTLE_BILL_NOT_CONFIRMED,
                    "对账单当前状态为「" + bill.getStatus() + "」，只有已确认的对账单才能生成结算批次");
        }
        // 2. 幂等：一张对账单只生成一个批次（uk_bill）
        FnSettleBatchPo exists = batchMapper.selectByBill(tenant, bill.getId());
        if (exists != null) {
            log.info("结算批次已存在，返回既有批次 stbNo={}", exists.getStbNo());
            return exists.getStbNo();
        }
        // 3. 金额口径：**以承运商侧金额结算**（对账确认意味着我们认了对方的账）
        long waybillCount = itemMapper.sumByBill(tenant, bill.getId()).totalCount();
        BigDecimal totalAmount = bill.getCarrierAmount() == null ? BigDecimal.ZERO : bill.getCarrierAmount();
        if (totalAmount.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结算金额必须为正，实际：" + totalAmount);
        }
        // 4. 建批次（状态由 Po 工厂内置「待下发」）
        String stbNo = "STB" + bill.getPeriod() + "-" + bill.getCarrierCode();
        FnSettleBatchPo batch = FnSettleBatchPo.of(idGenerator.nextId(), tenant, stbNo, bill.getId(),
                bill.getCarrierCode(), bill.getPeriod(), (int) waybillCount, totalAmount, TenantContext.userId());
        batch.setUpdatedBy(TenantContext.userId());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException ex) {
            // 并发生成：唯一键兜住，查回既有批次（幂等成功）
            FnSettleBatchPo concurrent = batchMapper.selectByBill(tenant, bill.getId());
            if (concurrent == null) {
                throw ex;
            }
            return concurrent.getStbNo();
        }
        log.warn("生成结算批次 stbNo={} 承运商={} 运单数={} 金额={}", stbNo, bill.getCarrierCode(), waybillCount,
                totalAmount);
        return stbNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String dispatch(String stbNo, long operatorId) {
        long tenant = TenantContext.tenantId();
        // 1. 批次必须存在
        FnSettleBatchPo batch = batchMapper.selectByNo(tenant, stbNo);
        if (batch == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结算批次不存在：" + stbNo);
        }
        // 2. 已下发/已结算：**必须报错**，不能幂等返回成功
        //    返回成功会让调用方以为"这笔钱刚发出去"，而实际早已发过——对付款类操作，这是最坏的一种"幂等"
        if (FnSettleBatchPo.STATUS_DISPATCHED.equals(batch.getStatus())
                || FnSettleBatchPo.STATUS_SETTLED.equals(batch.getStatus())) {
            throw new BizException(ErrorCode.SETTLE_BATCH_ALREADY_DISPATCHED,
                    "该批次已下发（指令号 " + batch.getInstructionNo() + "），不可重复下发——重复下发等于重复付款");
        }
        if (!FnSettleBatchPo.STATUS_PENDING.equals(batch.getStatus())) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "批次当前状态为「" + batch.getStatus() + "」，仅「待下发」可下发");
        }
        // 3. 调支付通道（幂等号 = SETTLE:{批次号}，机构侧据此去重）
        PaymentInstruction instruction = PaymentInstruction.logisticsSettle(stbNo, batch.getCarrierCode(),
                batch.getTotalAmount());
        PaymentInstructionResult result = paymentChannel.pay(instruction);
        if (!result.accepted()) {
            // 3.1 被拒：落失败原因，保留重试可能
            batchMapper.markDispatchFailed(tenant, stbNo, result.message(), operatorId);
            log.error("结算下发被拒 stbNo={} 原因={}", stbNo, result.message());
            throw new BizException(ErrorCode.SETTLE_DISPATCH_FAILED, "支付指令未受理：" + result.message());
        }
        // 4. 受理：CAS 落「已下发」+ 机构指令号
        int affected = batchMapper.markDispatched(tenant, stbNo, result.instructionNo(), operatorId);
        if (affected == 0) {
            // 通道已受理但本地状态被并发改掉：**绝不能吞**——钱可能已经出去了，必须让人立刻看到
            log.error("结算指令已受理但本地状态更新失败（疑似并发下发）stbNo={} instructionNo={}",
                    stbNo, result.instructionNo());
            throw new BizException(ErrorCode.SETTLE_DISPATCH_CONFLICT,
                    "支付指令已受理（指令号 " + result.instructionNo() + "）但批次状态已被并发修改，"
                            + "请人工核对机构流水后再处理");
        }
        log.warn("结算下发成功 stbNo={} 指令号={} 金额={} 操作人={}", stbNo, result.instructionNo(),
                batch.getTotalAmount(), operatorId);
        return result.instructionNo();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettleBatchVo> list(String carrierCode, String period) {
        long tenant = TenantContext.tenantId();
        List<FnSettleBatchPo> batches = batchMapper.selectListBy(tenant, carrierCode, period);
        List<SettleBatchVo> vos = new ArrayList<>(batches.size());
        for (FnSettleBatchPo batch : batches) {
            vos.add(toVo(batch));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public SettleBatchVo detail(String stbNo) {
        long tenant = TenantContext.tenantId();
        FnSettleBatchPo batch = batchMapper.selectByNo(tenant, stbNo);
        if (batch == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结算批次不存在：" + stbNo);
        }
        return toVo(batch);
    }

    /**
     * 批次 → Vo。
     *
     * @param batch 批次
     * @return Vo
     */
    private SettleBatchVo toVo(FnSettleBatchPo batch) {
        return new SettleBatchVo(batch.getStbNo(), batch.getCarrierCode(), batch.getPeriod(), batch.getStatus(),
                batch.getWaybillCount() == null ? 0 : batch.getWaybillCount(),
                batch.getTotalAmount() == null ? BigDecimal.ZERO : batch.getTotalAmount(),
                batch.getInstructionNo(), batch.getFailReason());
    }
}
