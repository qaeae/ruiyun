package com.wms.platform.common.approval.exec;

import java.math.BigDecimal;

/**
 * 审批通过事件（跨服务契约）。
 *
 * <p>契约放在共享内核而不是某个业务域：生产方（审批）与消费方（库存/财务）都依赖它，
 * 若放在 au 域，消费方就又被迫依赖 au——这正是 P1.3/P2 要消掉的耦合。
 *
 * <p>{@code payloadJson} 是**审批时固化的业务快照**：执行必须按快照执行，
 * 不能按"现在的最新数据"，否则审批的和执行的就不是同一件事（详见 {@code ApprovalRule} 的指纹校验）。
 *
 * <p>金额用 {@link String} 而不是 {@link BigDecimal}：跨进程传输时字符串不会丢精度、
 * 也不受各方 JSON 配置（是否按数字解析小数）影响；消费侧需要时再转回 {@code BigDecimal}。
 */
public record ApprovalApprovedEvent(long tenantId, long approvalId, String approvalNo, String bizType,
                                    Long bizId, String bizNo,
                                    Long warehouseId, Long merchantId, String amount, String payloadJson, String payloadHash,
                                    String reason, Long applyBy, Long approverId) {
}
