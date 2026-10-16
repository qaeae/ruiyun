package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备编辑入参。
 *
 * <p><b>刻意不含 {@code deviceCode}</b>：设备编号在设备贴码时就固定了，允许改会导致
 * "已经贴在机器上的码对不上台账"，现场只能重新贴码。要改编号应停用旧档 + 新建。
 *
 * @param deviceCode   设备编号（定位用，不可改）
 * @param name         设备名称
 * @param warehouseId  归属仓库
 * @param locationDesc 摆放位置
 * @param remark       备注
 */
public record DeviceEditRo(

        /** 设备编号：必填（作为定位键）。 */
        @NotBlank(message = "设备编号不能为空")
        String deviceCode,

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
