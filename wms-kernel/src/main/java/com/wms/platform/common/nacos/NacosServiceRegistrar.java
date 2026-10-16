package com.wms.platform.common.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 把自己注册进 Nacos（**注册中心**那一半），并在关闭时摘除。
 *
 * <p><b>降级原则</b>：注册失败**不让应用起不来**（打 ERROR + 标记状态），因为注册中心的作用是"让别人找到我"，
 * 而不是"我能不能干活"。反过来，注册失败必须**可见**——所以状态会通过健康检查暴露出来（见 {@code /api/v1/health}）。
 *
 * <p><b>为什么用 ApplicationRunner 而不是 {@code @PostConstruct}</b>：注册时要用到真实的 {@code server.port}
 * （可能被 0 端口随机分配或被 Nacos 配置覆盖），必须等容器就绪、端口确定后再注册。
 */
@Slf4j
public class NacosServiceRegistrar implements ApplicationRunner, DisposableBean {

    /** 设置。 */
    private final NacosSettings settings;

    /** 环境（取 server.port 等）。 */
    private final Environment environment;

    /** 命名服务（懒创建；失败后保持 null）。 */
    private NamingService namingService;

    /** 已注册实例（用于摘除与健康上报）。 */
    private Instance registered;

    /** 注册状态（健康检查读取）。 */
    private volatile String status = "未启用";

    /**
     * 构造。
     *
     * @param settings    设置
     * @param environment 环境
     */
    public NacosServiceRegistrar(NacosSettings settings, Environment environment) {
        this.settings = settings;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. 未启用直接返回（开发/CI 默认如此）
        if (!settings.isEnabled()) {
            status = "未启用";
            log.info("[Nacos] 注册中心未启用（wms.nacos.enabled=false）");
            return;
        }
        if (!settings.isRegisterEnabled()) {
            status = "已关闭注册";
            log.info("[Nacos] 注册已显式关闭（wms.nacos.register-enabled=false），仅使用配置中心");
            return;
        }
        // 2. 组装实例信息
        try {
            Instance instance = buildInstance();
            // 3. 注册（心跳由客户端自动维持）
            namingService = NacosFactory.createNamingService(toProperties(settings));
            namingService.registerInstance(settings.getServiceName(), settings.getGroup(), instance);
            registered = instance;
            status = "已注册";
            log.info("[Nacos] 实例已注册 service={} group={} namespace='{}' address={}:{} instanceId={}",
                    settings.getServiceName(), settings.getGroup(), settings.getNamespace(),
                    instance.getIp(), instance.getPort(), instance.getInstanceId());
        } catch (Exception ex) {
            // 4. 降级：不抛出，但必须可见
            status = "注册失败：" + ex.getMessage();
            log.error("[Nacos] 实例注册失败（应用继续运行，但不会被服务发现找到）serverAddr={} service={} 原因={}",
                    settings.getServerAddr(), settings.getServiceName(), ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (namingService == null || registered == null) {
            return;
        }
        try {
            namingService.deregisterInstance(settings.getServiceName(), settings.getGroup(),
                    registered.getIp(), registered.getPort());
            log.info("[Nacos] 实例已摘除 service={} address={}:{}",
                    settings.getServiceName(), registered.getIp(), registered.getPort());
        } catch (Exception ex) {
            // 临时实例（ephemeral）在客户端断开后会被服务端自动摘除，因此这里失败不是致命问题
            log.warn("[Nacos] 实例摘除失败（临时实例会在心跳超时后被服务端自动摘除）：{}", ex.getMessage());
        } finally {
            try {
                namingService.shutDown();
            } catch (NacosException ex) {
                log.debug("[Nacos] 命名客户端关闭异常（忽略）：{}", ex.getMessage());
            }
            registered = null;
            status = "已摘除";
        }
    }

    /**
     * 注册状态（供健康检查展示）。
     *
     * @return 状态文案
     */
    public String status() {
        return status;
    }

    /**
     * 是否注册成功。
     *
     * @return 成功返回 true
     */
    public boolean registered() {
        return "已注册".equals(status);
    }

    /**
     * 构造实例信息（临时实例 + 元数据便于排查"这个实例是谁"）。
     *
     * @return 实例
     * @throws Exception 取本机地址失败
     */
    private Instance buildInstance() throws Exception {
        Instance instance = new Instance();
        instance.setIp(resolveIp());
        instance.setPort(resolvePort());
        instance.setEphemeral(true);
        instance.setWeight(1.0D);
        Map<String, String> metadata = new HashMap<>(4);
        metadata.put("profile", String.join(",", environment.getActiveProfiles()));
        metadata.put("app", environment.getProperty("spring.application.name", "wms-platform"));
        metadata.put("startedAt", String.valueOf(System.currentTimeMillis()));
        instance.setMetadata(metadata);
        return instance;
    }

    /**
     * 解析注册 IP。
     *
     * @return IP
     * @throws Exception 探测失败
     */
    private String resolveIp() throws Exception {
        if (settings.getInstanceIp() != null && !settings.getInstanceIp().isBlank()) {
            return settings.getInstanceIp();
        }
        return InetAddress.getLocalHost().getHostAddress();
    }

    /**
     * 解析注册端口（优先显式配置，其次真实监听端口）。
     *
     * @return 端口
     */
    private int resolvePort() {
        if (settings.getInstancePort() != null) {
            return settings.getInstancePort();
        }
        return environment.getProperty("local.server.port", Integer.class,
                environment.getProperty("server.port", Integer.class, 8080));
    }

    /**
     * 组装客户端属性。
     *
     * @param settings 设置
     * @return 属性
     */
    private Properties toProperties(NacosSettings settings) {
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
