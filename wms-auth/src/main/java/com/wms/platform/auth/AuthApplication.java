package com.wms.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 权限与审批服务启动类（P1.2 从单体拆出的第一个服务）。
 *
 * <p><b>扫描范围</b>：只扫"本服务真正需要的包"——
 * {@code com.wms.platform.config}（共享装配：基础设施/幂等拦截器/JSON 时间格式）、
 * {@code com.wms.platform.common}（横切能力）、{@code com.wms.platform.modules.au}（本域）。
 *
 * <p><b>为什么不直接扫根包 {@code com.wms.platform}</b>：拆分时踩到过——组件扫描会覆盖
 * classpath 上的**所有**模块，而某些 Bean 需要别的域的东西（例如 {@code AuWebConfig} 需要
 * md 的 DeviceService），于是"扫得太宽"会让服务启动失败或无意间耦合上无关模块。
 * 服务边界要显式声明，扫描范围就是最直白的表达。
 *
 * <p><b>Mapper 不用在这里声明</b>：共享装配 {@code PlatformInfrastructureConfig} 里已有
 * {@code @MapperScan(basePackages="com.wms.platform", annotationClass=Mapper.class)}，
 * 它按"根包 + @Mapper 注解"扫描，结果自动等于本服务 classpath 上有的模块。
 */
@SpringBootApplication(scanBasePackages = {
        "com.wms.platform.config",
        "com.wms.platform.common",
        "com.wms.platform.modules.au"
})
@EnableTransactionManagement
public class AuthApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
