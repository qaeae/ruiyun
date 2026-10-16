package com.wms.platform.modules.iv.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.iv.infrastructure.po.IvSkuPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 级三态账 Mapper（表 {@code iv_sku}）。
 *
 * <p><b>所有数量变更一律条件更新（CAS），且条件与动作的语义严格对齐</b>——
 * 这是 PoC-4 得出的第 1、2 条实现约束（{@code 07-PoC-P4执行报告.md} §五）：
 * <ol>
 *   <li>禁止"先查后改"：查与改之间的窗口足以让并发请求超卖；</li>
 *   <li>影响行数 = 0 → 交调用方按业务语义处理（冲突/不足），**不重试不猜测**；</li>
 *   <li>每个 CAS 的 {@code WHERE} 都显式写出"扣得动的条件"，让数据库承担最终判定；</li>
 *   <li>数据库 CHECK（{@code ck_iv_sku_balance}/{@code ck_iv_sku_nonneg}）是最后兜底。</li>
 * </ol>
 */
@Mapper
public interface IvSkuMapper extends BaseMapper<IvSkuPo> {

    /**
     * 查三态账（唯一键 {@code uk_iv_sku}）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @return 账，无记录返回 {@code null}
     */
    default IvSkuPo selectByKey(long tenantId, long warehouseId, long merchantId, long skuId) {
        return selectOne(Wrappers.<IvSkuPo>lambdaQuery()
                .eq(IvSkuPo::getTenantId, tenantId)
                .eq(IvSkuPo::getWarehouseId, warehouseId)
                .eq(IvSkuPo::getMerchantId, merchantId)
                .eq(IvSkuPo::getSkuId, skuId)
                .last("limit 1"));
    }

    /**
     * 按仓库查三态账列表（库存查询接口 API-IV-01）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库（可空 = 不限）
     * @param merchantId  商家（可空 = 不限）
     * @return 账列表
     */
    default List<IvSkuPo> selectList(Long tenantId, Long warehouseId, Long merchantId) {
        return selectList(Wrappers.<IvSkuPo>lambdaQuery()
                .eq(tenantId != null, IvSkuPo::getTenantId, tenantId)
                .eq(warehouseId != null, IvSkuPo::getWarehouseId, warehouseId)
                .eq(merchantId != null, IvSkuPo::getMerchantId, merchantId)
                .orderByAsc(IvSkuPo::getWarehouseId, IvSkuPo::getSkuId));
    }

    /**
     * 预占：可用 → 预占。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 可用不足）
     */
    @Update("""
            UPDATE iv_sku
               SET available_qty = available_qty - #{qty},
                   reserved_qty = reserved_qty + #{qty},
                   version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND deleted = 0 AND available_qty >= #{qty}
            """)
    int reserve(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                @Param("merchantId") long merchantId, @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 释放预占：预占 → 可用。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 预占不足）
     */
    @Update("""
            UPDATE iv_sku
               SET available_qty = available_qty + #{qty},
                   reserved_qty = reserved_qty - #{qty},
                   version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND deleted = 0 AND reserved_qty >= #{qty}
            """)
    int release(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                @Param("merchantId") long merchantId, @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 出库：实物与预占同减（不变量 {@code physical = available + reserved} 保持）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数（0 = 实物或预占不足）
     */
    @Update("""
            UPDATE iv_sku
               SET physical_qty = physical_qty - #{qty},
                   reserved_qty = reserved_qty - #{qty},
                   version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND deleted = 0 AND physical_qty >= #{qty} AND reserved_qty >= #{qty}
            """)
    int ship(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
             @Param("merchantId") long merchantId, @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 收货（上架/退货入库）：实物与可用同增；首次收货自动建账。
     *
     * @param id          雪花 ID（插入用）
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @return 影响行数
     */
    @Update("""
            INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id,
                                physical_qty, available_qty, reserved_qty, version, deleted)
            VALUES (#{id}, #{tenantId}, #{warehouseId}, #{merchantId}, #{skuId},
                    #{qty}, #{qty}, 0, 0, 0)
            ON DUPLICATE KEY UPDATE
                physical_qty = physical_qty + VALUES(physical_qty),
                available_qty = available_qty + VALUES(available_qty),
                version = version + 1
            """)
    int receive(@Param("id") long id, @Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
                @Param("merchantId") long merchantId, @Param("skuId") long skuId, @Param("qty") BigDecimal qty);

    /**
     * 盘点调整：实物与可用同加减（{@code delta} 带符号：盘盈正、盘亏负）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param delta       带符号差异
     * @return 影响行数（0 = 调整后会为负）
     */
    @Update("""
            UPDATE iv_sku
               SET physical_qty = physical_qty + #{delta},
                   available_qty = available_qty + #{delta},
                   version = version + 1
             WHERE tenant_id = #{tenantId} AND warehouse_id = #{warehouseId}
               AND merchant_id = #{merchantId} AND sku_id = #{skuId}
               AND deleted = 0
               AND physical_qty + #{delta} >= 0
               AND available_qty + #{delta} >= 0
            """)
    int adjust(@Param("tenantId") long tenantId, @Param("warehouseId") long warehouseId,
               @Param("merchantId") long merchantId, @Param("skuId") long skuId, @Param("delta") BigDecimal delta);

    /**
     * 三态不变量违反行数（{@code 01} §4 不变量 1，用于日终校验接口）。
     *
     * @return 违反行数
     */
    @Select("SELECT COUNT(*) FROM iv_sku WHERE deleted = 0 AND physical_qty <> available_qty + reserved_qty")
    long countBalanceViolation();

    /**
     * 不变量 1b 违反行数：三态为负。
     *
     * @return 违反行数
     */
    @Select("""
            SELECT COUNT(*) FROM iv_sku
             WHERE deleted = 0 AND (physical_qty < 0 OR available_qty < 0 OR reserved_qty < 0)
            """)
    long countNegativeViolation();
}
