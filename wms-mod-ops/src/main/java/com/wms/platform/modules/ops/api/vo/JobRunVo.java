package com.wms.platform.modules.ops.api.vo;

import java.time.LocalDateTime;

/**
 * 任务执行记录视图（03 API-OPS-01）。
 *
 * @param id          记录 ID
 * @param jobName     任务名
 * @param triggerType 触发方式（SCHEDULE/MANUAL）
 * @param status      结果（成功/失败/跳过）
 * @param startedAt   开始时间
 * @param endedAt     结束时间
 * @param costMs      耗时（毫秒）
 * @param message     结果摘要或异常信息
 * @param operatorId  手工补跑的操作人
 */
public record JobRunVo(Long id, String jobName, String triggerType, String status, LocalDateTime startedAt,
                       LocalDateTime endedAt, Long costMs, String message, Long operatorId) {
}
