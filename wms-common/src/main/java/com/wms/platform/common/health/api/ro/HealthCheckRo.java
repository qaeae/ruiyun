package com.wms.platform.common.health.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 骨架自检请求体（规范条目 B2/B3：请求入参用 Ro，不用 {@code Map} 接收）。
 *
 * @param payload 回显内容
 */
public record HealthCheckRo(

        /** 回显内容：必填，长度 ≤32。 */
        @NotBlank(message = "不能为空")
        @Size(max = 32, message = "长度不得超过 32")
        String payload) {
}
