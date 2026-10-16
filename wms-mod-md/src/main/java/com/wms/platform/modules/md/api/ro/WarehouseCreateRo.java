package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * 仓库新增入参（03 API-MD-02）。
 *
 * @param warehouseCode 仓库编码（租户内唯一）
 * @param name          名称
 * @param province      省
 * @param city          市
 * @param address       地址
 * @param cutoffTime    截单时间（按仓可配；不传默认 18:00）
 */
public record WarehouseCreateRo(

        /** 仓库编码：必填。 */
        @NotBlank(message = "仓库编码不能为空")
        @Size(max = 32, message = "仓库编码不得超过 32 字符")
        String warehouseCode,

        /** 名称：必填。 */
        @NotBlank(message = "仓库名称不能为空")
        @Size(max = 100, message = "仓库名称不得超过 100 字符")
        String name,

        /** 省。 */
        @Size(max = 32, message = "省不得超过 32 字符")
        String province,

        /** 市。 */
        @Size(max = 32, message = "市不得超过 32 字符")
        String city,

        /** 地址。 */
        @Size(max = 255, message = "地址不得超过 255 字符")
        String address,

        /** 截单时间（HH:mm 或 HH:mm:ss，由 JsonTimeConfig 宽容解析）。 */
        LocalTime cutoffTime) {
}
