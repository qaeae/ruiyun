package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 箱规换算新增入参（03 API-MD-04 {@code POST /master/skus/{skuId}/unit-conversions}）。
 *
 * <p>口径：{@code 1 unitFrom = factor unitTo}。
 *
 * @param unitFrom 源单位：件/箱/托
 * @param unitTo   目标单位：件/箱/托（不得与源单位相同）
 * @param factor   系数（必须 &gt; 0）
 */
public record UnitConvCreateRo(

        /** 源单位：必填。 */
        @NotBlank(message = "源单位不能为空")
        String unitFrom,

        /** 目标单位：必填。 */
        @NotBlank(message = "目标单位不能为空")
        String unitTo,

        /** 系数：必填且大于 0。 */
        @NotNull(message = "换算系数不能为空")
        BigDecimal factor) {
}
