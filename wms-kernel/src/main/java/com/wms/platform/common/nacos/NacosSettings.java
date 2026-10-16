package com.wms.platform.common.nacos;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * Nacos 接入设置（从本地配置/环境变量读取，**不来自 Nacos 自己**——否则是鸡生蛋问题）。
 *
 * <p>为什么不用 {@code spring.cloud.nacos.*}：本项目没有引入 Spring Cloud（原因见父 pom 的
 * {@code nacos-client.version} 注释：最新 spring-cloud-alibaba 只支持 Boot 3.2/3.3，本项目是 3.5.15）。
 * 因此这里用中立的 {@code wms.nacos.*} 前缀，语义清晰且不受第三方命名空间约束。
 *
 * <p>配置优先级（重要）：Nacos 拉到的值**插在环境变量之后、本地配置文件之前**，即
 * <pre>命令行参数 &gt; 系统属性 &gt; 环境变量 &gt; Nacos &gt; jar 内 application.yml</pre>
 * 这样既能让 Nacos 覆盖打包默认值（"改配置不用重打镜像"），又保留了**应急用环境变量就地覆盖**的能力。
 */
@Getter
@Setter
public class NacosSettings {

    /** 是否启用 Nacos 接入（默认 false：避免开发与 CI 被外部依赖卡住）。 */
    private boolean enabled;

    /** 服务端地址，如 {@code 127.0.0.1:8848}。 */
    private String serverAddr = "127.0.0.1:8848";

    /** 命名空间（空 = public）。 */
    private String namespace = "";

    /** 分组。 */
    private String group = "DEFAULT_GROUP";

    /** 服务名（注册用）。 */
    private String serviceName = "wms-core";

    /** 用户名（Nacos 开了鉴权时必填）。 */
    private String username = "";

    /** 口令（Nacos 开了鉴权时必填）。**只从环境变量/密钥管理注入，不要写进 Nacos 配置**。 */
    private String password = "";

    /** 是否注册实例（关掉即"只用配置中心不注册"）。 */
    private boolean registerEnabled = true;

    /** 要拉取的配置 DataId 列表（逗号分隔）。 */
    private String configDataIds = "wms-platform.yml";

    /** 拉取/注册超时（毫秒）。默认给足 3 秒：localhost 场景实测 <200ms。 */
    private long timeoutMs = 3000L;

    /** Nacos 不可用时是否让应用启动失败。默认 **false**（降级：用本地配置启动并打 ERROR）。 */
    private boolean failFast = false;

    /** 注册实例时使用的 IP（空 = 自动探测本机地址）。 */
    private String instanceIp = "";

    /** 注册实例时使用的端口（空 = 取 {@code server.port}）。 */
    private Integer instancePort;

    /**
     * 解析 DataId 列表。
     *
     * @return DataId 列表（已去空、去重）
     */
    public List<String> dataIdList() {
        if (configDataIds == null || configDataIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configDataIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 是否配置了鉴权凭据。
     *
     * @return 用户名与口令都非空返回 true
     */
    public boolean hasCredential() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
