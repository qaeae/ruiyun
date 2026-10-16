package com.wms.platform.modules.md.api.vo;

import java.math.BigDecimal;

/**
 * 数量换算结果（计费与库存上架共用）。
 *
 * @param skuId      商品 ID
 * @param fromUnit   源单位
 * @param toUnit     目标单位
 * @param sourceQty  原数量
 * @param resultQty  换算结果
 */
public record QtyConvertVo(Long skuId, String fromUnit, String toUnit, BigDecimal sourceQty,
                           BigDecimal resultQty) {
}
