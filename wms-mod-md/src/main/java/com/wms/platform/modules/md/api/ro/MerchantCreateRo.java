package com.wms.platform.modules.md.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 商家新增入参（03 API-MD-01 {@code POST /master/merchants}）。
 *
 * @param merchantNo 商家编号（租户内唯一）
 * @param name       商家名称
 * @param contactName 联系人
 * @param contactPhone 联系人手机号（**明文传入，落库前加密 + 脱敏**，01 §7）
 */
public record MerchantCreateRo(

        /** 商家编号：必填。 */
        @NotBlank(message = "商家编号不能为空")
        @Size(max = 32, message = "商家编号不得超过 32 字符")
        String merchantNo,

        /** 名称：必填。 */
        @NotBlank(message = "商家名称不能为空")
        @Size(max = 100, message = "商家名称不得超过 100 字符")
        String name,

        /** 联系人。 */
        @Size(max = 64, message = "联系人不得超过 64 字符")
        String contactName,

        /** 联系人手机号：明文入参，服务端负责加密与脱敏。 */
        @Size(max = 20, message = "手机号不得超过 20 字符")
        String contactPhone) {
}
