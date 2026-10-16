package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 收货 / 出库 / 调整入参（三者都需要库位，故共用）。
 *
 * <p><b>为什么出库也要库位</b>：{@code 01} §4 不变量 2 要求"库位明细合计 = SKU 聚合实物"。
 * 若出库只减 SKU 级实物，库位账就多出来了，日终校验必然报差异。
 * 调用方（出库流程）从派发分配记录里知道库位，因此带库位不是额外负担。
 *
 * @param warehouseId 仓库 ID
 * @param merchantId  商家 ID
 * @param skuId       商品内部 ID
 * @param locationId  库位内部 ID（由主数据解析）
 * @param qty         数量（调整时为带符号差异）
 * @param bizType     业务类型
 * @param bizId       业务单据 ID
 * @param bizNo       业务单号
 * @param reason      原因（调整/异常必填，便于审计）
 */
public record LocatedStockOpRo(

        /** 仓库：必填。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 商家：必填。 */
        @NotNull(message = "商家不能为空")
        Long merchantId,

        /** 商品 ID：必填。 */
        @NotNull(message = "商品不能为空")
        Long skuId,

        /** 库位 ID：必填（出库/收货/调整都要，见类注释）。 */
        @NotNull(message = "库位不能为空")
        Long locationId,

        /** 数量：必填；收货/出库为正，调整可正可负。 */
        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "-999999999.0000", message = "数量超出范围")
        BigDecimal qty,

        /** 业务类型：必填。 */
        @NotBlank(message = "业务类型不能为空")
        String bizType,

        /** 业务单据 ID：可空。 */
        Long bizId,

        /** 业务单号：必填。 */
        @NotBlank(message = "业务单号不能为空")
        String bizNo,

        /** 原因：调整必填。 */
        String reason) {
}
