package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.SkuUnitConvPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 箱规换算 Mapper（表 {@code md_sku_unit_conv}）。
 */
@Mapper
public interface SkuUnitConvMapper extends BaseMapper<SkuUnitConvPo> {

    /**
     * 查某 SKU 的全部换算配置（换算推导的输入）。
     *
     * @param tenantId 租户
     * @param skuId    商品 ID
     * @return 列表（按单位排序，结果稳定）
     */
    default List<SkuUnitConvPo> selectBySku(long tenantId, long skuId) {
        return selectList(Wrappers.<SkuUnitConvPo>lambdaQuery()
                .eq(SkuUnitConvPo::getTenantId, tenantId)
                .eq(SkuUnitConvPo::getSkuId, skuId)
                .orderByAsc(SkuUnitConvPo::getUnitFrom, SkuUnitConvPo::getUnitTo));
    }

    /**
     * 查单条换算（唯一键判重用）。
     *
     * @param tenantId 租户
     * @param skuId    商品 ID
     * @param unitFrom 源单位
     * @param unitTo   目标单位
     * @return 配置，不存在返回 {@code null}
     */
    default SkuUnitConvPo selectOneConv(long tenantId, long skuId, String unitFrom, String unitTo) {
        return selectOne(Wrappers.<SkuUnitConvPo>lambdaQuery()
                .eq(SkuUnitConvPo::getTenantId, tenantId)
                .eq(SkuUnitConvPo::getSkuId, skuId)
                .eq(SkuUnitConvPo::getUnitFrom, unitFrom)
                .eq(SkuUnitConvPo::getUnitTo, unitTo)
                .last("limit 1"));
    }
}
