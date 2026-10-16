package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 部分发货（{@code 03} API-OUT-12，状态机"部分发货"分支）。
 *
 * <p>语义：把本次实际发走的数量从原单"结清"，**余量生成一张新的出库单**（待接单），
 * 原单转「已出库」。为什么不让原单停留在半成品状态：
 * <ul>
 *   <li>{@code 01} §3.2 的 {@code status} 只有 9 个取值，没有"部分出库"半终态；</li>
 *   <li>余量新单走完整的"派发→拣货→称重→打包→装车"流程，复核与交接的卡点不会被跳过。</li>
 * </ul>
 *
 * @param items  逐行本次实际发货数量（必须 ≤ 应发数量）
 * @param reason 拆单原因（可空，写入两张单的备注便于串单）
 */
public record PartialShipOutOrderRo(@NotEmpty(message = "发货明细不能为空") @Valid List<ItemRo> items,
                                    @Size(max = 255, message = "拆单原因不得超过 255 字符") String reason) {

    /**
     * 部分发货明细。
     *
     * @param lineNo   明细行号
     * @param shipQty  本次发货数量（正数，且不超过该行应发数量）
     */
    public record ItemRo(@NotNull(message = "明细行号不能为空") Integer lineNo,
                         @NotNull(message = "发货数量不能为空")
                         @DecimalMin(value = "0.0001", message = "发货数量必须为正数") BigDecimal shipQty) {
    }
}
