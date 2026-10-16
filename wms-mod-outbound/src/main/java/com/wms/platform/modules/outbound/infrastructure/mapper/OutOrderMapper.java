package com.wms.platform.modules.outbound.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库单 Mapper（表 {@code out_order}）。
 *
 * <p><b>状态迁移一律用条件更新（{@code WHERE status = 原状态}）</b>：两个人同时点"装车"、
 * 或取消与装车并发时，只有一个能改成功（影响行数 = 1），另一个得到 0 行 → 上层按
 * {@code 20002 非法状态流转}（409）返回。若改成 {@code updateById}，后写的会**静默覆盖**先写的，
 * 现场表现是"点了取消但单子还是出库了"——这类丢失更新在单据系统里是致命的。
 *
 * <p><b>分页为什么手写 LIMIT/OFFSET</b>：MyBatis-Plus 的 {@code selectPage} 依赖
 * {@code PaginationInnerInterceptor}，而本项目没有注册该拦截器（未配置 MybatisPlusInterceptor）——
 * 用它会导致分页静默失效（返回全表）。这里显式写 LIMIT/OFFSET，行为不依赖框架配置。
 */
@Mapper
public interface OutOrderMapper extends BaseMapper<OutOrderPo> {

    /**
     * 按出库单号查（唯一键 {@code uk_do_no}）。
     *
     * @param tenantId 租户
     * @param doNo     出库单号
     * @return 出库单，不存在返回 {@code null}
     */
    default OutOrderPo selectByNo(long tenantId, String doNo) {
        return selectOne(Wrappers.<OutOrderPo>lambdaQuery()
                .eq(OutOrderPo::getTenantId, tenantId)
                .eq(OutOrderPo::getDoNo, doNo)
                .last("limit 1"));
    }

    /**
     * 按上游单号查（防重复下单唯一键 {@code uk_source_order} 的查询侧）。
     *
     * @param tenantId      租户
     * @param sourceType    来源类型
     * @param sourceOrderNo 上游订单号
     * @return 出库单，不存在返回 {@code null}
     */
    default OutOrderPo selectBySourceOrder(long tenantId, String sourceType, String sourceOrderNo) {
        if (sourceOrderNo == null || sourceOrderNo.isBlank()) {
            return null;
        }
        return selectOne(Wrappers.<OutOrderPo>lambdaQuery()
                .eq(OutOrderPo::getTenantId, tenantId)
                .eq(OutOrderPo::getSourceType, sourceType)
                .eq(OutOrderPo::getSourceOrderNo, sourceOrderNo)
                .last("limit 1"));
    }

    /**
     * 分页查询出库单（条件全部可选）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库（可空）
     * @param merchantId  商家（可空）
     * @param status      状态（可空）
     * @param doNo        单号模糊（可空）
     * @param offset      偏移
     * @param limit       条数
     * @return 出库单列表
     */
    default List<OutOrderPo> selectPageList(long tenantId, Long warehouseId, Long merchantId, String status,
                                            String doNo, int offset, int limit) {
        return selectList(Wrappers.<OutOrderPo>lambdaQuery()
                .eq(OutOrderPo::getTenantId, tenantId)
                .eq(warehouseId != null, OutOrderPo::getWarehouseId, warehouseId)
                .eq(merchantId != null, OutOrderPo::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), OutOrderPo::getStatus, status)
                .like(doNo != null && !doNo.isBlank(), OutOrderPo::getDoNo, doNo)
                .orderByDesc(OutOrderPo::getCreatedAt)
                .last("limit " + limit + " offset " + offset));
    }

    /**
     * 分页总数（条件与 {@link #selectPageList} 保持一致，否则会出现"总数 100、本页 0 条"）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库（可空）
     * @param merchantId  商家（可空）
     * @param status      状态（可空）
     * @param doNo        单号模糊（可空）
     * @return 总数
     */
    default long countPage(long tenantId, Long warehouseId, Long merchantId, String status, String doNo) {
        return selectCount(Wrappers.<OutOrderPo>lambdaQuery()
                .eq(OutOrderPo::getTenantId, tenantId)
                .eq(warehouseId != null, OutOrderPo::getWarehouseId, warehouseId)
                .eq(merchantId != null, OutOrderPo::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), OutOrderPo::getStatus, status)
                .like(doNo != null && !doNo.isBlank(), OutOrderPo::getDoNo, doNo));
    }

    /**
     * 状态迁移（条件更新：仅当当前状态等于期望的"迁移前状态"才生效）。
     *
     * @param tenantId    租户
     * @param id          出库单 ID
     * @param fromStatus  迁移前状态
     * @param toStatus    迁移后状态
     * @param cancelStage 取消时点状态（仅取消时有值，其它事件传 {@code null} 保持不变）
     * @param now         事件时间（写入取消/完成时间戳）
     * @param operatorId  操作人
     * @return 影响行数（0 = 状态已被并发改动，上层按 20002 返回）
     */
    @Update("""
            UPDATE out_order
               SET status = #{toStatus},
                   cancel_stage = COALESCE(#{cancelStage}, cancel_stage),
                   cancelled_at = CASE WHEN #{toStatus} = '已取消' THEN #{now} ELSE cancelled_at END,
                   finished_at = CASE WHEN #{toStatus} = '已出库' THEN #{now} ELSE finished_at END,
                   updated_by = #{operatorId}
             WHERE tenant_id = #{tenantId} AND id = #{id} AND status = #{fromStatus} AND deleted = 0
            """)
    int transition(@Param("tenantId") long tenantId, @Param("id") long id,
                   @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                   @Param("cancelStage") String cancelStage, @Param("now") LocalDateTime now,
                   @Param("operatorId") Long operatorId);

    /**
     * 回填称重量方结果。
     *
     * @param tenantId      租户
     * @param id            出库单 ID
     * @param actualWeight  实称重量
     * @param actualVolume  实量体积（可空）
     * @param packageCount  件数（可空）
     * @param actualWeightRequired 是否要求必须带重量（true 时重量为空则该次调用无效）
     * @param operatorId    操作人
     * @return 影响行数
     */
    @Update("""
            UPDATE out_order
               SET actual_weight = COALESCE(#{actualWeight}, actual_weight),
                   actual_volume = COALESCE(#{actualVolume}, actual_volume),
                   package_count = COALESCE(#{packageCount}, package_count),
                   weight_checked = 1,
                   updated_by = #{operatorId}
             WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0
               AND (#{actualWeightRequired} = 0 OR #{actualWeight} IS NOT NULL)
            """)
    int updateMeasure(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("actualWeight") BigDecimal actualWeight, @Param("actualVolume") BigDecimal actualVolume,
                      @Param("packageCount") Integer packageCount,
                      @Param("actualWeightRequired") boolean actualWeightRequired,
                      @Param("operatorId") Long operatorId);

    /**
     * 确认已扫码交接给装卸工（未交接不可装车，{@code 20005}）。
     *
     * @param tenantId   租户
     * @param id         出库单 ID
     * @param operatorId 操作人（装卸工）
     * @return 影响行数
     */
    @Update("UPDATE out_order SET handover_confirmed = 1, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int confirmHandover(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("operatorId") Long operatorId);

    /**
     * 累加面单打印次数（打包/补打登记；出库单侧只计数，真实打印登记在物流域）。
     *
     * @param tenantId   租户
     * @param id         出库单 ID
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE out_order SET label_print_count = label_print_count + 1, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int increaseLabelPrintCount(@Param("tenantId") long tenantId, @Param("id") long id,
                                @Param("operatorId") Long operatorId);

    /**
     * 写备注（异常拦截原因、部分发货余量单号）。
     *
     * @param tenantId   租户
     * @param id         出库单 ID
     * @param remark     备注
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE out_order SET remark = #{remark}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int updateRemark(@Param("tenantId") long tenantId, @Param("id") long id, @Param("remark") String remark,
                     @Param("operatorId") Long operatorId);

    /**
     * 待装车单数（装车前置校验的辅助统计，运维/报表用）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @return 条数
     */
    @Select("SELECT COUNT(1) FROM out_order WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId} "
            + "AND status = '待装车' AND deleted = 0")
    long countAwaitingLoad(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId);
}
