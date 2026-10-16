package com.wms.platform.modules.fns.app;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.approval.ApprovalActionHandler;
import com.wms.platform.common.approval.ApprovalContext;
import com.wms.platform.common.approval.ApprovalBizType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结算下发的复核执行器（{@code 02} M2-05 复核矩阵 + `04` §四「财务发起 + 财务负责人复核」）。
 *
 * <p><b>为什么结算下发也在矩阵里</b>：下发 = **真正把钱划出去**。
 * 前面所有环节（对账 → 确认 → 生成批次）都只是"准备"，只有下发会让资金离开账户；
 * 而且划出去之后"不反向划拨、由下期冲抵"（`04` §六），纠错成本极高。
 *
 * <p>执行时用审批单自身的**业务单号**（批次号）与快照中的金额，不读实时参数；
 * 幂等由 {@code SettlementService.dispatch} 的 CAS + 机构侧幂等号 {@code SETTLE:{批次号}} 保证。
 */
@Component
@RequiredArgsConstructor
public class SettleDispatchApprovalHandler implements ApprovalActionHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(SettleDispatchApprovalHandler.class);

    /** 结算服务。 */
    private final SettlementService settlementService;

    @Override
    public ApprovalBizType supportType() {
        return ApprovalBizType.SETTLE_DISPATCH;
    }

    @Override
    public void execute(ApprovalContext context) {
        // 1. 批次号来自审批单的业务单号（提交时写入，执行时不再由调用方提供）
        if (context.bizNo() == null || context.bizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "结算下发审批缺少业务单号（批次号），无法执行：" + context.approvalNo());
        }
        // 2. 执行下发（内部 CAS：已下发的批次会拒绝，因此重复执行不会重复付款）
        String instructionNo = settlementService.dispatch(context.bizNo(), context.approverId());
        log.warn("结算下发已按复核执行 approvalNo={} stbNo={} 指令号={} 复核人={}", context.approvalNo(),
                context.bizNo(), instructionNo, context.approverId());
    }
}
