package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.ops.api.vo.JobRunVo;
import com.wms.platform.modules.ops.api.vo.JobVo;
import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.app.ScheduledJobService;
import com.wms.platform.modules.ops.domain.JobLeaseRule;
import com.wms.platform.modules.ops.domain.JobName;
import com.wms.platform.modules.ops.domain.JobRunStatus;
import com.wms.platform.modules.ops.infrastructure.mapper.PfJobRunMapper;
import com.wms.platform.modules.ops.infrastructure.mapper.PfScheduledJobMapper;
import com.wms.platform.modules.ops.infrastructure.po.PfJobRunPo;
import com.wms.platform.modules.ops.infrastructure.po.PfScheduledJobPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * 定时任务调度服务实现（{@code 01} §3.17）。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 工厂）、B5（带码异常）、B7（编号步骤注释）、B9（构造注入）。
 *
 * <p><b>执行壳的五步</b>：抢租约 → 写执行记录（开始）→ 执行 → 写结果 → 释放租约。
 * 关键取舍：
 * <ul>
 *   <li>执行记录先写"开始"，结束再更新——这样**任务卡死/实例被杀**时，能留下一条"有开始没结束"的记录，
 *       而不是什么都没有（"什么都没记录"是最难排查的状态）；</li>
 *   <li>释放租约放在 {@code finally}：失败也必须释放，否则要等租约自然过期（10 分钟）才能重跑；</li>
 *   <li>异常**不向外抛**（@Scheduled 抛异常只会打日志，还可能导致后续调度静默停止），
 *       而是记录为「失败」并返回——运维看 {@code pf_job_run} 就能看到失败原因；</li>
 *   <li>"没抢到租约"记「跳过」而不是「失败」：多实例下每个周期都有一半实例跳过，
 *       记成失败会造成告警疲劳（运维会去修一个不存在的故障）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScheduledJobServiceImpl implements ScheduledJobService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobServiceImpl.class);

    /** 任务 Mapper。 */
    private final PfScheduledJobMapper jobMapper;

    /** 执行历史 Mapper。 */
    private final PfJobRunMapper runMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 全部处理器（各域提供；按 JobName 建索引，新增任务不用改本类）。 */
    private final List<JobHandler> handlers;

    /** 本实例标识（构造期算一次：默认用主机名，容器里是 Pod 名）。 */
    private final String instanceId = resolveInstanceId();

    /** 任务租户（一期单租户，多租户扩展点见 01 §3.17）。 */
    private static final long JOB_TENANT_ID = 1L;

    @Override
    public JobRunVo run(JobName job, boolean manual) {
        // 1. 任务必须先登记（首次运行时自动登记，避免"部署完忘了初始化任务表"）
        PfScheduledJobPo task = ensureRegistered(job);
        LocalDateTime startedAt = LocalDateTime.now();
        String owner = JobLeaseRule.owner(instanceId, Thread.currentThread().getId());
        // 2. 抢租约（CAS；抢不到说明别的实例正在跑）
        int acquired = jobMapper.tryAcquire(JOB_TENANT_ID, job.code(), owner,
                JobLeaseRule.leaseUntil(startedAt, JobLeaseRule.DEFAULT_LEASE_MINUTES));
        if (acquired == 0) {
            String reason = task.getEnabled() != null && task.getEnabled() == 0
                    ? "任务已停用" : "未抢到租约（其他实例正在执行）";
            log.info("任务跳过 job={} 原因={}", job.code(), reason);
            return recordRun(job, manual, startedAt, JobRunStatus.SKIPPED, reason);
        }
        // 3. 执行并留痕（先写"开始"，卡死时能留下痕迹）
        PfJobRunPo run = PfJobRunPo.of(idGenerator.nextId(), JOB_TENANT_ID, job.code(),
                manual ? "MANUAL" : "SCHEDULE", startedAt, TenantContext.userId());
        runMapper.insert(run);
        try {
            JobHandler handler = findHandler(job);
            String summary = handler.execute();
            LocalDateTime endedAt = LocalDateTime.now();
            long cost = Duration.between(startedAt, endedAt).toMillis();
            run.finish(JobRunStatus.SUCCESS.getLabel(), endedAt, cost, summary);
            runMapper.updateById(run);
            jobMapper.recordResult(JOB_TENANT_ID, job.code(), JobRunStatus.SUCCESS.getLabel(), startedAt, endedAt,
                    summary);
            log.info("任务执行成功 job={} 触发={} 耗时={}ms 摘要={}", job.code(), run.getTriggerType(), cost, summary);
            return toVo(run);
        } catch (Exception ex) {
            // 4. 失败：记录原因但不外抛（避免影响后续调度；运维从 pf_job_run 查原因）
            LocalDateTime endedAt = LocalDateTime.now();
            long cost = Duration.between(startedAt, endedAt).toMillis();
            String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            run.finish(JobRunStatus.FAILED.getLabel(), endedAt, cost, truncate(message));
            runMapper.updateById(run);
            jobMapper.recordResult(JOB_TENANT_ID, job.code(), JobRunStatus.FAILED.getLabel(), startedAt, endedAt,
                    truncate(message));
            log.error("任务执行失败 job={} 耗时={}ms（已记录，可手工补跑）", job.code(), cost, ex);
            return toVo(run);
        } finally {
            // 5. 释放租约（失败也必须释放，否则要等 10 分钟自然过期才能重跑）
            jobMapper.release(JOB_TENANT_ID, job.code(), owner);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobVo> listJobs() {
        List<PfScheduledJobPo> rows = jobMapper.selectListAll(JOB_TENANT_ID);
        List<JobVo> vos = new ArrayList<>(rows.size());
        for (PfScheduledJobPo row : rows) {
            vos.add(new JobVo(row.getJobName(), row.getDisplayName(), row.getCronDesc(),
                    row.getEnabled() != null && row.getEnabled() == 1, row.getLeaseOwner(), row.getLeaseUntil(),
                    row.getLastStatus(), row.getLastStartAt(), row.getLastEndAt(), row.getLastMessage(),
                    row.getSuccessCount() == null ? 0 : row.getSuccessCount(),
                    row.getFailCount() == null ? 0 : row.getFailCount()));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobRunVo> listRuns(String jobName, int limit) {
        List<PfJobRunPo> rows = runMapper.selectRecent(JOB_TENANT_ID, jobName, limit);
        List<JobRunVo> vos = new ArrayList<>(rows.size());
        for (PfJobRunPo row : rows) {
            vos.add(toVo(row));
        }
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(String jobName, boolean enabled) {
        JobName job = JobName.of(jobName);
        PfScheduledJobPo task = ensureRegistered(job);
        jobMapper.updateEnabled(JOB_TENANT_ID, task.getJobName(), enabled ? 1 : 0);
        log.warn("任务启停变更 job={} enabled={}", job.code(), enabled);
    }

    /**
     * 首次运行时自动登记任务（避免"部署完忘了初始化任务表"导致任务静默不跑）。
     *
     * @param job 任务
     * @return 任务行
     */
    private PfScheduledJobPo ensureRegistered(JobName job) {
        PfScheduledJobPo exists = jobMapper.selectByName(JOB_TENANT_ID, job.code());
        if (exists != null) {
            return exists;
        }
        PfScheduledJobPo po = PfScheduledJobPo.of(idGenerator.nextId(), JOB_TENANT_ID, job.code(),
                job.getDisplayName(), job.getCronDesc());
        po.setUpdatedBy(0L);
        try {
            jobMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // 并发首跑：唯一键兜住
            return jobMapper.selectByName(JOB_TENANT_ID, job.code());
        }
        return po;
    }

    /**
     * 查处理器。
     *
     * @param job 任务
     * @return 处理器
     * @throws BizException 未实现时抛 {@code 10001}
     */
    private JobHandler findHandler(JobName job) {
        for (JobHandler handler : handlers) {
            if (handler.supportJob() == job) {
                return handler;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "任务「" + job.getDisplayName() + "」尚未实现处理器（任务清单已登记，但代码未接入）");
    }

    /**
     * 只记录执行历史（跳过场景：没有执行体，不写 pf_scheduled_job 的结果统计）。
     *
     * @param job       任务
     * @param manual    是否手工
     * @param startedAt 时间
     * @param status    结果
     * @param message   摘要
     * @return 记录
     */
    private JobRunVo recordRun(JobName job, boolean manual, LocalDateTime startedAt, JobRunStatus status,
                               String message) {
        PfJobRunPo run = PfJobRunPo.of(idGenerator.nextId(), JOB_TENANT_ID, job.code(),
                manual ? "MANUAL" : "SCHEDULE", startedAt, TenantContext.userId());
        run.finish(status.getLabel(), LocalDateTime.now(), 0L, message);
        runMapper.insert(run);
        return toVo(run);
    }

    /**
     * 截断过长消息（列宽 500）。
     *
     * @param message 消息
     * @return 截断后的消息
     */
    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    /**
     * 转换。
     *
     * @param row 记录
     * @return 视图
     */
    private JobRunVo toVo(PfJobRunPo row) {
        return new JobRunVo(row.getId(), row.getJobName(), row.getTriggerType(), row.getStatus(), row.getStartedAt(),
                row.getEndedAt(), row.getCostMs(), row.getMessage(), row.getOperatorId());
    }

    /**
     * 实例标识（容器里用 hostname；本地用 hostname+pid 便于区分同机多进程）。
     *
     * @return 实例标识
     */
    private static String resolveInstanceId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid();
    }


}
