package com.wms.platform.modules.ops.domain;

import java.time.LocalDateTime;

/**
 * 任务租约规则（`01` §3.17）。
 *
 * <p><b>为什么必须有租约</b>：`@Scheduled` 在**每个实例**上都会触发。双实例部署时，
 * 日终勾稽会被算两遍（互相覆盖结论）、兜底扣减扫两遍、告警发两条。
 * 单机开发永远发现不了这个问题——它在扩容到第二个实例的那天突然出现。
 *
 * <p><b>为什么租约要带过期时间</b>：如果"执行期间一直持锁"，实例崩溃（OOM/Kill -9/网络分区）
 * 会让任务**永久卡死**，而且没人知道为什么。带过期时间后，超时即可被其他实例接管；
 * 代价是极端情况下可能重入——所以**任务本身必须幂等**（租约与幂等是两件事，缺一不可）。
 *
 * <p><b>租约时长该多长</b>：大于任务正常耗时的数倍（默认 10 分钟）。
 * 太短 → 任务还在跑就被别人接管，重复执行；太长 → 崩溃后长时间无人接管。
 * 一期取 10 分钟，并在任务实现里保持"单次执行不超过 1 分钟"的约束。
 */
public final class JobLeaseRule {

    /** 默认租约时长（分钟）。 */
    public static final int DEFAULT_LEASE_MINUTES = 10;

    private JobLeaseRule() {
    }

    /**
     * 计算租约到期时间。
     *
     * @param now      当前时间
     * @param minutes  时长（分钟）
     * @return 到期时间
     */
    public static LocalDateTime leaseUntil(LocalDateTime now, int minutes) {
        int actual = minutes <= 0 ? DEFAULT_LEASE_MINUTES : minutes;
        return now.plusMinutes(actual);
    }

    /**
     * 租约是否可被抢占。
     *
     * <p>注意：真正的抢占必须由数据库 CAS 完成（{@code UPDATE ... WHERE lease_until IS NULL OR ...}），
     * 本方法只用于**判断与测试**，不能当作并发保护——"先查再抢"在并发下必然出错。
     *
     * @param leaseUntil 当前租约到期时间（可空）
     * @param now        当前时间
     * @return 可抢占返回 {@code true}
     */
    public static boolean isLeaseAvailable(LocalDateTime leaseUntil, LocalDateTime now) {
        return leaseUntil == null || leaseUntil.isBefore(now);
    }

    /**
     * 实例标识（租约 owner 的可读格式）。
     *
     * @param instanceId 实例标识（如主机名/Pod 名）
     * @param threadId   线程 ID
     * @return owner 字符串
     */
    public static String owner(String instanceId, long threadId) {
        String base = instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
        return base + ":" + threadId;
    }
}
