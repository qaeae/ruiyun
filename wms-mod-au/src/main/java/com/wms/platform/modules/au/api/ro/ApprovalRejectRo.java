package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 驳回复核单入参（03 API-AUTH-05）。
 *
 * @param reason 驳回原因（必填：否则发起人不知道该改什么）
 */
public record ApprovalRejectRo(

        /** 驳回原因：必填。 */
        @NotBlank(message = "驳回原因不能为空")
        @Size(max = 255, message = "驳回原因不得超过 255 字符")
        String reason) {
}
