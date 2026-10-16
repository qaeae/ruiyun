package com.wms.platform.modules.ops.app;

import com.wms.platform.modules.ops.domain.JobName;

/**
 * 定时任务处理器（**唯一扩展点**：新增任务只需实现本接口 + 登记 {@link JobName}）。
 *
 * <p><b>约定</b>：
 * <ol>
 *   <li>**必须幂等**：租约只保证"同一时刻只有一个实例在跑"，不保证"只跑一次"——
 *       租约过期接管、手工补跑、重试都会导致重复执行；</li>
 *   <li>**必须快**：单次执行控制在 1 分钟内（租约默认 10 分钟，留足余量），
 *       量大的要分批（如兜底扫描已限制单次 500 条）；</li>
 *   <li>返回值是**结果摘要**，会写进 `pf_job_run.message` 与 `pf_scheduled_job.last_message`——
 *       写清楚"扫了多少、扣了多少、差异几条"，运维看这一行就能判断要不要介入；</li>
 *   <li>抛异常即视为失败（会被记录并计数），不要自己吞掉——吞掉的失败最终会变成"账不对但没人知道"。</li>
 * </ol>
 */
public interface JobHandler {

    /**
     * 支持的任务。
     *
     * @return 任务名
     */
    JobName supportJob();

    /**
     * 执行任务。
     *
     * @return 结果摘要（写入执行历史）
     */
    String execute();
}
