package com.wms.platform.common.remote;

import com.wms.platform.common.config.WmsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.context.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 服务间调用的公共支撑（P3.4）。
 *
 * <p><b>为什么需要它（拆分才出现的新问题）</b>：进程内调用时"身份"是隐式的——
 * 同一个线程里 {@code TenantContext} 已经有了。拆成两个进程后，被调服务的接口
 * **仍然是受保护的**（{@code @RequiresPermission} + 写接口要 {@code Idempotency-Key}），
 * 而服务间调用**没有用户令牌**。因此必须显式把身份与幂等键带过去，否则会得到 401/400。
 *
 * <p><b>复用已有机制而不是新造</b>：本项目网关就是这么做的——
 * 带内部令牌 {@code X-Gateway-Token} + 身份头，{@code GatewayHeaderGuardFilter} 会
 * 校验令牌、剥掉伪造头；{@code AuthorizationInterceptor} 见到匹配的令牌就直接信任身份头并注入
 * {@code TenantContext}（不再验令牌）。服务间调用沿用同一套，避免出现第二条鉴权路径。
 */
@Component
public class RemoteCallSupport {

    /** 内部令牌头值来源（与网关一致）。 */
    private final WmsProperties wmsProperties;

    /** 服务令牌提供者（后台任务没有用户上下文时用它；内部凭据换服务账号令牌）。 */
    private final ServiceTokenProvider serviceTokenProvider;

    /**
     * 构造。
     *
     * @param wmsProperties 配置
     */
    public RemoteCallSupport(WmsProperties wmsProperties, ServiceTokenProvider serviceTokenProvider) {
        this.wmsProperties = wmsProperties;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    /**
     * 构造服务间调用的请求头：内部令牌 + 当前身份（从 TenantContext 转发）。
     *
     * @return 请求头
     */
    public HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();

        // 1) **用户触发**的调用：转发原请求的 Authorization 令牌。
        //    为什么这样最好：被调方按正常流程验令牌 → **权限来自用户**、审计也归到"是这个人发起的"。
        //    踩坑背景：只带身份头（X-User-Id 等）时身份会被认可，但**权限集为空** →
        //    被 @RequiresPermission 拒绝（实测 403：需要权限 stock:invariant:check）。
        String userToken = currentUserAuthorization();
        if (userToken != null) {
            headers.set(HeaderConst.AUTHORIZATION, userToken);
            return headers;
        }

        // 2) **后台任务触发**（没有用户请求上下文）：用服务账号令牌。
        //    没配服务账号令牌时退化为内部令牌 + 身份头 —— 此时被调方若要求权限会 403，
        //    属于"明确失败"而不是默默放行（安全优先）。
        // 2) 静态配置优先（本地调试/应急）；否则用服务令牌提供者（缓存 + 单飞 + 提前续期）
        String configured = wmsProperties.getSecurity().getServiceToken();
        if (configured != null && !configured.isBlank()) {
            headers.set(HeaderConst.AUTHORIZATION, "Bearer " + configured);
            return headers;
        }
        String issued = serviceTokenProvider.token();
        headers.set(HeaderConst.AUTHORIZATION, "Bearer " + issued);
        return headers;

    }

    /**
     * 取当前 HTTP 请求的 Authorization 头（没有请求上下文时返回 null）。
     *
     * @return Authorization 头原值；无则 null
     */
    private String currentUserAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(HeaderConst.AUTHORIZATION);
            return authorization == null || authorization.isBlank() ? null : authorization;
        }
        return null;
    }

    /**
     * 派生幂等键（服务间调用也必须带：重试不能产生第二笔业务）。
     *
     * <p>用"业务单号 + 载荷哈希"而不是随机值：这样**同一次业务动作的重试天然命中同一个键**，
     * 下游幂等拦住重复；不同动作则不会互相顶掉。
     *
     * @param bizNo       业务单号
     * @param payloadJson 载荷
     * @return 幂等键（≤96 字符，符合 {@code Idempotency-Key} 约束）
     */
    public String idempotencyKey(String bizNo, String payloadJson) {
        String raw = (bizNo == null ? "-" : bizNo) + "|" + (payloadJson == null ? "-" : payloadJson);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
            String key = "SVC-" + (bizNo == null ? "-" : bizNo) + "-" + hash.substring(0, 16);
            return key.length() <= 96 ? key : key.substring(0, 96);
        } catch (Exception ex) {
            throw new com.wms.platform.common.api.BizException(
                    com.wms.platform.common.api.ErrorCode.SYSTEM_ERROR, "生成服务间幂等键失败", ex);
        }
    }
}
