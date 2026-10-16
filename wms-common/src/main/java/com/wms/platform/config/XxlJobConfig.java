package com.wms.platform.config;

import com.wms.platform.common.config.WmsProperties;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器装配（P2.2，仅 {@code wms.xxl-job.enabled=true} 时生效）。
 *
 * <p><b>并存方案的边界（本项目的明确取舍）</b>：
 * <table border="1">
 *   <caption>职责划分</caption>
 *   <tr><th>能力</th><th>由谁负责</th></tr>
 *   <tr><td>调度触发、分片、失败重试、执行历史、告警</td><td><b>XXL-JOB</b></td></tr>
 *   <tr><td>租约互斥（多实例只有一个执行）、幂等、事务边界</td><td><b>我们自己的任务框架</b>（{@code pf_scheduled_job}）</td></tr>
 * </table>
 *
 * <p><b>为什么不把业务逻辑写进 {@code @XxlJob} 方法里</b>：那样会丢掉我们已有的租约与执行历史，
 * 而且"XXL-JOB 重试 + 我们的事务"叠在一起容易出现重复执行。
 * 本项目所有 {@code @XxlJob} 处理器都只做一件事——**转调 {@code ScheduledJobService}**，
 * 于是无论谁触发（DB 租约定时器 / XXL-JOB / 人工补跑），走的都是同一条被验证过的路径。
 *
 * <p><b>默认关闭</b>：与 {@code wms.messaging.mode} 同一种做法——默认不依赖外部组件，
 * CI 与新人环境不会被调度中心卡住；要用时显式打开。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wms.xxl-job", name = "enabled", havingValue = "true")
public class XxlJobConfig {

    /** 配置。 */
    private final WmsProperties wmsProperties;

    /**
     * 执行器：自动向调度中心注册，并按 {@code @XxlJob} 注解暴露处理器。
     *
     * @return 执行器
     */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        WmsProperties.XxlJob config = wmsProperties.getXxlJob();
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(config.getAdminAddresses());
        executor.setAccessToken(config.getAccessToken());
        executor.setAppname(config.getAppName());
        // 端口固定为 0 时由系统分配（单机多实例时不会互相抢占）；生产建议显式指定
        executor.setPort(config.getPort());
        executor.setLogPath(config.getLogPath());
        executor.setLogRetentionDays(config.getLogRetentionDays());
        log.info("[XXL-JOB] 执行器已装配 appName={} admin={} port={}（调度由调度中心负责，"
                        + "租约与幂等仍由本项目的任务框架保证）",
                config.getAppName(), config.getAdminAddresses(), config.getPort());
        return executor;
    }
}
