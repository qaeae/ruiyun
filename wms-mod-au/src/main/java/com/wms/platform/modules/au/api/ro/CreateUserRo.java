package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增用户入参（03 API-AUTH-02）。
 *
 * @param username  登录名
 * @param password  明文口令（服务端 PBKDF2 哈希后入库，不落明文）
 * @param name      姓名
 * @param phone     手机号（明文入参，服务端加密 + 脱敏）
 * @param adminFlag 是否超管（跨仓全权限；高风险，建议走复核流）
 */
public record CreateUserRo(

        /** 登录名。 */
        @NotBlank(message = "登录名不能为空")
        @Size(max = 64, message = "登录名不得超过 64 字符")
        String username,

        /** 明文口令（长度下限由 PasswordHasher 校验为 8 位）。 */
        @NotBlank(message = "口令不能为空")
        String password,

        /** 姓名。 */
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名不得超过 64 字符")
        String name,

        /** 手机号。 */
        @Size(max = 20, message = "手机号不得超过 20 字符")
        String phone,

        /** 是否超管。 */
        Boolean adminFlag) {
}
