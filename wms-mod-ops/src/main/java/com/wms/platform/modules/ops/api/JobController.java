package com.wms.platform.modules.ops.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.ops.api.vo.JobRunVo;
import com.wms.platform.modules.ops.api.vo.JobVo;
import com.wms.platform.modules.ops.app.ScheduledJobService;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 定时任务运维接口（03 API-OPS-01）。
 *
 * <p><b>手工补跑的价值与风险</b>：出问题时运维的第一反应是"能不能再跑一次"。
 * 若没有这个入口，他们就去改数据库、改 cron、重启服务——每一种都更危险。
 * 因此补跑入口是必须的，但它与定时走**同一段代码**（含租约），
 * 因此不会出现"补跑能成功、定时总失败"这类无法解释的差异。
 *
 * <p>权限：查询用 {@code ops:job:query}，补跑与启停用 {@code ops:job:run}
 * （补跑会触发真实业务动作：扣款、勾稽、告警，因此单独分权）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    /** 调度服务。 */
    private final ScheduledJobService scheduledJobService;

    /**
     * 任务一览（含租约状态与最近结果）。
     *
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.OPS_JOB_QUERY)
    @GetMapping
    public ApiResponse<List<JobVo>> listJobs() {
        return ApiResponse.ok(scheduledJobService.listJobs());
    }

    /**
     * 执行历史。
     *
     * @param jobName 任务名（可空 = 全部）
     * @param limit   条数（默认 50）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.OPS_JOB_QUERY)
    @GetMapping("/runs")
    public ApiResponse<List<JobRunVo>> listRuns(@RequestParam(required = false) String jobName,
                                                @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(scheduledJobService.listRuns(jobName, limit));
    }

    /**
     * 手工补跑（与定时共用同一执行壳，含租约保护）。
     *
     * @param jobName 任务名
     * @return 本次执行结果
     */
    @RequiresPermission(PermissionCodes.OPS_JOB_RUN)
    @PostMapping("/{jobName}/run")
    public ApiResponse<JobRunVo> run(@PathVariable String jobName) {
        return ApiResponse.ok(scheduledJobService.run(JobName.of(jobName), true));
    }

    /**
     * 启用/停用任务（停用后定时与补跑都会被跳过）。
     *
     * @param jobName 任务名
     * @param enabled 是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.OPS_JOB_RUN)
    @PostMapping("/{jobName}/enabled")
    public ApiResponse<Map<String, Object>> setEnabled(@PathVariable String jobName,
                                                       @RequestParam boolean enabled) {
        scheduledJobService.setEnabled(jobName, enabled);
        return ApiResponse.ok(Map.of("jobName", jobName, "enabled", enabled));
    }
}
