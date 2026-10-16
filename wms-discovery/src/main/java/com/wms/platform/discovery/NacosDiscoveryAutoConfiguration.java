package com.wms.platform.discovery;

import com.wms.platform.common.nacos.NacosSettings;
import com.wms.platform.common.nacos.NacosSettingsBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * 服务发现自动配置：把 Nacos 实例查询注册给 Spring Cloud LoadBalancer。
 *
 * <p><b>注册方式</b>：Spring Boot 3 要求写在
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * （{@code spring.factories} 里的 {@code EnableAutoConfiguration} 在 Boot 3 不再生效——本模块开发时踩到的坑）。
 *
 * <p><b>触发条件</b>：{@code wms.nacos.enabled=true}。关闭时不注册任何 DiscoveryClient，
 * 于是 {@code lb://} 无实例可用（网关用固定 URI 兜底）——这是显式取舍，
 * 避免"悄悄用了一个永远返回空的发现客户端"这种最难查的故障。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "wms.nacos", name = "enabled", havingValue = "true")
public class NacosDiscoveryAutoConfiguration {

    /**
     * 共享实例查询实现。
     *
     * @param environment 环境
     * @return provider
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosInstanceProvider nacosInstanceProvider(Environment environment) {
        NacosSettings settings = NacosSettingsBinder.bind((ConfigurableEnvironment) environment);
        return new NacosInstanceProvider(settings);
    }

    /**
     * 响应式客户端（网关的 lb:// 路由用）。
     *
     * @param provider 共享实现
     * @return 响应式客户端
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosReactiveDiscoveryClient nacosReactiveDiscoveryClient(NacosInstanceProvider provider) {
        return new NacosReactiveDiscoveryClient(provider);
    }

    /**
     * 命令式客户端（服务间阻塞调用用）。
     *
     * @param provider 共享实现
     * @return 命令式客户端
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosBlockingDiscoveryClient nacosBlockingDiscoveryClient(NacosInstanceProvider provider) {
        return new NacosBlockingDiscoveryClient(provider);
    }
}
