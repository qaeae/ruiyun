package com.wms.platform.config;

import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.device.DeviceGateway;
import com.wms.platform.common.gateway.GatewayHeaderGuardFilter;
import com.wms.platform.common.device.simulated.SimulatedBarcodeScanner;
import com.wms.platform.common.device.simulated.SimulatedElectronicScale;
import com.wms.platform.common.device.simulated.SimulatedLabelPrinter;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.idempotent.DbIdempotentService;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.common.idempotent.mapper.IdempotentMapper;
import com.wms.platform.common.nacos.NacosServiceRegistrar;
import com.wms.platform.common.nacos.NacosStartupReporter;
import com.wms.platform.common.nacos.NacosSettingsBinder;
import com.wms.platform.common.security.PiiCipher;
import com.wms.platform.common.trace.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * 基础设施装配：雪花 ID、PII 加解密、幂等服务、TraceId 过滤器、设备网关。
 *
 * <p>装配原则：横切组件与设备端口在 {@code wms-common} 定义、在 {@code config}（组合根）装配，
 * 业务模块只注入接口（{@code IdempotentService} / {@code PiiCipher} / {@code DeviceGateway}），
 * 不感知实现与数据源细节。
 *
 * <p><b>本类不得依赖任何业务模块</b>（{@code modules.*}）：它提供的是"业务模块也要用的 Bean"，
 * 一旦反向依赖（例如注入某个 {@code *Service}），就会与这些 Bean 构成**启动期循环依赖**——
 * PoC-3 的全链路 IT 已实测踩到一次，原因与修法见 {@link WebInterceptorConfig} 类注释。
 * 需要业务服务参与的装配（如设备绑定拦截器）请放到 Web 装配类。
 *
 * <p>规范落地（B9）：{@code @RequiredArgsConstructor} + {@code private final} 构造注入；
 * 配置值一律来自 {@link WmsProperties}（{@code @ConfigurationProperties}），**不再使用字段 {@code @Value}**。
 */
@Configuration
@RequiredArgsConstructor
/*
 * 显式扫描根包下的 Mapper —— 这里有两个必须写清楚的坑（P1.2 实测踩到）：
 *
 * ① **一旦显式声明 @MapperScan，MyBatis-Plus 的自动扫描就失效**：它的自动扫描条件是
 *    "容器里没有 MapperScannerConfigurer"。所以只加一句 @MapperScan("...idempotent.mapper")，
 *    会连带把其它域的 Mapper 全部漏掉，表现为启动报 "No qualifying bean of type XxxMapper"。
 * ② **@MapperScan 不过滤注解时会把所有接口都当 Mapper**：那会把 InventoryService 这类
 *    业务接口也注册成 MyBatis Mapper，启动直接失败。所以必须用 annotationClass 过滤，
 *    等价于原来隐式扫描的行为（只认 @Mapper 接口）。
 *
 * 写在共享装配里而不是各服务启动类里：34 个 Mapper 分散在 7 个域模块，让每个服务各自列清单
 * 既啰嗦又容易漏；按 "根包 + @Mapper 注解" 扫描时，**扫描结果自动等于该服务 classpath 上有的模块**
 * （auth 只拿到 au+common 的 Mapper，core 拿到全部），这正是我们想要的边界。
 */
@MapperScan(basePackages = "com.wms.platform", annotationClass = Mapper.class)
@EnableConfigurationProperties(WmsProperties.class)
public class PlatformInfrastructureConfig {

    /** 模拟模式取值。 */
    private static final String MODE_SIMULATED = "SIMULATED";

    /** 真实设备模式取值。 */
    private static final String MODE_REAL = "REAL";

    /** 项目自有配置（构造注入）。 */
    private final WmsProperties wmsProperties;

    /**
     * 雪花 ID 生成器。
     *
     * @return 按配置的机房/机器编号初始化的生成器
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(
                wmsProperties.getSnowflake().getDatacenterId(),
                wmsProperties.getSnowflake().getWorkerId());
    }

    /**
     * PII 加解密组件（密钥由配置/KMS 注入）。
     *
     * @return PII 加解密器
     */
    @Bean
    public PiiCipher piiCipher() {
        return new PiiCipher(wmsProperties.getSecurity().getPiiKeyBase64());
    }

    /**
     * 幂等裁决服务。
     *
     * @param idempotentMapper 幂等记录 Mapper（SQL 收口处）
     * @param idGenerator      雪花 ID 生成器
     * @return 幂等服务实现
     */
    @Bean
    public IdempotentService idempotentService(IdempotentMapper idempotentMapper, SnowflakeIdGenerator idGenerator) {
        return new DbIdempotentService(idempotentMapper, idGenerator);
    }

    /**
     * 设备网关（扫码/打印/称重三端口的统一入口）。
     *
     * <p>模式取值见 {@code WmsProperties.Device#mode}：
     * <ul>
     *   <li>{@code SIMULATED}：内置模拟设备——开发机无硬件即可跑通 P1/P3 全链路；</li>
     *   <li>{@code REAL}：**启动即失败**。一期服务端不直连硬件（扫码枪是 HID 键盘、
     *       打印机走客户端打印控件），真实适配器由 W5 客户端实现；
     *       若不在这里显式失败，就会出现"配置写了 REAL、实际在静默丢弃打印任务"的事故。</li>
     * </ul>
     *
     * @return 设备网关
     */
    @Bean
    public DeviceGateway deviceGateway() {
        // 1. 读取模式
        String mode = wmsProperties.getDevice().getMode() == null
                ? MODE_SIMULATED : wmsProperties.getDevice().getMode().trim().toUpperCase();

        // 2. REAL 模式明确拒绝（带错误码，避免启动期的 RuntimeException —— B5）
        if (MODE_REAL.equals(mode)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "wms.device.mode=REAL 暂不支持：真实设备适配器由 W5 客户端（PDA/仓库 PC）实现，"
                            + "服务端仅提供端口契约，见 08-设备网关与本地设备接入.md §三");
        }

        // 3. SIMULATED：装配内置模拟设备（无硬件可跑全链路）
        return new DeviceGateway(
                new SimulatedBarcodeScanner(),
                new SimulatedLabelPrinter(),
                new SimulatedElectronicScale());
    }

    /**
     * 身份头防伪造过滤器：只接受网关注入的身份头（详见过滤器类注释的越权场景说明）。
     *
     * <p>顺序放在 TraceId 之后、业务处理之前：这样被拒绝的请求也有 traceId 可查。
     *
     * @return 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<GatewayHeaderGuardFilter> gatewayHeaderGuardFilter() {
        FilterRegistrationBean<GatewayHeaderGuardFilter> registration =
                new FilterRegistrationBean<>(new GatewayHeaderGuardFilter(wmsProperties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*");
        registration.setName("gatewayHeaderGuardFilter");
        return registration;
    }

    /**
     * Nacos 注册器：把本实例注册到 Nacos（服务发现那一半；配置中心那一半见 {@code NacosConfigLoader}）。
     *
     * <p>注册失败**不影响启动**（降级），但状态会通过 {@code /api/v1/health} 暴露出来，
     * 避免"以为注册了、其实没注册"这种最难查的问题。
     * 设置绑定复用 {@link NacosSettingsBinder}，与启动期拉配置的口径完全一致（同名同默认值）。
     *
     * @param environment 环境（读取 wms.nacos.* 与真实端口）
     * @return 注册器
     */
    @Bean
    public NacosServiceRegistrar nacosServiceRegistrar(Environment environment) {
        return new NacosServiceRegistrar(
                NacosSettingsBinder.bind((ConfigurableEnvironment) environment), environment);
    }

    /**
     * Nacos 接入结果汇报器：应用就绪后打日志说明"配置拉到了没、注册上了没"。
     *
     * <p>存在的理由：配置拉取发生在日志系统初始化之前，那时的日志会丢（真实踩坑），
     * 所以必须把结论延迟到 {@code ApplicationReadyEvent} 再汇报，否则运维无法判断配置中心是否生效。
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

    /**
     * TraceId 过滤器：置于过滤器链最前，保证异常处理也能带 traceId。
     *
     * @return 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        return registration;
    }
}
