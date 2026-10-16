package com.wms.platform.modules.ops.api.vo;

import java.time.LocalDateTime;

/**
 * 任务视图（03 API-OPS-01）。
 *
 * @param jobName       任务名
 * @param displayName   中文名
 * @param cronDesc      调度描述
 * @param enabled       是否启用
 * @param leaseOwner    当前持锁实例（非空 = 正在执行）
 * @param leaseUntil    租约到期时间
 * @param lastStatus    上次结果
 * @param lastStartAt   上次开始时间
 * @param lastEndAt     上次结束时间
 * @param lastMessage   上次结果摘要
 * @param successCount  成功次数
 * @param failCount     失败次数
 */
public record JobVo(String jobName, String displayName, String cronDesc, boolean enabled, String leaseOwner,
                    LocalDateTime leaseUntil, String lastStatus, LocalDateTime lastStartAt, LocalDateTime lastEndAt,
                    String lastMessage, int successCount, int failCount) {
}
