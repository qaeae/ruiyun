package com.wms.platform.common.security;

import com.wms.platform.common.api.ApiErrorWriter;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.security.token.AccessToken;
import com.wms.platform.common.security.token.Hs256TokenCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 授权拦截器：只校验带 {@link RequiresPermission} 的接口。
 *
 * <p><b>身份来源有两条路（P1.3 引入"网关感知"）</b>：
 * <ol>
 *   <li><b>网关注入</b>（推荐路径）：请求带内部凭据 {@code X-Gateway-Token} 且与配置一致时，
 *       直接信任 {@code X-User-Id}/{@code X-Tenant-Id}/{@code X-User-Permissions} 等身份头。
 *       网关已经验过签、查过过期，这里**不再验签、不再查库**——少一次加解密、少一次数据库往返。</li>
 *   <li><b>本地验签</b>（直连路径）：没有内部凭据时，按 {@code Authorization: Bearer} 自行验签，
 *       权限取自令牌声明。这条路径保留给"本地不开网关直接跑服务"与集成测试（MockMvc），
 *       行为与路径 ① 一致（同一份 {@link AccessToken} 语义、同一套授权判断）。</li>
 * </ol>
 *
 * <p><b>为什么两条路都要有、且安全上等价</b>：只有一条"信头"的路径时，绕过网关直连服务端口就等于
 * 免鉴权；只有一条"验签"路径时，每个服务都要持有密钥并重复做网关已做过的事。
 * 同时满足二者，且用**内部凭据**区分"头是谁写的"，才能既省开销又不被伪造。
 * （防伪造的另一半在 {@code GatewayHeaderGuardFilter}：带身份头却没有合法内部凭据的请求会被直接拒绝。）
 *
 * <p><b>权限快照语义</b>：权限来自令牌/网关头，因此"撤权"在**令牌刷新后**生效——
 * 这是已登记的取舍（见 {@code RbacIT} 的用例）。要改成实时生效就得每个请求查库，
 * 那会把鉴权变成数据库热点；资金类敏感操作另有双人复核兜底。
 *
 * <p><b>状态码口径</b>（03 只定义了业务码 {@code 40300}，这里把 HTTP 语义补齐并登记）：
 * <ul>
 *   <li>未带令牌 / 令牌非法 / 已过期 → <b>HTTP 401</b> + {@code 40300}（未认证）；</li>
 *   <li>已认证但缺权限 → <b>HTTP 403</b> + {@code 40300}（无权限）。</li>
 * </ul>
 * 区分二者的价值：前端遇到 401 应跳登录、403 应提示"找管理员要权限"，混成一个码会让用户反复重登也没用。
 */
@Component
@RequiredArgsConstructor
public class AuthorizationInterceptor implements HandlerInterceptor {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AuthorizationInterceptor.class);

    /** Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 安全配置（提供内部凭据与令牌密钥）。 */
    private final WmsProperties wmsProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        // 1. 只处理 Controller 方法
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 2. 取权限声明（方法优先，其次类）
        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null) {
            // 未声明权限的接口：不在此处拦（由 ArchUnit 规则保证"写接口必须声明"）
            return true;
        }
        // 3. 解析身份：网关注入优先，否则本地验签
        AccessToken accessToken = resolveIdentity(request);
        if (accessToken == null) {
            log.warn("未认证，拒绝访问 path={}", request.getRequestURI());
            ApiErrorWriter.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.FORBIDDEN,
                    "缺少有效身份（网关身份头或 Authorization: Bearer <token>）");
            return false;
        }
        // 4. 授权：任一权限满足即可（超管在 AccessToken#hasPermission 内直接放行）
        Set<String> required = new LinkedHashSet<>(Arrays.asList(annotation.value()));
        boolean granted = required.isEmpty() || required.stream().anyMatch(accessToken::hasPermission);
        if (!granted) {
            log.warn("权限不足 userId={} required={} owned={}", accessToken.userId(), required,
                    accessToken.permissions().size());
            ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "无权限执行该操作，需要权限：" + String.join(" 或 ", required));
            return false;
        }
        // 5. 写入上下文：后续业务代码（审计、数据权限）都从这里取身份，不再信任请求体
        TenantContext.set(accessToken.tenantId(), accessToken.userId(), accessToken.warehouseIds(),
                request.getHeader(HeaderConst.DEVICE_CODE));
        request.setAttribute(AttributeNames.ACCESS_TOKEN, accessToken);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // 请求结束清理 ThreadLocal：线程池复用线程时，残留身份会被下一个请求读到（串号）
        TenantContext.clear();
    }

    /**
     * 解析调用方身份（网关身份头优先，否则本地验签）。
     *
     * @param request 请求
     * @return 身份；无法确认返回 {@code null}
     */
    private AccessToken resolveIdentity(HttpServletRequest request) {
        // 路径 ①：网关注入——内部凭据匹配才信任身份头
        String expected = wmsProperties.getSecurity().getGatewayInternalToken();
        String actual = request.getHeader(HeaderConst.GATEWAY_TOKEN);
        if (expected != null && !expected.isBlank() && expected.equals(actual)) {
            return fromGatewayHeaders(request);
        }
        // 路径 ②：本地验签
        String compact = extractToken(request);
        if (compact == null) {
            return null;
        }
        try {
            return new Hs256TokenCodec().decode(compact, wmsProperties.getSecurity().getTokenSecret());
        } catch (BizException ex) {
            log.warn("令牌校验失败 path={} reason={}", request.getRequestURI(), ex.getMessage());
            return null;
        }
    }

    /**
     * 从网关注入的身份头还原身份声明。
     *
     * <p>此处**不做验签**：能走到这里说明内部凭据已匹配（即请求来自网关，而不是客户端自己编的头）。
     *
     * @param request 请求
     * @return 身份；头缺失或格式非法返回 {@code null}
     */
    private AccessToken fromGatewayHeaders(HttpServletRequest request) {
        Long userId = toLong(request.getHeader(HeaderConst.USER_ID));
        Long tenantId = toLong(request.getHeader(HeaderConst.TENANT_ID));
        if (userId == null || tenantId == null) {
            log.warn("网关注入的身份头不完整（userId/tenantId 缺失），拒绝访问 path={}", request.getRequestURI());
            return null;
        }
        return new AccessToken(userId, tenantId,
                request.getHeader(HeaderConst.USERNAME) == null ? "" : request.getHeader(HeaderConst.USERNAME),
                Boolean.parseBoolean(request.getHeader(HeaderConst.USER_ADMIN)),
                toSet(request.getHeader(HeaderConst.USER_ROLES)),
                toSet(request.getHeader(HeaderConst.USER_PERMISSIONS)),
                toLongSet(request.getHeader(HeaderConst.WAREHOUSE_IDS)),
                toInstant(request.getHeader(HeaderConst.TOKEN_ISSUED_AT)),
                toInstant(request.getHeader(HeaderConst.TOKEN_EXPIRES_AT)));
    }

    /**
     * 从请求头取令牌。
     *
     * @param request 请求
     * @return 令牌，未携带返回 {@code null}
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HeaderConst.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 逗号分隔字符串 → 集合。
     *
     * @param value 头值
     * @return 集合（空值返回空集合）
     */
    private Set<String> toSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(value.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
    }

    /**
     * 逗号分隔数字 → 集合。
     *
     * @param value 头值
     * @return 数字集合（空值返回空集合）
     */
    private Set<Long> toLongSet(String value) {
        Set<Long> result = new LinkedHashSet<>();
        for (String item : toSet(value)) {
            Long id = toLong(item);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * 解析 Long（失败返回 {@code null}，不抛异常：头是外部输入，格式错应走"身份不可用"而不是 500）。
     *
     * @param value 头值
     * @return 数值或 {@code null}
     */
    private Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * epoch 毫秒 → Instant。
     *
     * @param value 头值
     * @return 时间或 {@code null}
     */
    private Instant toInstant(String value) {
        Long millis = toLong(value);
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    /** 请求属性名（供 Controller 取当前身份声明）。 */
    public static final class AttributeNames {

        /** 当前访问令牌/身份声明。 */
        public static final String ACCESS_TOKEN = "wms.accessToken";

        private AttributeNames() {
        }
    }
}
