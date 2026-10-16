package com.wms.platform.gateway.filter;

import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.security.token.AccessToken;
import com.wms.platform.gateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 网关鉴权过滤器（统一入口的第一道防线）。
 *
 * <p><b>做四件事</b>：
 * <ol>
 *   <li><b>公开路径直接放行</b>：健康探针、登录/刷新、外部回调（第三方不带我们的令牌）；</li>
 *   <li><b>验签令牌</b>：非法/过期直接 401 + 统一响应体，**不把无效流量打到业务服务**；</li>
 *   <li><b>注入身份头</b>：{@code X-User-Id}/{@code X-Tenant-Id}/{@code X-Warehouse-Ids}/{@code X-User-Admin}
 *       + 内部凭据 {@code X-Gateway-Token}；</li>
 *   <li><b>清除客户端伪造的同类头</b>：否则调用方自己带一个 {@code X-User-Id: 1} 就能冒充超管——
 *       这是网关架构里最典型的越权漏洞。</li>
 * </ol>
 *
 * <p><b>与业务服务的关系（P1 采用双保险）</b>：网关注入身份头，同时**原样透传 {@code Authorization}**，
 * 业务服务侧仍按原有方式验签与校验权限。这样迁移期不会出现"网关漏配一个路径就裸奔"的风险；
 * 待 P1.2 完成"服务只信网关注入头"的切换后，可去掉服务侧的令牌验签（届时靠 {@code X-Gateway-Token} 防伪造）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    /** 请求属性名：鉴权通过的令牌（供限流 KeyResolver 复用，避免重复验签）。 */
    public static final String ATTR_ACCESS_TOKEN = "wms.accessToken";

    /** 无需令牌的路径（与业务侧 {@code WebInterceptorConfig} 的豁免清单保持一致）。 */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/health",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/me",
            "/api/v1/callback");

    /** 所有身份头（客户端一律不许自带，网关统一注入/清除）。 */
    private static final List<String> IDENTITY_HEADERS = List.of(
            HeaderConst.USER_ID, HeaderConst.TENANT_ID, HeaderConst.WAREHOUSE_IDS, HeaderConst.USER_ADMIN,
            HeaderConst.USERNAME, HeaderConst.USER_PERMISSIONS, HeaderConst.USER_ROLES,
            HeaderConst.TOKEN_ISSUED_AT, HeaderConst.TOKEN_EXPIRES_AT, HeaderConst.AUTH_SOURCE,
            HeaderConst.GATEWAY_TOKEN);

    /** 令牌前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 过滤器顺序：早于路由转发，晚于 TraceId（保证 401 响应也带 traceId）。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /** 网关配置。 */
    private final GatewayProperties properties;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 公开路径：清掉伪造头后放行（公开路径也不需要身份头）
        if (isPublic(path)) {
            return chain.filter(exchange.mutate().request(stripSpoofedHeaders(request)).build());
        }

        // 2. 验签令牌
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String compact = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim() : null;
        AccessToken token = properties.decodeQuietly(compact);
        if (token == null) {
            // 未认证：401（与业务侧口径一致，错误码复用 FORBIDDEN=40300 的语义会在 03 文档里登记）
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "未认证或令牌无效/已过期（网关拦截，未转发到业务服务）");
        }

        // 3. 注入身份头（并清除客户端传入的同类头，防伪造）
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    // 先清除客户端可能伪造的全部身份头，再注入真实值（顺序不能反）
                    for (String name : IDENTITY_HEADERS) {
                        headers.remove(name);
                    }
                    headers.set(HeaderConst.USER_ID, String.valueOf(token.userId()));
                    headers.set(HeaderConst.TENANT_ID, String.valueOf(token.tenantId()));
                    headers.set(HeaderConst.WAREHOUSE_IDS, joinWarehouses(token));
                    headers.set(HeaderConst.USER_ADMIN, String.valueOf(token.adminFlag()));
                    headers.set(HeaderConst.USERNAME, token.username() == null ? "" : token.username());
                    // 权限与角色也一并下传：服务据此授权，**不必再验签、不必再查库**（P1.3）
                    headers.set(HeaderConst.USER_PERMISSIONS, join(token.permissions()));
                    headers.set(HeaderConst.USER_ROLES, join(token.roleCodes()));
                    if (token.issuedAt() != null) {
                        headers.set(HeaderConst.TOKEN_ISSUED_AT, String.valueOf(token.issuedAt().toEpochMilli()));
                    }
                    if (token.expiresAt() != null) {
                        headers.set(HeaderConst.TOKEN_EXPIRES_AT, String.valueOf(token.expiresAt().toEpochMilli()));
                    }
                    headers.set(HeaderConst.AUTH_SOURCE, "gateway");
                    if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank()) {
                        headers.set(HeaderConst.GATEWAY_TOKEN, properties.getInternalToken());
                    }
                })
                .build();

        if (properties.isAuthLogEnabled()) {
            log.info("[网关鉴权] 通过 userId={} tenantId={} path={}", token.userId(), token.tenantId(), path);
        }
        // 令牌放进 exchange 属性供限流 KeyResolver 复用（Builder 没有 attribute(...) 方法；
        // mutate() 出来的 exchange 与原对象共享同一个 attributes 视图，因此这样写是等效且更直观的）
        exchange.getAttributes().put(ATTR_ACCESS_TOKEN, token);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 是否公开路径（前缀匹配）。
     *
     * @param path 请求路径
     * @return 公开返回 true
     */
    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 去掉客户端伪造的身份头（公开路径也去，避免业务侧误信）。
     *
     * @param request 请求
     * @return 处理后的请求
     */
    private ServerHttpRequest stripSpoofedHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            for (String name : IDENTITY_HEADERS) {
                headers.remove(name);
            }
        }).build();
    }

    /**
     * 集合拼接为逗号分隔字符串。
     *
     * @param values 集合
     * @return 字符串（空集合返回空串）
     */
    private String join(java.util.Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    /**
     * 仓库列表拼接（空表示全部可见，超管语义）。
     *
     * @param token 令牌
     * @return 逗号分隔的仓库 ID
     */
    private String joinWarehouses(AccessToken token) {
        Set<Long> warehouses = token.warehouseIds();
        if (warehouses == null || warehouses.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Long id : warehouses) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    /**
     * 提前拒绝：输出统一响应结构（与业务侧的 {@code {code,message,data,traceId}} 保持一致）。
     *
     * @param exchange 交换对象
     * @param status   HTTP 状态
     * @param message  提示
     * @return 响应完成信号
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        String body = "{\"code\":" + ErrorCode.FORBIDDEN.getCode() + ",\"message\":\"" + message
                + "\",\"data\":null,\"traceId\":\"" + (traceId == null ? "" : traceId) + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
