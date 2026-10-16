package com.wms.platform.modules.ops.config;

import com.wms.platform.modules.ops.app.ScheduledJobService;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时任务调度配置（{@code 01} §3.17）。
 *
 * <p><b>为什么默认关闭</b>：{@code wms.schedule.enabled=false} 是默认值。
 * 本地/测试环境开着定时任务会造成两类麻烦：① 集成测试跑到一半被任务改了数据；
 * ② 开发机上跑着生产口径的告警，噪音干扰排查。
 * 生产通过配置显式打开——**"默认不跑"比"默认跑"安全**，因为忘记关的代价远大于忘记开。
 *
 * <p><b>为什么每个任务一个 {@code @Scheduled} 方法而不是动态注册</b>：
 * cron 写在配置里（可改），但"有哪些任务"必须是**代码里显式的**——
 * 动态注册会让"线上到底有哪些任务"变成运行时才能回答的问题。
 *
 * <p><b>多实例安全</b>：所有任务都经 {@code ScheduledJobService.run} 的租约保护，
 * 因此多实例部署时只有一个实例真正执行，其他实例记录为「跳过」。
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wms.schedule.enabled", havingValue = "true")
public class ScheduleConfig {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ScheduleConfig.class);

    /** 调度服务。 */
    private final ScheduledJobService scheduledJobService;

    /**
     * 启动时把"定时调度已启用"显式打出来（`@PostConstruct`）。
     *
     * <p>启动日志里没有这行，运维就无法一眼确认"这台实例到底跑不跑任务"——
     * 而"任务没跑"与"任务跑了但没输出"在现象上完全一样，排查起来很费时间。
     */
    @jakarta.annotation.PostConstruct
    public void logEnabled() {
        log.warn("定时任务调度已启用（多实例下由租约保证同一任务只有一个实例执行）");
    }

    /** 兜底扣减扫描：每小时（`01` §1.2 第 8 行）。 */
    @Scheduled(cron = "${wms.schedule.cron.fallback-sweep:0 5 * * * *}")
    public void fallbackSweep() {
        scheduledJobService.run(JobName.FALLBACK_SWEEP, false);
    }

    /** 日终勾稽：每天 01:30 跑 T-1（`04` §5.2）。 */
    @Scheduled(cron = "${wms.schedule.cron.daily-reconcile:0 30 1 * * *}")
    public void dailyReconcile() {
        scheduledJobService.run(JobName.DAILY_RECONCILE, false);
    }

    /** 对账差异超期告警：每天 09:00（`04` §六 T+3 制度）。 */
    @Scheduled(cron = "${wms.schedule.cron.reconcile-bill-alert:0 0 9 * * *}")
    public void reconcileBillAlert() {
        scheduledJobService.run(JobName.RECONCILE_BILL_ALERT, false);
    }

    /** 结算卡单告警：每天 09:00。 */
    @Scheduled(cron = "${wms.schedule.cron.settle-stuck-alert:0 0 9 * * *}")
    public void settleStuckAlert() {
        scheduledJobService.run(JobName.SETTLE_STUCK_ALERT, false);
    }

    /** 通道未回调补查：每 10 分钟（`04` §5.3）。 */
    @Scheduled(cron = "${wms.schedule.cron.channel-query-retry:0 */10 * * * *}")
    public void channelQueryRetry() {
        scheduledJobService.run(JobName.CHANNEL_QUERY_RETRY, false);
    }
}
