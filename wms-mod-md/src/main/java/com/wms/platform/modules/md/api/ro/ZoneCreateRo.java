package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 库区新增入参（03 API-MD-02）。
 *
 * @param warehouseId 所属仓库
 * @param zoneCode    库区编码（仓库内唯一）
 * @param name        名称
 * @param zoneType    类型：存储区/拣货区/复核区/隔离区/退货区
 */
public record ZoneCreateRo(

        /** 仓库：必填（服务端校验存在且启用）。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 库区编码：必填。 */
        @NotBlank(message = "库区编码不能为空")
        @Size(max = 32, message = "库区编码不得超过 32 字符")
        String zoneCode,

        /** 名称。 */
        @Size(max = 64, message = "库区名称不得超过 64 字符")
        String name,

        /** 类型：必填（隔离区标记由类型推导）。 */
        @NotBlank(message = "库区类型不能为空")
        String zoneType) {
}
