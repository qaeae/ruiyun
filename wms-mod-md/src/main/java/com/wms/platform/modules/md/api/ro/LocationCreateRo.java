package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 库位新增入参（03 API-MD-02）。
 *
 * @param warehouseId  所属仓库
 * @param zoneId       所属库区（必须属于该仓库）
 * @param locationCode 库位编码（现场贴码，仓库内唯一）
 * @param abcClass     ABC 分类（拣货路径用）
 * @param isPickable   是否可拣（存储区大库位通常不可拣）
 * @param quotaUnit    折算托盘位数（超占费用依据）
 */
public record LocationCreateRo(

        /** 仓库：必填。 */
        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        /** 库区：必填（服务端校验归属仓库）。 */
        @NotNull(message = "库区不能为空")
        Long zoneId,

        /** 库位编码：必填。 */
        @NotBlank(message = "库位编码不能为空")
        @Size(max = 64, message = "库位编码不得超过 64 字符")
        String locationCode,

        /** ABC 分类（A/B/C，可空）。 */
        @Size(max = 4, message = "ABC 分类不得超过 4 字符")
        String abcClass,

        /** 是否可拣：默认 true。 */
        Boolean isPickable,

        /** 折算托盘位：默认 1，必须 ≥ 1。 */
        @Min(value = 1, message = "折算托盘位必须大于等于 1")
        Integer quotaUnit) {
}
