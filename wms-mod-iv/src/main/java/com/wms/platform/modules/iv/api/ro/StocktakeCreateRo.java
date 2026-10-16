package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建盘点单入参（03 API-IV-03 {@code POST /stocktakes}）。
 *
 * @param warehouseId 仓库
 * @param zoneId      库区（按库区盘点；为空表示全仓）
 * @param scopeType   范围类型：全仓/库区/库位
 * @param remark      备注
 */
public record StocktakeCreateRo(

        /** 仓库：必填。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 库区（可空 = 全仓）。 */
        Long zoneId,

        /** 范围类型（默认库区）。 */
        String scopeType,

        /** 备注。 */
        @Size(max = 255, message = "备注不得超过 255 字符")
        String remark) {
}
