package com.wms.platform.discovery;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import reactor.core.publisher.Flux;

/**
 * 响应式服务发现客户端（**网关用**）。
 *
 * <p>网关是 WebFlux，`lb://` 路由需要 {@link ReactiveDiscoveryClient}。
 * 注意：{@link NacosInstanceProvider} 内部是阻塞调用，这里用 {@code subscribeOn} 把它挪到弹性线程池，
 * 避免阻塞 Netty 的事件循环线程（这是响应式集成里最常见的性能事故）。
 */
@RequiredArgsConstructor
public class NacosReactiveDiscoveryClient implements ReactiveDiscoveryClient {

    /** 共享实例查询实现。 */
    private final NacosInstanceProvider provider;

    @Override
    public String description() {
        return provider.description();
    }

    @Override
    public Flux<ServiceInstance> getInstances(String serviceId) {
        return Flux.defer(() -> Flux.fromIterable(provider.instances(serviceId)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> getServices() {
        return Flux.defer(() -> Flux.fromIterable(provider.services()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
