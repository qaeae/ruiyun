package com.wms.platform.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.wms.platform.common.nacos.NacosSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Nacos 实例查询的共享实现（被响应式与命令式两个客户端复用）。
 *
 * <p><b>为什么必须拆成"共享实现 + 两个客户端"（真实约束）</b>：Spring Cloud 的
 * {@code ReactiveDiscoveryClient} 与 {@code DiscoveryClient} 声明了**同名但返回类型不同**的方法
 * （{@code getInstances(String)} 分别返回 {@code Flux<ServiceInstance>} 与 {@code List<ServiceInstance>}）。
 * Java 里同一泛型擦除签名不可能有两种返回类型，因此**一个类无法同时实现两个接口**——
 * 这不是偷懒，是语言限制（本模块第一次尝试合并实现时编译直接报 incompatible return type）。
 *
 * <p><b>容错策略</b>：Nacos 客户端内部有实例缓存，服务端短暂不可用时仍能返回上次结果（可用性优先）；
 * 若连缓存都拿不到，这里返回**空列表 + ERROR 日志**而不是抛异常——让负载均衡得到"无可用实例"并快速失败，
 * 避免把 Nacos 异常抛进业务线程栈变成看不懂的 500。
 */
@Slf4j
@RequiredArgsConstructor
public class NacosInstanceProvider {

    /** Nacos 设置（与注册/配置中心同一份 {@code wms.nacos.*} 口径）。 */
    private final NacosSettings settings;

    /** 命名服务（懒初始化；失败后保持 null，下次调用重试）。 */
    private volatile NamingService namingService;

    /**
     * 查询某个服务的健康实例。
     *
     * @param serviceId 服务名
     * @return 实例列表；查询失败返回空列表
     */
    public List<ServiceInstance> instances(String serviceId) {
        try {
            return naming().selectInstances(serviceId, settings.getGroup(), true, true).stream()
                    .map(this::toServiceInstance)
                    .toList();
        } catch (Exception ex) {
            log.error("[服务发现] 查询实例失败 service={} serverAddr={} 原因={}",
                    serviceId, settings.getServerAddr(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 查询全部服务名。
     *
     * @return 服务名列表；查询失败返回空列表
     */
    public List<String> services() {
        try {
            return naming().getServicesOfServer(1, Integer.MAX_VALUE, settings.getGroup()).getData();
        } catch (Exception ex) {
            log.error("[服务发现] 查询服务列表失败 group={} 原因={}", settings.getGroup(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 本适配器的描述（Actuator / 日志排查用）。
     *
     * @return 描述
     */
    public String description() {
        return "Nacos 服务发现（serverAddr=" + settings.getServerAddr() + ", group=" + settings.getGroup()
                + ", namespace='" + settings.getNamespace() + "'）";
    }

    /**
     * Nacos 实例 → Spring Cloud 实例。
     *
     * @param instance Nacos 实例
     * @return Spring Cloud 实例
     */
    private ServiceInstance toServiceInstance(Instance instance) {
        Map<String, String> metadata = instance.getMetadata() == null ? Map.of() : instance.getMetadata();
        String instanceId = instance.getInstanceId() == null
                ? instance.getIp() + ":" + instance.getPort() : instance.getInstanceId();
        return new DefaultServiceInstance(instanceId, instance.getServiceName(),
                instance.getIp(), instance.getPort(), false, metadata);
    }

    /**
     * 获取（必要时创建）命名服务。
     *
     * @return 命名服务
     * @throws NacosException 创建失败
     */
    private NamingService naming() throws NacosException {
        NamingService local = namingService;
        if (local == null) {
            synchronized (this) {
                if (namingService == null) {
                    namingService = NacosFactory.createNamingService(toProperties());
                    log.info("[服务发现] 已连接 Nacos serverAddr={} group={}",
                            settings.getServerAddr(), settings.getGroup());
                }
                local = namingService;
            }
        }
        return local;
    }

    /**
     * 组装客户端属性。
     *
     * @return 属性
     */
    private Properties toProperties() {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, settings.getServerAddr());
        properties.put(PropertyKeyConst.NAMESPACE, settings.getNamespace());
        if (settings.hasCredential()) {
            properties.put(PropertyKeyConst.USERNAME, settings.getUsername());
            properties.put(PropertyKeyConst.PASSWORD, settings.getPassword());
        }
        return properties;
    }
}
