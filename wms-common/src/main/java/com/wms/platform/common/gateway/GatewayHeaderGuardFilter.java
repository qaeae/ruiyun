package com.wms.platform.common.gateway;

import com.wms.platform.common.api.ApiErrorWriter;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.config.WmsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 身份头防伪造过滤器：只接受**网关注入**的身份头。
 *
 * <p><b>防的是什么（网关架构里最经典的越权漏洞）</b>：网关会把
 * {@code X-User-Id}/{@code X-Tenant-Id}/{@code X-Warehouse-Ids}/{@code X-User-Admin} 注入给业务服务。
 * 如果业务服务无条件信任这些头，那么调用方只要绕开网关（直连服务端口，或经网关时自己塞一份同名头）
 * 就能**伪装成任意用户甚至超管**。
 *
 * <p><b>判定规则</b>：
 * <ul>
 *   <li>配置了 {@code wms.security.gateway-internal-token} 时：请求带身份头但 {@code X-Gateway-Token}
 *       不匹配 → **403 拒绝**（宁可拒绝，也不要"看起来正常实则被伪造"）；</li>
 *   <li>未配置内部凭据时（例如本地直接跑、没起网关）：**剥掉**这些头并放行，同时启动时告警一次——
 *       此时身份只来自服务自身的令牌校验，不受头影响。</li>
 * </ul>
 *
 * <p><b>为什么单独做过滤器而不是塞进鉴权拦截器</b>：鉴权拦截器只处理声明了 {@code @RequiresPermission}
 * 的接口；而"信任伪造身份头"的风险存在于**所有**接口（包括只读接口的数据越权）。
 */
@Slf4j
@RequiredArgsConstructor
public class GatewayHeaderGuardFilter extends OncePerRequestFilter {

    /** 网关身份头（与网关侧 {@code GatewayConfig} 常量保持一致）。 */
    private static final String HEADER_USER_ID = "X-User-Id";

    /** 租户身份头。 */
    private static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** 仓库范围身份头。 */
    private static final String HEADER_WAREHOUSE_IDS = "X-Warehouse-Ids";

    /** 超管标记头。 */
    private static final String HEADER_ADMIN = "X-User-Admin";

    /** 来源标记头。 */
    private static final String HEADER_AUTH_SOURCE = "X-Auth-Source";

    /** 内部凭据头。 */
    private static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";

    /** 安全配置（含内部凭据）。 */
    private final WmsProperties wmsProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 1. 判断本次请求是否声称"来自网关身份注入"
        boolean claimsGateway = hasAnyHeader(request);
        if (!claimsGateway) {
            chain.doFilter(request, response);
            return;
        }
        // 2. 取配置的内部凭据
        String expected = wmsProperties.getSecurity().getGatewayInternalToken();
        if (expected == null || expected.isBlank()) {
            // 未启用防伪造校验：剥掉身份头（服务不信任它们），继续按自己的令牌校验走
            chain.doFilter(new StrippedRequest(request), response);
            return;
        }
        // 3. 启用校验：凭据不匹配则直接拒绝
        String actual = request.getHeader(HEADER_GATEWAY_TOKEN);
        if (!expected.equals(actual)) {
            log.warn("拒绝伪造的身份头：path={} 来源={}（未携带合法的 X-Gateway-Token）",
                    request.getRequestURI(), request.getRemoteAddr());
            ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "身份头只能由网关注入；检测到伪造的 X-User-Id/X-Tenant-Id，已拒绝");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 是否携带任一身份头。
     *
     * @param request 请求
     * @return 携带返回 true
     */
    private boolean hasAnyHeader(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ID) != null
                || request.getHeader(HEADER_TENANT_ID) != null
                || request.getHeader(HEADER_WAREHOUSE_IDS) != null
                || request.getHeader(HEADER_ADMIN) != null
                || request.getHeader(HEADER_AUTH_SOURCE) != null
                || request.getHeader(HEADER_GATEWAY_TOKEN) != null;
    }

    /**
     * 剥掉身份头的请求包装（未启用防伪造校验时使用，避免下游误信）。
     */
    private static final class StrippedRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        /**
         * 构造。
         *
         * @param request 原请求
         */
        private StrippedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isIdentityHeader(name) ? null : super.getHeader(name);
        }

        /**
         * 是否身份头（大小写不敏感）。
         *
         * @param name 头名
         * @return 是身份头返回 true
         */
        private boolean isIdentityHeader(String name) {
            return HEADER_USER_ID.equalsIgnoreCase(name) || HEADER_TENANT_ID.equalsIgnoreCase(name)
                    || HEADER_WAREHOUSE_IDS.equalsIgnoreCase(name) || HEADER_ADMIN.equalsIgnoreCase(name)
                    || HEADER_AUTH_SOURCE.equalsIgnoreCase(name) || HEADER_GATEWAY_TOKEN.equalsIgnoreCase(name);
        }
    }
}
