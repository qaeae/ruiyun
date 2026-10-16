package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参（03 API-AUTH-01）。
 *
 * @param username 登录名
 * @param password 明文口令
 */
public record LoginRo(

        /** 登录名。 */
        @NotBlank(message = "登录名不能为空")
        String username,

        /** 口令。 */
        @NotBlank(message = "口令不能为空")
        String password) {
}
