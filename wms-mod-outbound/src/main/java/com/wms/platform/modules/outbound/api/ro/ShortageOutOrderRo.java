package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 缺货上报（{@code 03} API-OUT-04，状态机第 4 行）。
 *
 * <p>副作用是"差额释放"：{@code reserved −= 缺货量}，因此**缺货量必须由现场明确给出**，
 * 而不是让服务端用"应发 − 已报拣货"倒推——倒推在分多次上报时会重复释放（第一次报 2 件、
 * 第二次再报 3 件，倒推会按累计差额再释放一遍）。
 *
 * @param items  逐行缺货数量
 * @param reason 缺货原因（可空，写入状态流水便于复盘）
 */
public record ShortageOutOrderRo(@NotEmpty(message = "缺货明细不能为空") @Valid List<ItemRo> items,
                                 @Size(max = 255, message = "缺货原因不得超过 255 字符") String reason) {

    /**
     * 缺货明细。
     *
     * @param lineNo      明细行号
     * @param shortageQty 本次缺货数量（正数）
     */
    public record ItemRo(@NotNull(message = "明细行号不能为空") Integer lineNo,
                         @NotNull(message = "缺货数量不能为空")
                         @DecimalMin(value = "0.0001", message = "缺货数量必须为正数") BigDecimal shortageQty) {
    }
}
