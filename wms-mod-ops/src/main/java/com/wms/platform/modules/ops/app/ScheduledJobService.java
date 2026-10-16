package com.wms.platform.modules.ops.app;

import com.wms.platform.modules.ops.api.vo.JobRunVo;
import com.wms.platform.modules.ops.api.vo.JobVo;
import com.wms.platform.modules.ops.domain.JobName;
import java.util.List;

/**
 * 定时任务调度服务（`01` §3.17）。
 *
 * <p><b>统一执行壳</b>：无论触发方式是 `@Scheduled` 还是手工补跑，都经过
 * "抢租约 → 写执行记录 → 执行 → 记结果 → 释放租约"这同一段代码。
 * 两条路径分开实现是运维事故的常见来源（"补跑能成功、定时总失败"）。
 */
public interface ScheduledJobService {

    /**
     * 执行任务（租约保护 + 留痕 + 异常隔离）。
     *
     * @param job    任务
     * @param manual 是否手工补跑
     * @return 执行结果摘要
     */
    JobRunVo run(JobName job, boolean manual);

    /**
     * 全部任务（含最近结果与统计）。
     *
     * @return 列表
     */
    List<JobVo> listJobs();

    /**
     * 某任务的执行历史。
     *
     * @param jobName 任务名（可空 = 全部）
     * @param limit   条数
     * @return 列表
     */
    List<JobRunVo> listRuns(String jobName, int limit);

    /**
     * 启用/停用任务。
     *
     * @param jobName 任务名
     * @param enabled 是否启用
     */
    void setEnabled(String jobName, boolean enabled);
}
