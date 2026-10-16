package com.wms.platform.modules.iv.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 驳回盘点的入参（03 API-IV-03）。
 *
 * @param reason 驳回原因（必填：否则现场不知道要改什么，只能重盘一遍）
 */
public record StocktakeRejectRo(

        /** 驳回原因：必填。 */
        @NotBlank(message = "驳回原因不能为空")
        @Size(max = 255, message = "驳回原因不得超过 255 字符")
        String reason) {
}
