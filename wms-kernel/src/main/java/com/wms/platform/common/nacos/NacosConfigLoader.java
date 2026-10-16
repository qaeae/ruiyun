package com.wms.platform.common.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 启动前从 Nacos 拉取配置并注入 {@link ConfigurableEnvironment}（**配置中心**那一半）。
 *
 * <p><b>为什么是 EnvironmentPostProcessor 而不是 {@code @Configuration}</b>：配置必须在容器刷新之前就位，
 * 否则 {@code @Value}/{@code @ConfigurationProperties} 绑定到的还是打包里的旧值。
 *
 * <p><b>执行时机</b>：order 为 {@link Ordered#HIGHEST_PRECEDENCE} + 20，晚于 Spring Boot 的
 * {@code ConfigDataEnvironmentPostProcessor}（+10），因此本地 {@code application.yml} 已就绪，
 * 我们才能读到 {@code wms.nacos.*}——服务端地址必须来自本地，否则是"用 Nacos 的地址去问 Nacos"。
 *
 * <p><b>优先级</b>：拉到的属性源插在 {@code systemEnvironment} 之后，得到
 * <pre>命令行 &gt; 系统属性 &gt; 环境变量 &gt; Nacos &gt; 本地 application.yml</pre>
 *
 * <p><b>降级</b>：默认 {@code fail-fast=false}——Nacos 不可用时用本地配置继续启动，绝不因配置中心抖动导致业务不可用。
 *
 * <p><b>为什么这里不写日志（真实踩坑）</b>：本方法运行在<b>日志系统初始化之前</b>
 * （LoggingApplicationListener 的 order 也是 +20 之后，晚于本类），此时打的日志**不会出现在任何输出里**。
 * 实测表现：Nacos 配置明明生效了（令牌有效期跟着 Nacos 的值变化），但日志里一条 Nacos 记录都没有，
 * 运维因此无法判断配置到底有没有拉到。所以这里只把结论写成**属性标记**，
 * 交给 {@link NacosStartupReporter} 在应用就绪后统一汇报（那时日志一定可用）。
 */
public class NacosConfigLoader implements EnvironmentPostProcessor, Ordered {

    /** 早于普通后处理器，晚于 Spring Boot 的 ConfigData（+10）。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /** 服务端可达性探针路径（Nacos 只读健康检查接口）。 */
    public static final String PROBE_PATH = "/nacos/v1/console/health/readiness";

    /** 属性源前缀（便于排查"这个值到底来自哪一层"）。 */
    public static final String SOURCE_PREFIX = "nacos:";

    /** 配置加载状态属性名（由 {@link NacosStartupReporter} 读取并汇报）。 */
    public static final String KEY_STATUS = "nacos.config.status";

    /** 已加载的 DataId 清单。 */
    public static final String KEY_DATA_IDS = "nacos.config.data-ids";

    /** 明细/失败原因。 */
    public static final String KEY_MESSAGE = "nacos.config.message";

    /** 是否处于降级状态（true = 远端不可达，值可能来自本地快照）。 */
    public static final String KEY_DEGRADED = "nacos.config.degraded";

    /** 服务端可达性（探测结果：可达/不可达）。 */
    public static final String KEY_REACHABLE = "nacos.config.server-reachable";

    /** 状态：未启用。 */
    public static final String STATUS_DISABLED = "未启用";

    /** 状态：已加载（服务端可达）。 */
    public static final String STATUS_LOADED = "已加载";

    /**
     * 状态：已加载但服务端不可达。
     *
     * <p><b>为什么单独一个状态（真实踩坑）</b>：nacos-client 在服务端不可达时会**回退到本地快照**
     * （上次成功拉取的缓存），于是 {@code getConfig} 依然返回内容、应用也照常启动——
     * 但那份配置**可能已经过期**。实测时日志报"配置中心已生效"，而实际上 Nacos 已经停了，
     * 运维完全看不出问题。所以这种情况必须与"真·远端可达"区分开，并且按告警级别输出。
     */
    public static final String STATUS_LOADED_FROM_SNAPSHOT = "已加载（服务端不可达，值可能来自本地快照）";

    /** 状态：降级（拉取失败且无快照，使用 jar 内配置）。 */
    public static final String STATUS_DEGRADED = "降级（使用 jar 内配置）";

    /** 可达性：可达。 */
    public static final String REACHABLE = "可达";

    /** 可达性：不可达。 */
    public static final String UNREACHABLE = "不可达";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 1. 读本地设置（Nacos 的地址必须先来自本地/环境变量）
        NacosSettings settings = NacosSettingsBinder.bind(environment);
        if (!settings.isEnabled()) {
            mark(environment, STATUS_DISABLED, "", "wms.nacos.enabled=false", false);
            return;
        }
        List<String> dataIds = settings.dataIdList();
        if (dataIds.isEmpty()) {
            mark(environment, STATUS_DISABLED, "", "wms.nacos.config-data-ids 为空", false);
            return;
        }
        // 2. 先探测服务端可达性：nacos-client 在服务端不可达时会**静默回退到本地快照**，
        //    只看 getConfig 的返回值无法区分"真·远端配置"与"过期缓存"，必须自己探一次。
        boolean reachable = probe(settings);
        // 3. 逐份拉取
        ConfigService configService = null;
        try {
            configService = NacosFactory.createConfigService(toProperties(settings));
            List<PropertySource<?>> loaded = new ArrayList<>();
            List<String> loadedIds = new ArrayList<>();
            List<String> missingIds = new ArrayList<>();
            for (String dataId : dataIds) {
                String content = configService.getConfig(dataId, settings.getGroup(), settings.getTimeoutMs());
                if (content == null || content.isBlank()) {
                    missingIds.add(dataId);
                    continue;
                }
                loaded.addAll(parse(dataId, content));
                loadedIds.add(dataId);
            }
            // 4. 注入环境（放在环境变量之后 = 可被环境变量应急覆盖，但覆盖本地文件）
            for (PropertySource<?> source : loaded) {
                insert(environment.getPropertySources(), source);
            }
            // 5. 记录结论（不写日志，原因见类注释）
            String detail = "serverAddr=" + settings.getServerAddr() + " group=" + settings.getGroup()
                    + " namespace='" + settings.getNamespace() + "'";
            if (!missingIds.isEmpty()) {
                detail = detail + "；以下 DataId 不存在（首次部署属正常）：" + String.join(",", missingIds);
            }
            if (reachable) {
                mark(environment, STATUS_LOADED, String.join(",", loadedIds), detail, false, REACHABLE);
            } else {
                // 服务端不可达却仍然拿到了内容 = 来自本地快照，必须按告警处理（可能是过期配置）
                mark(environment, STATUS_LOADED_FROM_SNAPSHOT, String.join(",", loadedIds),
                        detail + "；服务端不可达，本次值来自 nacos-client 本地快照", true, UNREACHABLE);
            }
        } catch (Exception ex) {
            if (settings.isFailFast()) {
                // B5：异常必须带错误码（基础设施故障 → SYSTEM_ERROR）
                throw new BizException(ErrorCode.SYSTEM_ERROR, "[Nacos] 配置中心不可用且已开启 fail-fast："
                        + settings.getServerAddr() + " " + ex.getMessage(), ex);
            }
            mark(environment, STATUS_DEGRADED, "", "serverAddr=" + settings.getServerAddr()
                    + "；原因=" + ex.getMessage(), true);
        } finally {
            shutdown(configService);
        }
    }

    /**
     * 探测 Nacos 服务端可达性（**必须独立探测**，不能只看 getConfig 是否返回内容）。
     *
     * <p>原因：nacos-client 在服务端不可达时会静默回退到本地快照，`getConfig` 照样返回内容。
     * 实测时 Nacos 已停、日志却报"配置中心已生效"，运维看不出"跑的是过期配置"，因此这里用
     * Nacos 自己的只读探针接口判定，与客户端缓存行为无关。
     *
     * @param settings 设置（支持逗号分隔的多个地址，任一可达即算可达）
     * @return 可达返回 true
     */
    private boolean probe(NacosSettings settings) {
        String addr = settings.getServerAddr();
        if (addr == null || addr.isBlank()) {
            return false;
        }
        int timeout = (int) Math.min(settings.getTimeoutMs(), 2000L);
        for (String single : addr.split(",")) {
            String target = single.trim();
            if (target.isEmpty()) {
                continue;
            }
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + target + PROBE_PATH);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return true;
                }
            } catch (Exception ignored) {
                // 单个地址失败继续试下一个；全部失败才算不可达（这里刻意不打日志：日志系统尚未就绪）
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return false;
    }

    /**
     * 写入状态标记（作为**最低优先级**属性源，不会覆盖任何真实配置）。
     *
     * @param environment 环境
     * @param status      状态
     * @param dataIds     已加载 DataId
     * @param message     明细
     * @param degraded    是否降级
     * @param reachable   服务端可达性
     */
    private void mark(ConfigurableEnvironment environment, String status, String dataIds,
                      String message, boolean degraded, String reachable) {
        Map<String, Object> markers = new LinkedHashMap<>(5);
        markers.put(KEY_STATUS, status);
        markers.put(KEY_DATA_IDS, dataIds);
        markers.put(KEY_MESSAGE, message);
        markers.put(KEY_DEGRADED, degraded);
        markers.put(KEY_REACHABLE, reachable);
        environment.getPropertySources().addLast(new MapPropertySource("nacos-status", markers));
    }

    /**
     * 写入状态标记（可达性未知的场景：未启用 / 拉取异常）。
     *
     * @param environment 环境
     * @param status      状态
     * @param dataIds     已加载 DataId
     * @param message     明细
     * @param degraded    是否降级
     */
    private void mark(ConfigurableEnvironment environment, String status, String dataIds,
                      String message, boolean degraded) {
        mark(environment, status, dataIds, message, degraded, degraded ? UNREACHABLE : REACHABLE);
    }

    /**
     * 把 YAML 内容解析成属性源。
     *
     * @param dataId  DataId（用于属性源命名）
     * @param content YAML 文本
     * @return 属性源列表（YAML 可能包含多个文档）
     * @throws Exception 解析失败
     */
    private List<PropertySource<?>> parse(String dataId, String content) throws Exception {
        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getDescription() {
                return "Nacos config [" + dataId + "]";
            }
        };
        return new YamlPropertySourceLoader().load(SOURCE_PREFIX + dataId, resource);
    }

    /**
     * 把 Nacos 属性源插到环境变量之后（保证优先级：环境变量 &gt; Nacos &gt; 本地文件）。
     *
     * @param sources 属性源集合
     * @param source  待插入的属性源
     */
    private void insert(MutablePropertySources sources, PropertySource<?> source) {
        if (sources.contains("systemEnvironment")) {
            sources.addAfter("systemEnvironment", source);
        } else {
            sources.addLast(source);
        }
    }

    /**
     * 组装 nacos-client 属性。
     *
     * @param settings 设置
     * @return 客户端属性
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

    /**
     * 关闭客户端（拉完即关；注册用的是另一个实例，不要互相影响）。
     *
     * @param configService 配置服务（可为 null）
     */
    private void shutdown(ConfigService configService) {
        if (configService == null) {
            return;
        }
        try {
            configService.shutDown();
        } catch (NacosException ignored) {
            // 拉取已完成，关闭失败不影响业务；此处刻意不打日志（日志系统尚未就绪）
        }
    }
}
