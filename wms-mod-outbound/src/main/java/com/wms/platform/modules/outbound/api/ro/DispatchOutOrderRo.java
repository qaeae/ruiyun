package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 派发拣货任务（{@code 03} API-OUT-02，状态机第 2 行）。
 *
 * <p>派发要**指定拣货库位**：库存侧的动作是"库位 {@code allocated_qty} 增加"，
 * 而"从哪个库位拣"是现场决定（也许同一 SKU 分散在多个库位）。因此由库管逐行给库位编码，
 * 服务端解析为库位 ID 并校验可拣（{@code 50003}）。
 *
 * @param items 逐行拣货库位（行号对应建单时的明细顺序）
 */
public record DispatchOutOrderRo(@NotEmpty(message = "拣货明细不能为空") @Valid List<ItemRo> items) {

    /**
     * 派发明细。
     *
     * @param lineNo       明细行号
     * @param locationCode 拣货库位编码（须存在、启用且可拣）
     */
    public record ItemRo(@NotNull(message = "明细行号不能为空") Integer lineNo,
                         @NotBlank(message = "拣货库位不能为空") String locationCode) {
    }
}
