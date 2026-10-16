package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 移库入参（03 API-IV-04 {@code POST /inventory/moves}）。
 *
 * @param warehouseId    仓库 ID
 * @param merchantId     商家 ID
 * @param skuId          商品内部 ID
 * @param qty            数量
 * @param fromLocationId 源库位
 * @param toLocationId   目标库位
 * @param bizNo          业务单号
 */
public record MoveRo(

        /** 仓库：必填。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 商家：必填。 */
        @NotNull(message = "商家不能为空")
        Long merchantId,

        /** 商品 ID：必填。 */
        @NotNull(message = "商品不能为空")
        Long skuId,

        /** 数量：必填且为正。 */
        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.0001", message = "数量必须大于 0")
        BigDecimal qty,

        /** 源库位：必填。 */
        @NotNull(message = "源库位不能为空")
        Long fromLocationId,

        /** 目标库位：必填。 */
        @NotNull(message = "目标库位不能为空")
        Long toLocationId,

        /** 业务单号：必填。 */
        @NotBlank(message = "业务单号不能为空")
        String bizNo) {
}
