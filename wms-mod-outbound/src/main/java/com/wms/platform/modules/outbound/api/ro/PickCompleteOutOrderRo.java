package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 拣货完成上报（{@code 03} API-OUT-03，状态机第 3 行）。
 *
 * <p>口径：**实拣数量必须等于应发数量**才允许"拣货完成"（否则应走缺货上报 API-OUT-04）。
 * 把这个判断放在服务端而不是信任调用方：现场"差一两件先报完成"很常见，
 * 若放行会让库存与订单永久对不上（{@code 01} §2.1 第 3 行的前置条件就是"实拣 = 订单数量"）。
 *
 * @param items 逐行实拣数量
 */
public record PickCompleteOutOrderRo(@NotEmpty(message = "拣货明细不能为空") @Valid List<ItemRo> items) {

    /**
     * 拣货明细。
     *
     * @param lineNo    明细行号
     * @param pickedQty 实拣数量（必须等于应发数量）
     */
    public record ItemRo(@NotNull(message = "明细行号不能为空") Integer lineNo,
                         @NotNull(message = "实拣数量不能为空")
                         @DecimalMin(value = "0.0001", message = "实拣数量必须为正数") BigDecimal pickedQty) {
    }
}
