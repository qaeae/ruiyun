package com.wms.platform.modules.iv.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.iv.infrastructure.po.IvLocationPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

import java.util.List;

/**
 * 库位级明细账 Mapper（表 {@code iv_location}）。
 *
 * <p>与 SKU 级一样，全部数量变更走条件更新。库位的 CAS 条件必须包含
 * {@code location_id}：按"仓库 + SKU"做 CAS 会去扣别的库位的量，现场对不出来。
 */
@Mapper
public interface IvLocationMapper extends BaseMapper<IvLocationPo> {

    /**
     * 查库位账（唯一键 {@code uk_iv_loc}）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @return 账，无记录返回 {@code null}
     */
    default IvLocationPo selectByKey(long tenantId, long warehouseId, long locationId, long merchantId, long skuId) {
        return selectOne(Wrappers.<IvLocationPo>lambdaQuery()
                .eq(IvLocationPo::getTenantId, tenantId)
                .eq(IvLocationPo::getWarehouseId, warehouseId)
                .eq(IvLocationPo::getLocationId, locationId)
                .eq(IvLocationPo::getMerchantId, merchantId)
                .eq(IvLocationPo::getSkuId, skuId)
                .eq(IvLocationPo::getBatchNo, "")
                .last("limit 1"));
    }

    /**
     * 查某 SKU 的库位账列表（API-IV-01 库位明细）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param skuId       商品
     * @return 列表（按库位排序，结果稳定）
     */
    default List<IvLocationPo> selectBySku(long tenantId, long warehouseId, long skuId) {
        return selectList(Wrappers.<IvLocationPo>lambdaQuery()
                .eq(IvLocationPo::getTenantId, tenantId)
                .eq(IvLocationPo::getWarehouseId, warehouseId)
                .eq(IvLocationPo::getSkuId, skuId)
                .orderByAsc(IvLocationPo::getLocationId));
    }

    /**
     * 该 SKU 的库位实物合计（用于不变量 2 的比对）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param skuId       商品
     * @return 实物合计（无记录返回 0）
     */
    @Select("""
            SELECT IFNULL(SUM(physical_qty), 0) FROM iv_location
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId} AND sku_id = #{skuId} AND deleted = 0
            """)
    BigDecimal sumPhysicalBySku(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                                @Param("skuId") long skuId);

    /**
     * 派发分配：库位"已分配待拣"增加（SKU 三态不变）。
     *
     * @param id          雪花 ID（首次建账用）
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 该库位实物不足，无法分配）
     */
    @Update("""
            INSERT INTO iv_location (id, tenant_id, warehouse_id, location_id, merchant_id, sku_id,
                                     batch_no, physical_qty, allocated_qty, version, deleted)
            VALUES (#{id}, #{tenantId}, #{warehouseId}, #{locationId}, #{merchantId}, #{skuId}, '', 0, #{qty}, 0, 0)
            ON DUPLICATE KEY UPDATE
                allocated_qty = IF(physical_qty >= allocated_qty + #{qty},
                                   allocated_qty + #{qty}, allocated_qty),
                version = version + 1
            """)
    int allocate(@Param("id") long id, @Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                 @Param("locationId") long locationId, @Param("merchantId") long merchantId,
                 @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 拣货：释放该库位"已分配待拣"（SKU 三态不变，{@code 01} 状态机第 3 行）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 可拣量不足）
     */
    @Update("""
            UPDATE iv_location
               SET allocated_qty = allocated_qty - #{qty}, version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND location_id = #{locationId} AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND batch_no = '' AND deleted = 0 AND allocated_qty >= #{qty}
            """)
    int pick(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
             @Param("locationId") long locationId, @Param("merchantId") long merchantId,
             @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 取消分配：把该库位"已分配待拣"的量归位（取消已派发未拣货的单据用，{@code 01} §2.6）。
     *
     * <p>SQL 与 {@link #pick} 相同（都只减 {@code allocated_qty}），但**流水类型不同**
     * （{@code UNALLOCATE} vs {@code PICK}）——业务含义相反的动作共用一种流水会让复盘无法解释。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 该库位分配量不足，数据不一致需排查）
     */
    @Update("""
            UPDATE iv_location
               SET allocated_qty = allocated_qty - #{qty}, version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND location_id = #{locationId} AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND batch_no = '' AND deleted = 0 AND allocated_qty >= #{qty}
            """)
    int unallocate(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                   @Param("locationId") long locationId, @Param("merchantId") long merchantId,
                   @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 上架到库位：库位实物增加（首次建账）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数
     */
    @Update("""
            INSERT INTO iv_location (id, tenant_id, warehouse_id, location_id, merchant_id, sku_id,
                                     batch_no, physical_qty, allocated_qty, version, deleted)
            VALUES (#{id}, #{tenantId}, #{warehouseId}, #{locationId}, #{merchantId}, #{skuId}, '', #{qty}, 0, 0, 0)
            ON DUPLICATE KEY UPDATE
                physical_qty = physical_qty + VALUES(physical_qty),
                version = version + 1
            """)
    int receiveToLocation(@Param("id") long id, @Param("tenantId") long tenantId,
                          @Param("warehouseId") long warehouseId, @Param("locationId") long locationId,
                          @Param("merchantId") long merchantId, @Param("skuId") long skuId,
                          @Param("qty") BigDecimal qty);

    /**
     * 出库时扣减库位实物（保证 {@code 01} §4 不变量 2 在任意时刻成立）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 库位实物不足）
     */
    @Update("""
            UPDATE iv_location
               SET physical_qty = physical_qty - #{qty}, version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND location_id = #{locationId} AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND batch_no = '' AND deleted = 0 AND physical_qty >= #{qty}
            """)
    int shipFromLocation(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                         @Param("locationId") long locationId, @Param("merchantId") long merchantId,
                         @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 移库：源库位实物减少。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  源库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 源库位实物不足）
     */
    @Update("""
            UPDATE iv_location
               SET physical_qty = physical_qty - #{qty}, version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND location_id = #{locationId} AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND batch_no = '' AND deleted = 0 AND physical_qty >= #{qty}
            """)
    int moveOut(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                @Param("locationId") long locationId, @Param("merchantId") long merchantId,
                @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 移库：目标库位实物增加（首次建账）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  目标库位
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数
     */
    @Update("""
            INSERT INTO iv_location (id, tenant_id, warehouse_id, location_id, merchant_id, sku_id,
                                     batch_no, physical_qty, allocated_qty, version, deleted)
            VALUES (#{id}, #{tenantId}, #{warehouseId}, #{locationId}, #{merchantId}, #{skuId}, '', #{qty}, 0, 0, 0)
            ON DUPLICATE KEY UPDATE
                physical_qty = physical_qty + VALUES(physical_qty),
                version = version + 1
            """)
    int moveIn(@Param("id") long id, @Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
               @Param("locationId") long locationId, @Param("merchantId") long merchantId,
               @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
    /**
     * 建盘点单时取账面快照（该仓库/库区下**有实物**的库位账行）。
     *
     * <p>为什么只取 {@code physical_qty > 0}：盘点是为核对"实际有多少"，
     * 账面为 0 的库位行纳入会让盘点单被无用明细淹没（库位数 × SKU 数是乘法级膨胀）。
     * 现场若在空库位发现货（盘盈），通过"手工加明细"处理——这条路径刻意留成人工动作：
     * 凭空出现的货值得多问一句。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param zoneId      库区（可空 = 全仓）
     * @return 库位账列表（按库位、商品排序，结果稳定）
     */
    default List<IvLocationPo> selectStockForStocktake(long tenantId, long warehouseId, Long zoneId) {
        // 1. 按仓库取有实物的库位账
        List<IvLocationPo> list = selectList(Wrappers.<IvLocationPo>lambdaQuery()
                .eq(IvLocationPo::getTenantId, tenantId)
                .eq(IvLocationPo::getWarehouseId, warehouseId)
                .gt(IvLocationPo::getPhysicalQty, BigDecimal.ZERO)
                .orderByAsc(IvLocationPo::getLocationId, IvLocationPo::getSkuId));
        // 2. 指定库区时按主数据过滤（库位归属在 md 域；跨域只读，理由见 STANDARDS-MAPPING §八）
        if (zoneId == null) {
            return list;
        }
        List<Long> locationIds = locationIdsOfZone(tenantId, warehouseId, zoneId);
        List<IvLocationPo> filtered = new java.util.ArrayList<>(list.size());
        for (IvLocationPo po : list) {
            if (locationIds.contains(po.getLocationId())) {
                filtered.add(po);
            }
        }
        return filtered;
    }

    /**
     * 库区下的库位 ID（跨域只读，仅用于圈定盘点范围）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param zoneId      库区
     * @return 库位 ID 列表
     */
    @Select("SELECT id FROM md_location WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId} "
            + "AND zone_id = #{zoneId} AND deleted = 0")
    List<Long> locationIdsOfZone(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                                 @Param("zoneId") long zoneId);

    /**
     * 不变量 2 违反行数（{@code 01} §4，日终校验用）。
     *
     * @return 违反行数
     */
    @Select("""
            SELECT COUNT(*) FROM (
                SELECT s.id, s.physical_qty, IFNULL(SUM(l.physical_qty), 0) AS loc_qty
                  FROM iv_sku s
                  LEFT JOIN iv_location l
                    ON l.tenant_id = s.tenant_id AND l.warehouse_id = s.warehouse_id
                   AND l.sku_id = s.sku_id AND l.deleted = 0
                 WHERE s.deleted = 0
                 GROUP BY s.id, s.physical_qty
                HAVING s.physical_qty <> loc_qty
            ) t
            """)
    long countLocationSumMismatch();

    /**
     * 不变量 2b 违反行数：库位实物或分配量为负。
     *
     * @return 违反行数
     */
    @Select("""
            SELECT COUNT(*) FROM iv_location
             WHERE deleted = 0 AND (physical_qty < 0 OR allocated_qty < 0)
            """)
    long countNegativeViolation();
}
