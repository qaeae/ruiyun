package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * SKU 新增入参（03 API-MD-03 {@code POST /master/skus}）。
 *
 * @param merchantId 所属商家
 * @param skuCode    商品条码（现场扫码值，商家内唯一）
 * @param name       名称
 * @param category   品类
 * @param unit       基本单位：件/箱/托
 * @param weightKg   单件重量（运费预估基准）
 * @param volumeRatio 体积（抛比基准）
 * @param isForbidden 是否禁入品类（**仅平台运营可置位**，03 备注）
 * @param forbiddenReason 禁入原因（置位时必填）
 */
public record SkuCreateRo(

        /** 商家：必填。 */
        @NotNull(message = "商家不能为空")
        Long merchantId,

        /** 商品条码：必填。 */
        @NotBlank(message = "商品条码不能为空")
        @Size(max = 64, message = "商品条码不得超过 64 字符")
        String skuCode,

        /** 名称：必填。 */
        @NotBlank(message = "商品名称不能为空")
        @Size(max = 128, message = "商品名称不得超过 128 字符")
        String name,

        /** 品类。 */
        @Size(max = 48, message = "品类不得超过 48 字符")
        String category,

        /** 基本单位：件/箱/托。 */
        @NotBlank(message = "基本单位不能为空")
        String unit,

        /** 单件重量（kg）。 */
        @DecimalMin(value = "0", message = "重量不能为负")
        BigDecimal weightKg,

        /** 体积（或抛比基准）。 */
        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volumeRatio,

        /** 是否禁入品类。 */
        Boolean isForbidden,

        /** 禁入原因。 */
        @Size(max = 255, message = "禁入原因不得超过 255 字符")
        String forbiddenReason) {
}
