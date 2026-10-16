package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 派发分配 / 拣货入参（库位级动作，SKU 三态不变）。
 *
 * @param warehouseId 仓库 ID
 * @param merchantId  商家 ID
 * @param skuId       商品内部 ID
 * @param locationId  库位内部 ID
 * @param qty         数量
 * @param bizNo       业务单号（DO 号 / 任务号）
 */
public record AllocateRo(

        /** 仓库：必填。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 商家：必填。 */
        @NotNull(message = "商家不能为空")
        Long merchantId,

        /** 商品 ID：必填。 */
        @NotNull(message = "商品不能为空")
        Long skuId,

        /** 库位 ID：必填。 */
        @NotNull(message = "库位不能为空")
        Long locationId,

        /** 数量：必填且为正。 */
        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.0001", message = "数量必须大于 0")
        BigDecimal qty,

        /** 业务单号：必填。 */
        @NotNull(message = "业务单号不能为空")
        String bizNo) {
}
