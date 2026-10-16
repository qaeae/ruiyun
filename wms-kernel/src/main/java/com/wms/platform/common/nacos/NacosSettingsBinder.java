package com.wms.platform.common.nacos;

import lombok.experimental.UtilityClass;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 从 {@link ConfigurableEnvironment} 绑定 {@link NacosSettings}。
 *
 * <p>为什么不用 {@code Binder}/{@code @ConfigurationProperties}：本类在
 * {@code EnvironmentPostProcessor} 阶段就要用，那时容器还没创建、`@ConfigurationProperties`
 * 还没生效。手工读属性虽然朴素，但**没有隐藏行为**——每一层的取值都能直接说清。
 */
@UtilityClass
public class NacosSettingsBinder {

    /**
     * 绑定设置。
     *
     * @param environment 环境
     * @return 设置（缺省值见 {@link NacosSettings} 字段默认值）
     */
    public NacosSettings bind(ConfigurableEnvironment environment) {
        NacosSettings settings = new NacosSettings();
        settings.setEnabled(environment.getProperty("wms.nacos.enabled", Boolean.class, false));
        settings.setServerAddr(environment.getProperty("wms.nacos.server-addr", "127.0.0.1:8848"));
        settings.setNamespace(environment.getProperty("wms.nacos.namespace", ""));
        settings.setGroup(environment.getProperty("wms.nacos.group", "DEFAULT_GROUP"));
        settings.setServiceName(environment.getProperty("wms.nacos.service-name", "wms-core"));
        settings.setUsername(environment.getProperty("wms.nacos.username", ""));
        settings.setPassword(environment.getProperty("wms.nacos.password", ""));
        settings.setRegisterEnabled(environment.getProperty("wms.nacos.register-enabled", Boolean.class, true));
        settings.setConfigDataIds(environment.getProperty("wms.nacos.config-data-ids", "wms-platform.yml"));
        settings.setTimeoutMs(environment.getProperty("wms.nacos.timeout-ms", Long.class, 3000L));
        settings.setFailFast(environment.getProperty("wms.nacos.fail-fast", Boolean.class, false));
        settings.setInstanceIp(environment.getProperty("wms.nacos.instance-ip", ""));
        settings.setInstancePort(environment.getProperty("wms.nacos.instance-port", Integer.class));
        return settings;
    }
}
