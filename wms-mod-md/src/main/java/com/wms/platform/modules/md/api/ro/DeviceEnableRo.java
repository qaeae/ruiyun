package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotNull;

/**
 * 设备启停入参。
 *
 * @param deviceCode 设备编号
 * @param enabled    目标状态：{@code true} 启用、{@code false} 停用
 */
public record DeviceEnableRo(

        /** 设备编号：必填。 */
        @NotNull(message = "设备编号不能为空")
        String deviceCode,

        /** 目标状态：必填。 */
        @NotNull(message = "启用状态不能为空")
        Boolean enabled) {
}
