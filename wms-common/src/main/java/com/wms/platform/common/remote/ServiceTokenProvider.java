package com.wms.platform.common.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.constant.HeaderConst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 服务令牌提供者（P3.4 §54.2；服务间后台任务的身份来源）。
 *
 * <p><b>解决什么问题</b>：后台任务（定时对账、兜底扫描）没有用户请求上下文，无法转发用户令牌；
 * 而"内部令牌 + 身份头"的权限集为空，会被被调方的 {@code @RequiresPermission} 拒绝（实测 403）。
 * 这里用**内部凭据**去换一张**服务账号**的令牌（权限来自该账号的 RBAC 角色）。
 *
 * <p><b>三条实现约束（都不是可选项）</b>：
 * <ol>
 *   <li><b>缓存 + 提前续期</b>：服务令牌 TTL 短（默认 30 分钟），但不能每次调用都去换——
 *       按 {@code TTL - 60s} 缓存，既省调用又留出续期余量；</li>
 *   <li><b>单飞（single-flight）</b>：并发任务同时发现过期时，只允许一个线程去换，
 *       其余等待复用结果——否则会出现"令牌刷新风暴"（20 个任务 → 20 次签发）；</li>
 *   <li><b>取不到就明确失败</b>：抛业务异常，**绝不**退化成"内部令牌 + 无权限"再去撞 403——
 *       那样只会把"身份没配好"伪装成"权限不够"，排查要多绕一圈。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：本类持有的是**内部凭据**（{@code wms.security.gateway-internal-token}），
 * 它只用于"换令牌"这一件事；服务令牌本身权限很小（由 {@code svc-*} 账号的角色决定），且可随时撤权。
 */
@Slf4j
@Component
public class ServiceTokenProvider {

    /** 提前续期余量：剩余不足它就重新换一张，避免"刚拿到就过期"。 */
    private static final Duration RENEW_AHEAD = Duration.ofSeconds(60);

    /** 配置。 */
    private final WmsProperties wmsProperties;

    /** HTTP 客户端（指向鉴权服务）。 */
    private final RestClient restClient;

    /** JSON。 */
    private final ObjectMapper objectMapper;

    /** 单飞锁：多个任务同时发现过期时，只有一个去换。 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** 缓存的服务令牌。 */
    private volatile String cachedToken;

    /** 缓存到期时刻（已含提前续期余量）。 */
    private volatile Instant cachedUntil = Instant.EPOCH;

    /**
     * 构造。
     *
     * @param remoteClientFactory 客户端工厂
     * @param wmsProperties       配置（内部凭据）
     * @param objectMapper        JSON
     * @param baseUrl             鉴权服务基址（默认按服务名解析）
     */
    public ServiceTokenProvider(RemoteClientFactory remoteClientFactory,
                                WmsProperties wmsProperties,
                                ObjectMapper objectMapper,
                                @Value("${wms.remote.auth-base-url:http://wms-auth}") String baseUrl) {
        this.restClient = remoteClientFactory.create(baseUrl);
        this.wmsProperties = wmsProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 取服务令牌（命中缓存直接返回）。
     *
     * @return 服务令牌（compact 形式）
     */
    public String token() {
        String current = cachedToken;
        if (current != null && Instant.now().isBefore(cachedUntil)) {
            return current;
        }
        refreshLock.lock();
        try {
            // 双检：可能已被其它线程刷新
            String refreshed = cachedToken;
            if (refreshed != null && Instant.now().isBefore(cachedUntil)) {
                return refreshed;
            }
            return doRefresh();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 真正去换一张服务令牌。
     *
     * @return 令牌
     */
    @SuppressWarnings("unchecked")
    private String doRefresh() {
        String internalToken = wmsProperties.getSecurity().getGatewayInternalToken();
        if (internalToken == null || internalToken.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "未配置内部凭据（wms.security.gateway-internal-token），无法换取服务令牌");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderConst.GATEWAY_TOKEN, internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 幂等键：换令牌本身是幂等的，带上它可穿过幂等拦截器（也便于排查重复调用）
        headers.set(HeaderConst.IDEMPOTENCY_KEY, "SVC-TOKEN-" + (System.currentTimeMillis() / 1000));
        log.info("[服务令牌] 向鉴权服务换取服务令牌（账号 {}）", wmsProperties.getSecurity().getServiceAccountUsername());
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri("/api/v1/auth/service-token")
                .headers(h -> h.addAll(headers))
                .body(toJson(Map.of()))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "换取服务令牌失败："
                    + (response == null ? "无响应" : "code=" + response.getCode() + " msg=" + response.getMessage()));
        }
        Object token = response.getData().get("token");
        if (token == null || String.valueOf(token).isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "换取服务令牌失败：响应中没有 token");
        }
        long ttlMinutes = wmsProperties.getSecurity().getServiceTokenTtlMinutes();
        this.cachedToken = String.valueOf(token);
        this.cachedUntil = Instant.now().plus(Duration.ofMinutes(ttlMinutes)).minus(RENEW_AHEAD);
        log.info("[服务令牌] 已换取并缓存服务令牌，有效期 {} 分钟（提前 {} 秒续期）", ttlMinutes, RENEW_AHEAD.toSeconds());
        return cachedToken;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param body 载荷
     * @return JSON
     */
    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "服务令牌请求体序列化失败", ex);
        }
    }
}
