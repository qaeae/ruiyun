package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增角色入参（03 API-AUTH-02）。
 *
 * @param roleCode    角色码（租户内唯一）
 * @param name        名称
 * @param description 说明
 */
public record RoleCreateRo(

        /** 角色码。 */
        @NotBlank(message = "角色码不能为空")
        @Size(max = 64, message = "角色码不得超过 64 字符")
        String roleCode,

        /** 名称。 */
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称不得超过 64 字符")
        String name,

        /** 说明。 */
        @Size(max = 255, message = "说明不得超过 255 字符")
        String description) {
}
