package com.wms.platform.common.health.api;

import com.wms.platform.common.security.PublicApi;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.idempotent.IdempotentKey;
import com.wms.platform.common.nacos.NacosServiceRegistrar;
import com.wms.platform.common.health.api.ro.HealthCheckRo;
import com.wms.platform.common.health.api.vo.HealthCheckVo;
import com.wms.platform.common.health.api.vo.HealthVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 骨架自检接口（health 域）。
 *
 * <p>用途：验证骨架的横切能力已生效 —— 统一响应、traceId 透传、参数校验、错误码、幂等键强制校验。
 * **W3 实现真实业务接口后可删除 {@code /check}**；{@code /health} 保留供探针使用。
 *
 * <p>规范落地：
 * <ul>
 *   <li><b>B2</b>：类上 {@code @Validated}；请求体 {@code @Valid @RequestBody}；
 *       入参用 Ro、出参用 Vo，**不使用 {@code Map} 收发**；</li>
 *   <li><b>B3</b>：Ro/Vo 落在 {@code api.ro} / {@code api.vo} 包并按后缀命名；</li>
 *   <li><b>B9</b>：{@code @RequiredArgsConstructor} + {@code private final} 构造注入
 *       （{@code Environment} 读取应用名，替代字段 {@code @Value}）。</li>
 * </ul>
 */
@Validated
@PublicApi("健康探针：供 CI/监控无凭据调用，不得暴露任何业务数据")
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    /** 触发演示业务异常的保留字（仅本类使用，按 B6 留在类内不外提）。 */
    private static final String PAYLOAD_BOOM = "boom";

    /** 运行环境：用于读取 {@code spring.application.name}（构造注入，非字段注入）。 */
    private final Environment environment;

    /** Nacos 注册器：把注册状态暴露给探针（注册失败不影响启动，但必须看得见）。 */
    private final NacosServiceRegistrar nacosServiceRegistrar;

    /**
     * 健康检查（探针使用）。
     *
     * @return 统一响应包裹的健康状态 Vo
     */
    @GetMapping
    public ApiResponse<HealthVo> health() {
        // 1. 读取应用名（缺省值兜底，避免探针因配置缺失而 500）
        String application = environment.getProperty("spring.application.name", "unknown");
        // 2. 组装 Vo 返回（B2：不用 Map 承载响应）
        //    带上 Nacos 注册状态：注册失败时应用会**故意**继续启动（降级），若不暴露就会出现
        //    "服务在跑、但服务发现里找不到它"的静默故障
        return ApiResponse.ok(HealthVo.of(application, "UP", LocalDateTime.now().toString(),
                nacosServiceRegistrar.status()));
    }

    /**
     * 幂等键与异常链路自检：缺少 {@code Idempotency-Key} 时应返回 400 + 10001。
     * 该接口**不落库**，仅验证入口链路。
     *
     * @param ro 自检请求体
     * @return 统一响应包裹的回显 Vo
     */
    @PostMapping("/check")
    @IdempotentKey("HEALTH_CHECK")
    public ApiResponse<HealthCheckVo> check(@Valid @RequestBody HealthCheckRo ro) {
        // 1. 演示业务异常 → HTTP 200 + 业务码（03 §1.2 的契约形态）
        if (PAYLOAD_BOOM.equals(ro.payload())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "演示业务异常：payload 不允许为 boom");
        }
        // 2. 正常回显
        return ApiResponse.ok(HealthCheckVo.of(ro.payload()));
    }
}
