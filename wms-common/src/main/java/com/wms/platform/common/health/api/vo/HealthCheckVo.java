package com.wms.platform.common.health.api.vo;

import lombok.Getter;

/**
 * 骨架自检响应（规范条目 B2/B3：响应用 Vo，不用 {@code Map} 返回）。
 */
@Getter
public class HealthCheckVo {

    /** 回显内容。 */
    private final String echo;

    private HealthCheckVo(String echo) {
        this.echo = echo;
    }

    /**
     * 静态工厂（规范条目 B4）。
     *
     * @param echo 回显内容
     * @return 自检响应
     */
    public static HealthCheckVo of(String echo) {
        return new HealthCheckVo(echo);
    }
}
