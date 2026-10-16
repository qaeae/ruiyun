package com.wms.platform.modules.fns.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工处理差异条目入参（03 API-FNS-02）。
 *
 * @param liability 责任方：PLATFORM / MERCHANT / LOGISTICS
 * @param remark    处理说明（必填：追责与审计依据）
 */
public record ReconcileHandleRo(

        /** 责任方：必填。 */
        @NotNull(message = "责任方不能为空")
        String liability,

        /** 说明：必填，进入审计与追责依据。 */
        @NotBlank(message = "处理说明不能为空")
        @Size(max = 255, message = "处理说明不得超过 255 字符")
        String remark) {
}
