package com.wms.platform.modules.ops.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.ops.infrastructure.po.PfJobRunPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 任务执行历史 Mapper（表 {@code pf_job_run}）。
 *
 * <p>写入用 MyBatis-Plus 的 insert/updateById（无手写 SQL 需求）；查询按"某任务最近 N 条"与
 * "最近的失败"两种口径收敛在本类，避免 Service 里散落 Wrapper（B1）。
 */
@Mapper
public interface PfJobRunMapper extends BaseMapper<PfJobRunPo> {

    /**
     * 某任务的最近执行记录。
     *
     * @param tenantId 租户
     * @param jobName  任务名
     * @param limit    条数
     * @return 列表（按开始时间倒序）
     */
    default List<PfJobRunPo> selectRecent(long tenantId, String jobName, int limit) {
        return selectList(Wrappers.<PfJobRunPo>lambdaQuery()
                .eq(PfJobRunPo::getTenantId, tenantId)
                .eq(jobName != null && !jobName.isBlank(), PfJobRunPo::getJobName, jobName)
                .orderByDesc(PfJobRunPo::getStartedAt)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
    }

    /**
     * 最近的失败记录（排查用）。
     *
     * @param tenantId 租户
     * @param limit    条数
     * @return 列表
     */
    default List<PfJobRunPo> selectRecentFailures(long tenantId, int limit) {
        return selectList(Wrappers.<PfJobRunPo>lambdaQuery()
                .eq(PfJobRunPo::getTenantId, tenantId)
                .eq(PfJobRunPo::getStatus, "失败")
                .orderByDesc(PfJobRunPo::getStartedAt)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }
}
