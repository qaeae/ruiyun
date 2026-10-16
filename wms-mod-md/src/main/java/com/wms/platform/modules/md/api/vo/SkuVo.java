package com.wms.platform.modules.md.api.vo;

import java.math.BigDecimal;

/**
 * SKU 视图（03 API-MD-03/07）。
 *
 * @param id             商品 ID
 * @param merchantId     所属商家
 * @param skuCode        商品条码
 * @param name           名称
 * @param category       品类
 * @param unit           基本单位
 * @param weightKg       单件重量
 * @param volumeRatio    体积/抛比
 * @param forbidden      是否禁入品类
 * @param forbiddenReason 禁入原因
 * @param status         状态
 */
public record SkuVo(Long id, Long merchantId, String skuCode, String name, String category, String unit,
                    BigDecimal weightKg, BigDecimal volumeRatio, boolean forbidden, String forbiddenReason,
                    String status) {
}
