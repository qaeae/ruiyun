package com.wms.platform.modules.iv.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.iv.infrastructure.po.IvMovementPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存流水 Mapper（表 {@code iv_movement}）。
 *
 * <p><b>只暴露 insert</b>：流水不可改不可删（{@code 01} §2）。这一限制不是洁癖——
 * 一旦允许改流水，"账实不符"就再也无法通过流水追溯定位（改了哪一笔、谁改的都说不清）。
 *
 * <p>唯一键 {@code uk_movement_idem(tenant_id, idem_key)} 由数据库承担去重，
 * 应用层不预先查询（预查再插在并发下不可靠，且多一次往返）。
 */
@Mapper
public interface IvMovementMapper extends BaseMapper<IvMovementPo> {

    /**
     * 幂等键是否已有流水（{@code uk_movement_idem} 的存在性查询）。
     *
     * <p>用于回答"幂等表已过期，但这个动作到底做过没有"——离线补传超过 7 天时唯一的判据。
     *
     * @param tenantId 租户
     * @param idemKey  幂等键
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM iv_movement WHERE tenant_id = #{tenantId} AND idem_key = #{idemKey}")
    long countByIdemKey(@Param("tenantId") long tenantId, @Param("idemKey") String idemKey);

    /**
     * 某业务单号某 SKU 的**当前剩余预占量**（{@code RESERVE} 之和 − {@code UNRESERVE} 之和）。
     *
     * <p><b>为什么需要它（而不是去改表加一列"已释放缺货量"）</b>：取消出库时要释放"还剩多少预占"，
     * 而缺货上报已经把一部分预占释放掉了——{@code out_order_item} 上没有记录这个数。
     * 两条路：加一列（要写 Flyway 迁移、且与流水两处记同一事实，迟早不一致），
     * 或者**用流水本身算**。流水是"不可删除"的账，算出来的数天然与库存动作一致，因此选后者。
     *
     * @param tenantId 租户
     * @param bizNo    业务单号（出库单号）
     * @param skuId    商品
     * @return 剩余预占量（无记录返回 0）
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN movement_type = 'RESERVE' THEN qty ELSE -qty END), 0)
              FROM iv_movement
             WHERE tenant_id = #{tenantId} AND biz_no = #{bizNo} AND sku_id = #{skuId}
               AND movement_type IN ('RESERVE', 'UNRESERVE')
            """)
    java.math.BigDecimal sumReservedRemaining(@Param("tenantId") long tenantId, @Param("bizNo") String bizNo,
                                              @Param("skuId") long skuId);


    /**
     * 按业务单号查流水（API-IV-02 追单）。
     *
     * @param tenantId 租户
     * @param bizType  业务类型（DO/ASN/STOCKTAKE/RETURN/MOVE）
     * @param bizNo    业务单号
     * @return 流水列表（按发生时间正序）
     */
    default List<IvMovementPo> selectByBiz(long tenantId, String bizType, String bizNo) {
        return selectList(Wrappers.<IvMovementPo>lambdaQuery()
                .eq(IvMovementPo::getTenantId, tenantId)
                .eq(bizType != null && !bizType.isBlank(), IvMovementPo::getBizType, bizType)
                .eq(bizNo != null && !bizNo.isBlank(), IvMovementPo::getBizNo, bizNo)
                .orderByAsc(IvMovementPo::getOccurredAt));
    }

    /**
     * 按 SKU + 仓库查流水（库存流水查询的常规入口）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param skuId       商品
     * @param limit       最大条数
     * @return 流水列表（按发生时间倒序，最近的在前面）
     */
    default List<IvMovementPo> selectBySku(long tenantId, long warehouseId, long skuId, int limit) {
        return selectList(Wrappers.<IvMovementPo>lambdaQuery()
                .eq(IvMovementPo::getTenantId, tenantId)
                .eq(IvMovementPo::getWarehouseId, warehouseId)
                .eq(IvMovementPo::getSkuId, skuId)
                .orderByDesc(IvMovementPo::getOccurredAt)
                .last("limit " + Math.max(1, limit)));
    }
}
