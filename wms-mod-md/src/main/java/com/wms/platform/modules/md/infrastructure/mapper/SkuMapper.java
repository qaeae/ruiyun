package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.SkuPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 商品主数据 Mapper（表 {@code md_sku}）。
 *
 * <p>规范落地（B1）：Wrapper 只出现在本接口的 {@code default} 方法里。
 */
@Mapper
public interface SkuMapper extends BaseMapper<SkuPo> {

    /**
     * 按条码查商品（多租户 + 商家内唯一）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param skuCode    商品条码
     * @return 商品，不存在返回 {@code null}
     */
    default SkuPo selectByCode(long tenantId, long merchantId, String skuCode) {
        return selectOne(Wrappers.<SkuPo>lambdaQuery()
                .eq(SkuPo::getTenantId, tenantId)
                .eq(SkuPo::getMerchantId, merchantId)
                .eq(SkuPo::getSkuCode, skuCode)
                .last("limit 1"));
    }

    /**
     * 按商品 ID 查（校验归属与状态用）。
     *
     * @param tenantId 租户
     * @param skuId    商品 ID
     * @return 商品，不存在返回 {@code null}
     */
    default SkuPo selectByIdSafe(long tenantId, long skuId) {
        return selectOne(Wrappers.<SkuPo>lambdaQuery()
                .eq(SkuPo::getTenantId, tenantId)
                .eq(SkuPo::getId, skuId)
                .last("limit 1"));
    }

    /**
     * 商家下的商品列表（主数据页面用）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 商品列表
     */
    default List<SkuPo> selectByMerchant(long tenantId, long merchantId) {
        return selectList(Wrappers.<SkuPo>lambdaQuery()
                .eq(SkuPo::getTenantId, tenantId)
                .eq(SkuPo::getMerchantId, merchantId)
                .orderByAsc(SkuPo::getSkuCode));
    }
    /**
     * 改状态（Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId  租户
     * @param id        商品 ID
     * @param status    目标状态
     * @param updatedBy 操作人
     * @return 影响行数
     */
    default int updateStatus(long tenantId, long id, String status, Long updatedBy) {
        return update(null, Wrappers.<SkuPo>lambdaUpdate()
                .eq(SkuPo::getTenantId, tenantId)
                .eq(SkuPo::getId, id)
                .set(SkuPo::getStatus, status)
                .set(SkuPo::getUpdatedBy, updatedBy));
    }
}
