package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备登记入参（03 API-MD-05）。
 *
 * <p>规范落地（B2/B3）：请求入参用 {@code Ro}、落在 {@code ..ro..} 包、
 * 校验用 {@code jakarta.validation} 且提示信息中文。
 *
 * @param deviceCode   设备编号（现场唯一；只允许大写字母/数字/短横线，便于贴码与扫码）
 * @param deviceType   设备类型枚举码：PDA/SCANNER/PRINTER/SCALE/GATE
 * @param name         设备名称（如"复核台1-电子秤"）
 * @param warehouseId  归属仓库（可空 = 平台或跨仓共用）
 * @param locationDesc 摆放位置描述（可空）
 * @param remark       备注（可空）
 */
public record DeviceRegisterRo(

        /** 设备编号：必填，≤64，仅大写字母/数字/短横线。 */
        @NotBlank(message = "设备编号不能为空")
        @Size(max = 64, message = "设备编号长度不得超过 64")
        @Pattern(regexp = "^[A-Z0-9-]+$", message = "设备编号只允许大写字母、数字与短横线")
        String deviceCode,

        /** 设备类型：必填，取值见 DeviceType。 */
        @NotBlank(message = "设备类型不能为空")
        String deviceType,

        /** 设备名称：可空，≤64。 */
        @Size(max = 64, message = "设备名称长度不得超过 64")
        String name,

        /** 归属仓库 ID：可空。 */
        Long warehouseId,

        /** 摆放位置：可空，≤128。 */
        @Size(max = 128, message = "位置描述长度不得超过 128")
        String locationDesc,

        /** 备注：可空，≤255。 */
        @Size(max = 255, message = "备注长度不得超过 255")
        String remark) {
}
