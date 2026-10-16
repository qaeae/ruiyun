package com.wms.platform.modules.md.api.vo;

import java.math.BigDecimal;

/**
 * 箱规换算视图（03 API-MD-04）。
 *
 * @param skuId    商品 ID
 * @param unitFrom 源单位
 * @param unitTo   目标单位
 * @param factor   系数（{@code 1 unitFrom = factor unitTo}）
 */
public record UnitConvVo(Long skuId, String unitFrom, String unitTo, BigDecimal factor) {
}
