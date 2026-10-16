package com.wms.platform.gateway.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.nacos.NacosServiceRegistrar;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关健康端点（WebFlux）。
 *
 * <p><b>为什么路径是 {@code /healthz} 而不是 {@code /api/v1/health}</b>：
 * 网关把所有 {@code /api/**} 路由到后端服务，所以 {@code /api/v1/health} 返回的是**后端**的健康，
 * 不是网关自己的。探针要判断"网关本身是否活着"，必须走一个不被路由接管的路径。
 *
 * <p>返回 {@code Map} 是有意的例外（内核的 ApiResponse 支持泛型，但此处字段随环境变化，
 * 且仅用于探针；业务接口仍遵守"不用 Map 收发"的规范条目 B2）。
 */
@RestController
@RequiredArgsConstructor
public class GatewayHealthController {

    /** 时间格式（与业务服务的健康检查口径一致）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 运行环境。 */
    private final Environment environment;

    /** Nacos 注册状态（注册失败时也能看出来）。 */
    private final NacosServiceRegistrar nacosServiceRegistrar;

    /**
     * 健康检查。
     *
     * @return 统一响应包裹的健康信息
     */
    @GetMapping("/healthz")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("application", environment.getProperty("spring.application.name", "wms-gateway"));
        body.put("status", "UP");
        body.put("serverTime", LocalDateTime.now().format(TIME_FORMAT));
        body.put("nacos", nacosServiceRegistrar.status());
        return ApiResponse.ok(body);
    }
}
