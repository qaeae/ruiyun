package com.wms.platform.modules.ops.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.ops.infrastructure.po.PfScheduledJobPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务 Mapper（表 {@code pf_scheduled_job}）。
 *
 * <p><b>租约抢占必须用 CAS，不能"先查再抢"</b>：两个实例同时查到"租约可抢"、
 * 然后都去写，就会双双进入执行——这正是多实例场景下最容易犯的错，而且本地单机测不出来。
 */
@Mapper
public interface PfScheduledJobMapper extends BaseMapper<PfScheduledJobPo> {

    /**
     * 按任务名查（含租户过滤）。
     *
     * @param tenantId 租户
     * @param jobName  任务名
     * @return 任务行，不存在返回 {@code null}
     */
    default PfScheduledJobPo selectByName(long tenantId, String jobName) {
        return selectOne(Wrappers.<PfScheduledJobPo>lambdaQuery()
                .eq(PfScheduledJobPo::getTenantId, tenantId)
                .eq(PfScheduledJobPo::getJobName, jobName)
                .last("limit 1"));
    }

    /**
     * 全部任务（运维视图）。
     *
     * @param tenantId 租户
     * @return 列表（按任务名排序）
     */
    default List<PfScheduledJobPo> selectListAll(long tenantId) {
        return selectList(Wrappers.<PfScheduledJobPo>lambdaQuery()
                .eq(PfScheduledJobPo::getTenantId, tenantId)
                .orderByAsc(PfScheduledJobPo::getJobName));
    }

    /**
     * **抢租约（CAS）**：仅当无租约、租约已过期、或自己就是持有者时可抢到。
     *
     * <p>三个条件缺一不可：
     * <ul>
     *   <li>{@code lease_until IS NULL}：从未执行过；</li>
     *   <li>{@code lease_until < NOW()}：上一个实例崩了/超时，可以接管；</li>
     *   <li>{@code lease_owner = self}：同一个实例重入（例如手工补跑紧接着定时触发），
     *       放行比"自己把自己挡住"更符合预期——任务幂等，重入代价小于"补跑被拒"的困惑。</li>
     * </ul>
     *
     * @param tenantId   租户
     * @param jobName    任务名
     * @param owner      实例标识
     * @param leaseUntil 租约到期时间
     * @return 影响行数（1 = 抢到；0 = 别人正在跑，应跳过）
     */
    @Update("UPDATE pf_scheduled_job SET lease_owner = #{owner}, lease_until = #{leaseUntil} "
            + "WHERE tenant_id = #{tenantId} AND job_name = #{jobName} AND deleted = 0 AND enabled = 1 "
            + "AND (lease_until IS NULL OR lease_until < NOW(3) OR lease_owner = #{owner})")
    int tryAcquire(@Param("tenantId") long tenantId, @Param("jobName") String jobName,
                   @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 释放租约（执行结束/失败后必须调用，否则要等租约自然过期）。
     *
     * @param tenantId 租户
     * @param jobName  任务名
     * @param owner    实例标识（只释放自己的锁，避免"释放了别人的"）
     * @return 影响行数
     */
    @Update("UPDATE pf_scheduled_job SET lease_owner = NULL, lease_until = NULL "
            + "WHERE tenant_id = #{tenantId} AND job_name = #{jobName} AND lease_owner = #{owner} AND deleted = 0")
    int release(@Param("tenantId") long tenantId, @Param("jobName") String jobName, @Param("owner") String owner);

    /**
     * 记录执行结果（成功/失败/跳过 + 计数）。
     *
     * @param tenantId 租户
     * @param jobName  任务名
     * @param status   结果（成功/失败/跳过）
     * @param startAt  开始时间
     * @param endAt    结束时间
     * @param message  结果摘要
     * @return 影响行数
     */
    @Update("UPDATE pf_scheduled_job SET last_status = #{status}, last_start_at = #{startAt}, "
            + "last_end_at = #{endAt}, last_message = #{message}, "
            + "success_count = success_count + CASE WHEN #{status} = '成功' THEN 1 ELSE 0 END, "
            + "fail_count = fail_count + CASE WHEN #{status} = '失败' THEN 1 ELSE 0 END "
            + "WHERE tenant_id = #{tenantId} AND job_name = #{jobName} AND deleted = 0")
    int recordResult(@Param("tenantId") long tenantId, @Param("jobName") String jobName,
                     @Param("status") String status, @Param("startAt") LocalDateTime startAt,
                     @Param("endAt") LocalDateTime endAt, @Param("message") String message);

    /**
     * 启用/停用（运维手工介入某任务时用）。
     *
     * @param tenantId 租户
     * @param jobName  任务名
     * @param enabled  1 启用 / 0 停用
     * @return 影响行数
     */
    default int updateEnabled(long tenantId, String jobName, int enabled) {
        return update(null, Wrappers.<PfScheduledJobPo>lambdaUpdate()
                .eq(PfScheduledJobPo::getTenantId, tenantId)
                .eq(PfScheduledJobPo::getJobName, jobName)
                .set(PfScheduledJobPo::getEnabled, enabled));
    }
}
