package com.wms.platform.common.approval.exec;

import com.wms.platform.common.approval.ApprovalActionHandler;
import com.wms.platform.common.approval.ApprovalBusiness;
import com.wms.platform.common.approval.ApprovalContext;
import lombok.RequiredArgsConstructor;

/**
 * 进程内执行：审批事务内直接调用执行方（**默认策略**）。
 *
 * <p>特点：强一致（审批通过的那一刻业务动作已经生效）。
 * 代价：执行方与审批方必须在同一进程、同一事务边界内——这正是拆分后要换掉的原因。
 */
@RequiredArgsConstructor
public class InProcessApprovalExecutor implements ApprovalExecutor {

    /** 业务动作查找器（按业务类型找执行方）。 */
    private final ApprovalBusiness approvalBusiness;

    @Override
    public void execute(ApprovalContext context, long approverId) {
        ApprovalActionHandler handler = approvalBusiness.findHandler(context.bizType());
        handler.execute(context);
    }
}
