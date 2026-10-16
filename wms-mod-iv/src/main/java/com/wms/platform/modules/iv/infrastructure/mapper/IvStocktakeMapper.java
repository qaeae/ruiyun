package com.wms.platform.modules.iv.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.iv.infrastructure.po.IvStocktakePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 盘点单 Mapper（表 {@code iv_stocktake}）。 */
@Mapper
public interface IvStocktakeMapper extends BaseMapper<IvStocktakePo> {

    /**
     * 按单号查。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @return 盘点单，不存在返回 {@code null}
     */
    default IvStocktakePo selectByNo(long tenantId, String stocktakeNo) {
        return selectOne(Wrappers.<IvStocktakePo>lambdaQuery()
                .eq(IvStocktakePo::getTenantId, tenantId)
                .eq(IvStocktakePo::getStocktakeNo, stocktakeNo)
                .last("limit 1"));
    }

    /**
     * 列表（按创建时间倒序）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库（可空）
     * @param status      状态（可空）
     * @return 列表
     */
    default List<IvStocktakePo> selectListBy(Long tenantId, Long warehouseId, String status) {
        return selectList(Wrappers.<IvStocktakePo>lambdaQuery()
                .eq(warehouseId != null, IvStocktakePo::getWarehouseId, warehouseId)
                .eq(status != null && !status.isBlank(), IvStocktakePo::getStatus, status)
                .orderByDesc(IvStocktakePo::getCreatedAt));
    }

    /**
     * 提交审批（条件更新：仅「待录入」可提交，避免并发重复提交跨状态）。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @param status      目标状态
     * @param diffCount   有差异条数
     * @param operatorId  操作人
     * @return 影响行数（0 = 状态已变，调用方应报冲突）
     */
    @Update("UPDATE iv_stocktake SET status = #{status}, diff_count = #{diffCount}, submitted_by = #{operatorId}, "
            + "submitted_at = NOW(3), updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND stocktake_no = #{stocktakeNo} AND status = '待录入' AND deleted = 0")
    int submit(@Param("tenantId") long tenantId, @Param("stocktakeNo") String stocktakeNo,
               @Param("status") String status, @Param("diffCount") int diffCount,
               @Param("operatorId") Long operatorId);

    /**
     * 审批通过（条件更新：仅「待审批」；同时落审批人，供双人复核留痕）。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @param status      目标状态
     * @param approverId  审批人
     * @return 影响行数（0 = 状态已变）
     */
    @Update("UPDATE iv_stocktake SET status = #{status}, approved_by = #{approverId}, approved_at = NOW(3), "
            + "updated_by = #{approverId} "
            + "WHERE tenant_id = #{tenantId} AND stocktake_no = #{stocktakeNo} AND status = '待审批' AND deleted = 0")
    int approve(@Param("tenantId") long tenantId, @Param("stocktakeNo") String stocktakeNo,
                @Param("status") String status, @Param("approverId") Long approverId);

    /**
     * 驳回（条件更新：仅「待审批」）。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @param status      目标状态
     * @param reason      驳回原因
     * @param operatorId  操作人
     * @return 影响行数（0 = 状态已变）
     */
    @Update("UPDATE iv_stocktake SET status = #{status}, reason = #{reason}, approved_by = #{operatorId}, "
            + "approved_at = NOW(3), updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND stocktake_no = #{stocktakeNo} AND status = '待审批' AND deleted = 0")
    int reject(@Param("tenantId") long tenantId, @Param("stocktakeNo") String stocktakeNo,
               @Param("status") String status, @Param("reason") String reason,
               @Param("operatorId") Long operatorId);

    /**
     * 回写明细条数（建单后统计）。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @param itemCount   条数
     * @param updatedAt   更新时间（便于审计）
     * @param operatorId  操作人
     * @return 影响行数
     */
    default int updateItemCount(long tenantId, String stocktakeNo, int itemCount, LocalDateTime updatedAt,
                                Long operatorId) {
        return update(null, Wrappers.<IvStocktakePo>lambdaUpdate()
                .eq(IvStocktakePo::getTenantId, tenantId)
                .eq(IvStocktakePo::getStocktakeNo, stocktakeNo)
                .set(IvStocktakePo::getItemCount, itemCount)
                .set(IvStocktakePo::getUpdatedBy, operatorId));
    }
}
