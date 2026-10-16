package com.wms.platform.common.approval.exec;

import com.wms.platform.common.approval.ApprovalContext;

/**
 * 审批通过后的**执行策略**（本项目两种实现，按 {@code wms.messaging.mode} 选择）。
 *
 * <p>为什么要抽策略而不是直接写死一种：
 * <ul>
 *   <li>{@link InProcessApprovalExecutor}：审批事务内直接执行——**强一致**（审批通过即生效），
 *       适合单体/小规模，也是集成测试与默认行为；</li>
 *   <li>{@link OutboxApprovalExecutor}：写事务性发件箱 + 经 MQ 异步执行——**最终一致**，
 *       适合拆分后"执行方是另一个服务"的场景（跨进程不能共享事务）。</li>
 * </ul>
 * 两者对调用方（审批服务）完全透明，因此切换是一个配置项，而不是改代码。
 */
public interface ApprovalExecutor {

    /**
     * 执行审批通过后的业务动作。
     *
     * @param context 审批上下文（含快照载荷）
     * @param approverId 复核人
     */
    void execute(ApprovalContext context, long approverId);
}
