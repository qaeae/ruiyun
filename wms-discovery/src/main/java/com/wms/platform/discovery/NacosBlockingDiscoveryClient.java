package com.wms.platform.discovery;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;

/**
 * 命令式（阻塞）服务发现客户端（**未来的服务间调用用**，如 Feign/RestClient + `lb://`）。
 *
 * <p>与 {@link NacosReactiveDiscoveryClient} 分开实现的原因见 {@link NacosInstanceProvider} 的类注释
 * （两个接口同名方法返回类型不兼容，无法合并到一个类里）。
 */
@RequiredArgsConstructor
public class NacosBlockingDiscoveryClient implements DiscoveryClient {

    /** 共享实例查询实现。 */
    private final NacosInstanceProvider provider;

    @Override
    public String description() {
        return provider.description();
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
        return provider.instances(serviceId);
    }

    @Override
    public List<String> getServices() {
        return provider.services();
    }
}
