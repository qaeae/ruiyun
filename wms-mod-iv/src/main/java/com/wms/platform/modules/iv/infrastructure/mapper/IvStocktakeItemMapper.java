package com.wms.platform.modules.iv.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.iv.domain.StocktakeRule;
import com.wms.platform.modules.iv.infrastructure.po.IvStocktakeItemPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** 盘点明细 Mapper（表 {@code iv_stocktake_item}）。 */
@Mapper
public interface IvStocktakeItemMapper extends BaseMapper<IvStocktakeItemPo> {

    /**
     * 查某盘点单的全部明细。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @return 列表（按库位、商品排序，结果稳定）
     */
    default List<IvStocktakeItemPo> selectByStocktake(long tenantId, long stocktakeId) {
        return selectList(Wrappers.<IvStocktakeItemPo>lambdaQuery()
                .eq(IvStocktakeItemPo::getTenantId, tenantId)
                .eq(IvStocktakeItemPo::getStocktakeId, stocktakeId)
                .orderByAsc(IvStocktakeItemPo::getLocationId, IvStocktakeItemPo::getSkuId));
    }

    /**
     * 查某条明细。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @param skuId       商品
     * @param locationId  库位
     * @return 明细，不存在返回 {@code null}
     */
    default IvStocktakeItemPo selectOneItem(long tenantId, long stocktakeId, long skuId, long locationId) {
        return selectOne(Wrappers.<IvStocktakeItemPo>lambdaQuery()
                .eq(IvStocktakeItemPo::getTenantId, tenantId)
                .eq(IvStocktakeItemPo::getStocktakeId, stocktakeId)
                .eq(IvStocktakeItemPo::getSkuId, skuId)
                .eq(IvStocktakeItemPo::getLocationId, locationId)
                .last("limit 1"));
    }

    /**
     * 有差异的明细条数（提交时统计，供审批人判断工作量与风险）。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM iv_stocktake_item WHERE tenant_id = #{tenantId} "
            + "AND stocktake_id = #{stocktakeId} AND deleted = 0 AND diff_qty IS NOT NULL AND diff_qty <> 0")
    long countDiff(@Param("tenantId") long tenantId, @Param("stocktakeId") long stocktakeId);

    /**
     * 明细条数。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM iv_stocktake_item WHERE tenant_id = #{tenantId} "
            + "AND stocktake_id = #{stocktakeId} AND deleted = 0")
    long countItems(@Param("tenantId") long tenantId, @Param("stocktakeId") long stocktakeId);

    /**
     * 录入实盘（条件更新：仅「待录入」；**已提交的明细不许再改**，否则审批看到的内容与执行的不一致）。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @param skuId       商品
     * @param locationId  库位
     * @param actualQty   实盘数
     * @param diffQty     差异
     * @param remark      备注
     * @param operatorId  操作人
     * @return 影响行数（0 = 未找到 / 已录入过 / 已被改动）
     */
    @Update("UPDATE iv_stocktake_item SET actual_qty = #{actualQty}, diff_qty = #{diffQty}, "
            + "item_status = '已录入', remark = #{remark}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND stocktake_id = #{stocktakeId} AND sku_id = #{skuId} "
            + "AND location_id = #{locationId} AND item_status = '待录入' AND deleted = 0")
    int record(@Param("tenantId") long tenantId, @Param("stocktakeId") long stocktakeId,
               @Param("skuId") long skuId, @Param("locationId") long locationId,
               @Param("actualQty") BigDecimal actualQty, @Param("diffQty") BigDecimal diffQty,
               @Param("remark") String remark, @Param("operatorId") Long operatorId);

    /**
     * 标记已调整（审批通过并生成 ADJUST 流水后调用；**幂等**：已调整的不会被再次更新）。
     *
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @param skuId       商品
     * @param locationId  库位
     * @param operatorId  操作人
     * @return 影响行数
     */
    default int markAdjusted(long tenantId, long stocktakeId, long skuId, long locationId, Long operatorId) {
        return update(null, Wrappers.<IvStocktakeItemPo>lambdaUpdate()
                .eq(IvStocktakeItemPo::getTenantId, tenantId)
                .eq(IvStocktakeItemPo::getStocktakeId, stocktakeId)
                .eq(IvStocktakeItemPo::getSkuId, skuId)
                .eq(IvStocktakeItemPo::getLocationId, locationId)
                .ne(IvStocktakeItemPo::getItemStatus, StocktakeRule.ITEM_ADJUSTED)
                .set(IvStocktakeItemPo::getItemStatus, StocktakeRule.ITEM_ADJUSTED)
                .set(IvStocktakeItemPo::getUpdatedBy, operatorId));
    }
}
