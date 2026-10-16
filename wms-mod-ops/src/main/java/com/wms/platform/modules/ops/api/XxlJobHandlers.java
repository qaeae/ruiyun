package com.wms.platform.modules.ops.api;

import com.wms.platform.modules.ops.app.ScheduledJobService;
import com.wms.platform.modules.ops.domain.JobName;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 处理器（P2.2）：**只做触发转调**，业务逻辑一律不写在这里。
 *
 * <p><b>并存方案的边界</b>：
 * <ul>
 *   <li>XXL-JOB 负责：调度（cron/固定频率）、分片、失败重试、执行历史、超时告警；</li>
 *   <li>本项目任务框架负责：**租约互斥**（多实例同时被调度时只有一个真正执行）、
 *       执行历史落库（{@code pf_scheduled_job_run}）、业务幂等。</li>
 * </ul>
 *
 * <p><b>为什么必须转调而不是直接实现</b>：直接实现会绕过租约——XXL-JOB 的"失败重试"与
 * 我们的事务叠在一起，容易出现同一批账单被并发处理。转调之后，
 * 无论触发者是 DB 定时器、XXL-JOB 还是人工补跑，走的都是**同一条被验证过的路径**。
 *
 * <p>处理器命名约定 {@code wmsXxx}：与调度中心里的 {@code JobHandler} 字段一一对应，
 * 多一个前缀是为了在同一个调度中心里与其他项目区分开。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wms.xxl-job", name = "enabled", havingValue = "true")
public class XxlJobHandlers {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(XxlJobHandlers.class);

    /** 本项目任务框架（租约、历史、幂等都在里面）。 */
    private final ScheduledJobService scheduledJobService;

    /**
     * 发件箱补投（把没投出去的事件补发到 MQ）。
     *
     * <p>为什么它最适合交给调度中心：这是"可靠性兜底"任务，漏跑一次就可能让某笔审批
     * 长时间不生效。调度中心有**失败重试与超时告警**，比只靠进程内定时器更稳。
     */
    @XxlJob("wmsOutboxRelay")
    public void outboxRelay() {
        run(JobName.OUTBOX_RELAY);
    }

    /**
     * 兜底扣减扫描（揽收 7 天未回传 → 按预估价扣减）。
     */
    @XxlJob("wmsFallbackSweep")
    public void fallbackSweep() {
        run(JobName.FALLBACK_SWEEP);
    }

    /**
     * 日终四层勾稽（资金/业务/运营商/库存）。
     */
    @XxlJob("wmsDailyReconcile")
    public void dailyReconcile() {
        run(JobName.DAILY_RECONCILE);
    }

    /**
     * 通道未回调补查（支付机构异步通知丢失时主动查询）。
     */
    @XxlJob("wmsChannelQueryRetry")
    public void channelQueryRetry() {
        run(JobName.CHANNEL_QUERY_RETRY);
    }

    /**
     * 统一转调：所有处理器都走这里，保证行为一致（含日志与异常处理）。
     *
     * @param job 任务名
     */
    private void run(JobName job) {
        // 走 manual=true：对任务框架来说"外部触发"与"人工补跑"是同一条路径，
        // 区别只在触发者，而触发者已由调度中心的执行日志记录
        var result = scheduledJobService.run(job, true);
        log.info("[XXL-JOB] 触发任务 job={} status={} message={}", job.name(), result.status(), result.message());
    }
}
