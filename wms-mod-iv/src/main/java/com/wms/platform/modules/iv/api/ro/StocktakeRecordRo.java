package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 录入实盘入参（03 API-IV-03 {@code POST /stocktakes/{no}/items}）。
 *
 * <p>批量录入：现场是按库位逐条盘、逐条提交的，一次请求带多条可以减少往返
 * （PDA/仓库 PC 网络不稳，能少一次交互就少一次失败机会）。
 *
 * @param items 明细列表
 */
public record StocktakeRecordRo(

        /** 明细：至少一条。 */
        @NotEmpty(message = "录入明细不能为空")
        @Valid
        List<Item> items) {

    /**
     * 单条录入。
     *
     * @param skuId      商品 ID
     * @param locationId 库位 ID
     * @param actualQty  实盘数（**必填**：0 与"没盘"必须可区分，故不允许缺省）
     * @param remark     备注
     */
    public record Item(

            /** 商品：必填。 */
            @NotNull(message = "商品不能为空")
            Long skuId,

            /** 库位：必填（盘点按库位点数）。 */
            @NotNull(message = "库位不能为空")
            Long locationId,

            /** 实盘数：必填且非负。 */
            @NotNull(message = "实盘数不能为空（盘到 0 也要明确填 0）")
            @DecimalMin(value = "0", message = "实盘数不能为负")
            BigDecimal actualQty,

            /** 备注。 */
            @Size(max = 255, message = "备注不得超过 255 字符")
            String remark) {
    }
}
