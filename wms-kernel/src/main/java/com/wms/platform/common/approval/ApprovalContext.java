package com.wms.platform.common.approval;


import java.math.BigDecimal;

/**
 * 审批执行上下文（**不暴露 Po**：业务域只需要这些字段就能执行动作）。
 *
 * <p>刻意不带 Po 对象：那会让各业务域依赖 `au` 的**基础设施类型**。
 * 审批单的所有权属于 `au` 域，业务域只接收"我要执行什么"。
 *
 * @param tenantId   租户（**必须带上**：审批事件会跨进程投递，消费线程没有 HTTP 上下文，
 *                   缺了它下游要么查不到数据、要么写错租户）
 * @param approvalId 审批单主键（发件箱的聚合根 ID）
 * @param approvalNo 审批单号
 * @param bizType    业务类型
 * @param bizId      业务对象 ID（可空）
 * @param bizNo      业务单号
 * @param warehouseId 仓库（可空）
 * @param merchantId 商家（可空）
 * @param amount     金额（可空）
 * @param payloadJson 审批内容快照（**执行时必须以它为准**，不能用执行时的实时参数）
 * @param payloadHash 内容指纹
 * @param reason     申请事由
 * @param applyBy    发起人
 * @param approverId 复核人
 */
public record ApprovalContext(long tenantId, long approvalId, String approvalNo, ApprovalBizType bizType,
                              Long bizId, String bizNo, Long warehouseId, Long merchantId, BigDecimal amount,
                              String payloadJson, String payloadHash, String reason, Long applyBy, long approverId) {
}
