package com.wms.platform.modules.outbound.api.vo;

import java.time.LocalDateTime;

/**
 * 状态轨迹行（API-OUT-13 {@code /{doNo}/status-logs}）。
 *
 * <p>轨迹来自 {@code pf_biz_status_log}：每一步迁移的**事件、操作人、设备、时间、traceId**
 * 都在这里，是现场出问题（"这单谁装的车""为什么被拦截"）时的第一手证据。
 * 因此 {@code idemKey} 也一并回传（排障时用它对齐幂等记录），但**不回传密文/PII**。
 *
 * @param fromStatus  迁移前状态（建单为 {@code null}）
 * @param toStatus    迁移后状态
 * @param event       事件名
 * @param operatorId  操作人 ID
 * @param operatorRole 操作人角色（可空）
 * @param deviceCode  设备编号（可空）
 * @param remark      备注（取消回滚计划、缺货原因、拦截原因）
 * @param traceId     全链路追踪 ID（可空）
 * @param idemKey     状态迁移幂等键
 * @param createdAt   发生时间
 */
public record OutStatusLogVo(String fromStatus, String toStatus, String event, Long operatorId,
                             String operatorRole, String deviceCode, String remark, String traceId,
                             String idemKey, LocalDateTime createdAt) {
}
