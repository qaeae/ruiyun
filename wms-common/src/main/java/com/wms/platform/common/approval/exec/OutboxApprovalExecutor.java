package com.wms.platform.common.approval.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.approval.ApprovalContext;
import com.wms.platform.common.messaging.EventTopics;
import com.wms.platform.common.messaging.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 发件箱 + 事件执行：把"审批通过"变成一条事件，由消费方异步执行（**最终一致**）。
 *
 * <p><b>时序</b>：审批状态变更与发件箱记录在同一个事务里提交 →
 * 提交后**立即尝试投递一次**（把延迟压到毫秒级）→ 若失败，由 ops 的 {@code OUTBOX_RELAY} 定时补投。
 * 立即投递挂在 {@code afterCommit} 上：只有事务真的提交了才发，
 * 否则会出现"发了消息但审批回滚"（下游执行了不该执行的事）。
 *
 * <p><b>为什么必须异步化（拆分后的硬约束）</b>：审批在 `wms-auth`（或 core 的 au），
 * 执行方在库存/财务，跨进程无法共享数据库事务。事件化之后，
 * 执行方不再需要"被审批方直接调用"，两边只依赖**事件契约**（放在共享内核）。
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxApprovalExecutor implements ApprovalExecutor {

    /** 聚合根类型：审批单。 */
    public static final String AGGREGATE_APPROVAL = "APPROVAL";

    /** 发件箱。 */
    private final OutboxService outboxService;

    /** JSON（事件载荷用与业务一致的序列化口径）。 */
    private final ObjectMapper objectMapper;

    @Override
    public void execute(ApprovalContext context, long approverId) {
        ApprovalApprovedEvent event = new ApprovalApprovedEvent(
                context.tenantId(), context.approvalId(),
                context.approvalNo(), context.bizType().code(), context.bizId(), context.bizNo(),
                context.warehouseId(), context.merchantId(),
                context.amount() == null ? null : context.amount().toPlainString(),
                context.payloadJson(), context.payloadHash(), context.reason(), context.applyBy(), approverId);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            // B5：异常必须带错误码；这里属于"事件载荷无法序列化"，属于系统缺陷而非用户输入问题
            throw new BizException(ErrorCode.SYSTEM_ERROR, "审批事件序列化失败 approvalNo=" + context.approvalNo(), ex);
        }
        // 路由键由业务类型决定（唯一来源），并**随事件一起落库**：投递阶段不再重新判断
        long outboxId = outboxService.write(context.tenantId(), EventTopics.EVENT_TYPE_APPROVAL_APPROVED,
                context.bizType().routingKey(), AGGREGATE_APPROVAL, context.approvalId(), payload);
        log.info("[审批] 已写入发件箱，事件将异步执行 approvalNo={} bizType={} outboxId={}",
                context.approvalNo(), context.bizType().code(), outboxId);
        publishAfterCommit(context.tenantId(), outboxId);
    }

    /**
     * 事务提交后立即投递一次（best-effort；失败不影响审批结果，定时任务会补）。
     *
     * @param tenantId 租户
     * @param outboxId 发件箱记录 ID
     */
    private void publishAfterCommit(long tenantId, long outboxId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (!outboxService.publishNow(tenantId, outboxId)) {
                        log.warn("[审批] 提交后立即投递失败，已安排定时重投 outboxId={}", outboxId);
                    }
                }
            });
        } else {
            // 无事务上下文（例如被直接调用）：直接投递
            outboxService.publishNow(tenantId, outboxId);
        }
    }
}
