package com.wms.platform.modules.fns.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 运费试算请求（`03` API-CS-04）。
 *
 * @param regionCode      目的地区域码（必填：价目表按区域分档）
 * @param actualWeightKg  实重（kg）
 * @param volumeCm3       体积（cm³）；不传则不比较体积重
 * @param cardCode        价目表编码；不传用默认价目表
 * @param bufferRatio     缓冲系数覆盖；不传用价目表自身的（预估价 = 运费 × (1+系数)）
 */
public record FreightEstimateRo(

        /** 目的地区域码。 */
        @NotBlank(message = "目的地区域码不能为空")
        String regionCode,

        /** 实重（kg）。 */
        @NotNull(message = "实重不能为空")
        @DecimalMin(value = "0", message = "实重不能为负")
        BigDecimal actualWeightKg,

        /** 体积（cm³）。 */
        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volumeCm3,

        /** 价目表编码。 */
        String cardCode,

        /** 缓冲系数覆盖。 */
        @DecimalMin(value = "0", message = "缓冲系数不能为负")
        BigDecimal bufferRatio) {
}
