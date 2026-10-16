package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 预占 / 释放入参（03 API-IV-05 {@code /inventory/reservations}、{@code /releases}）。
 *
 * @param warehouseId 仓库 ID
 * @param merchantId  商家 ID
 * @param skuId       商品内部 ID（由主数据解析后传入，接口层不再做条码解析）
 * @param qty         数量（正数）
 * @param bizType     业务类型：DO（出库单）等
 * @param bizId       业务单据 ID
 * @param bizNo       业务单号
 */
public record StockOpRo(

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

        /** 业务类型：必填。 */
        @NotNull(message = "业务类型不能为空")
        String bizType,

        /** 业务单据 ID：可为空（如手工预占）。 */
        Long bizId,

        /** 业务单号：必填（追单依据）。 */
        @NotNull(message = "业务单号不能为空")
        String bizNo) {
}
