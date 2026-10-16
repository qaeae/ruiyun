package com.wms.platform.gateway.config;

import com.wms.platform.common.nacos.NacosServiceRegistrar;
import com.wms.platform.common.nacos.NacosSettingsBinder;
import com.wms.platform.common.nacos.NacosStartupReporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * 网关自身的 Nacos 注册与汇报装配。
 *
 * <p><b>为什么要在这里单独装配（而不是像业务服务那样自动带上）</b>：网关**刻意不依赖 `wms-common`**
 * （它带 servlet，会让 WebFlux 网关启动失败），而注册器/汇报器这两个 Bean 原先定义在
 * `wms-common` 的 `PlatformInfrastructureConfig` 里。类本身在 `wms-kernel`（无 servlet）✓，
 * 所以这里只需把它们装配出来即可。
 *
 * <p>网关注册自己是"单实例也注册"这条约定的落实：运维在 Nacos 控制台能一眼看到网关实例，
 * 而不是只能靠猜。它不需要被别人发现（没人调网关），但注册成本极低、排查价值高。
 */
@Configuration
public class GatewayNacosConfig {

    /**
     * 注册器：把网关实例注册到 Nacos（失败不阻断启动，状态通过日志汇报）。
     *
     * @param environment 环境
     * @return 注册器
     */
    @Bean
    public NacosServiceRegistrar nacosServiceRegistrar(Environment environment) {
        return new NacosServiceRegistrar(
                NacosSettingsBinder.bind((ConfigurableEnvironment) environment), environment);
    }

    /**
     * 汇报器：应用就绪后打印"配置拉到了没、注册上了没"（配置拉取发生在日志系统初始化之前，那时的日志会丢）。
     *
     * @param environment 环境
     * @param nacosServiceRegistrar 注册器
     * @return 汇报器
     */
    @Bean
    public NacosStartupReporter nacosStartupReporter(Environment environment,
                                                     NacosServiceRegistrar nacosServiceRegistrar) {
        return new NacosStartupReporter(environment, nacosServiceRegistrar);
    }
}
