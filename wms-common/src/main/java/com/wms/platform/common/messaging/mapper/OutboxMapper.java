package com.wms.platform.common.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.common.messaging.po.OutboxPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 发件箱 Mapper。
 *
 * <p>按规范条目 B1：条件构造器只允许出现在 Mapper 的 {@code default} 方法里，
 * 因此 {@link #selectDue} 这类查询都收口在这里，Service 里看不到 Wrapper。
 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxPo> {

    /**
     * 查询到期待投递的记录（按主键升序 = 按产生顺序投递，减小下游乱序概率）。
     *
     * @param limit 单批条数
     * @return 待投递记录
     */
    default List<OutboxPo> selectDue(int limit) {
        return selectList(Wrappers.<OutboxPo>lambdaQuery()
                .eq(OutboxPo::getStatus, OutboxPo.STATUS_PENDING)
                .le(OutboxPo::getNextRetryAt, java.time.LocalDateTime.now())
                .orderByAsc(OutboxPo::getId)
                .last("limit " + limit));
    }

    /**
     * 按事件类型与聚合根查询（排查"这条审批的事件发出去没有"）。
     *
     * @param aggregateType 聚合根类型
     * @param aggregateId   聚合根 ID
     * @return 记录列表
     */
    default List<OutboxPo> selectByAggregate(String aggregateType, Long aggregateId) {
        return selectList(Wrappers.<OutboxPo>lambdaQuery()
                .eq(OutboxPo::getAggregateType, aggregateType)
                .eq(OutboxPo::getAggregateId, aggregateId)
                .orderByAsc(OutboxPo::getId));
    }

    /**
     * 标记已投递（条件更新：只更新仍为"待投递"的行）。
     *
     * <p>为什么用条件更新：立刻投递与定时重投可能并发处理同一行，
     * 条件更新保证**只有第一个成功者**能把它改成"已投递"，避免重复计数。
     *
     * @param tenantId 租户
     * @param id       主键
     * @return 影响行数（0 = 已被别的线程投递）
     */
    @Update("update pf_outbox set status = '已投递', next_retry_at = null, last_error = null, "
            + "updated_at = now(3) where id = #{id} and tenant_id = #{tenantId} and status = '待投递' "
            + "and deleted = 0")
    int markSent(@Param("tenantId") long tenantId, @Param("id") long id);

    /**
     * 标记投递失败并安排下次重试。
     *
     * @param tenantId    租户
     * @param id          主键
     * @param error       错误（已截断）
     * @param nextRetryAt 下次重试时间
     * @param status      目标状态（待投递/失败）
     * @return 影响行数
     */
    @Update("update pf_outbox set retry_count = retry_count + 1, last_error = #{error}, "
            + "next_retry_at = #{nextRetryAt}, status = #{status}, updated_at = now(3) "
            + "where id = #{id} and tenant_id = #{tenantId} and deleted = 0")
    int markFailed(@Param("tenantId") long tenantId, @Param("id") long id, @Param("error") String error,
                   @Param("nextRetryAt") java.time.LocalDateTime nextRetryAt, @Param("status") String status);

    /**
     * 统计各状态条数（告警与运维用）。
     *
     * @param status 状态
     * @return 条数
     */
    default long countByStatus(String status) {
        return selectCount(Wrappers.<OutboxPo>lambdaQuery().eq(OutboxPo::getStatus, status));
    }
}
