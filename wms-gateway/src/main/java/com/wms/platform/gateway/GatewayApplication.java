package com.wms.platform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动类（Spring Cloud Gateway，WebFlux 响应式）。
 *
 * <p><b>为什么独立成一个应用</b>：网关是流量入口，需要独立部署、独立伸缩、独立发布；
 * 它也是 WebFlux 技术栈（而业务服务是 Servlet），两者不能混在一个进程里。
 *
 * <p><b>依赖边界</b>：本应用只依赖 {@code wms-kernel}（错误码/统一响应/Nacos/令牌验签），
 * **不依赖 wms-common**——后者带 {@code spring-boot-starter-web}，会让网关直接启动失败。
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
