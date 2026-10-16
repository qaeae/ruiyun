package com.wms.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.idempotent.IdempotencyInterceptor;
import com.wms.platform.common.security.AuthorizationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公共 Web 拦截器装配：**幂等键校验**（所有 Servlet 服务共用）。
 *
 * <p><b>为什么从原来的 {@code WebInterceptorConfig} 拆出来（P1.2）</b>：拆分服务时发现原来的装配类
 * 同时依赖 au（授权拦截器）与 md（设备校验）两个业务域。如果整类留在 `wms-core`，
 * 那么拆出去的 `wms-auth`/`wms-masterdata` 就会**没有任何拦截器**——接口裸奔（该要幂等键的不要了、
 * 该鉴权的不鉴权了）。所以按"谁能提供服务"重新归属：
 * <ul>
 *   <li>幂等（{@code wms-common}）→ 本类；</li>
 *   <li>鉴权 → 本类。P1.3 后拦截器已移到 {@code wms-common}（网关感知、不依赖 au 域），
 *       因此所有服务都自动获得鉴权，md/ops 不再需要为了权限码而依赖 au；</li>
 *   <li>设备绑定（需要 md 的 DeviceService）→ {@code MdWebConfig}。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class CommonWebConfig implements WebMvcConfigurer {

    /** 外部回调路径：用验签 + 业务键保证幂等，不要求 Idempotency-Key（03 §六）。 */
    private static final String[] CALLBACK_PATHS = {"/api/v1/callback/**"};

    /**
     * 不要求 {@code Idempotency-Key} 请求头的路径（除回调外）。
     *
     * <p><b>为什么 PDA 批量补传要豁免（P3 决策）</b>：该接口的幂等粒度是**逐条明细**——
     * 每条补传都带 {@code deviceCode:taskId:action:seq} 幂等键（{@code 01} §6），
     * 服务端逐条返回 {@code accepted/duplicated/conflict/rejected}。若在批次层再要求一个幂等键，
     * PDA 重投同一批时会命中批次级重放，**直接返回第一次的整批响应**（全是 {@code accepted}），
     * 于是现场看不到"这条其实早就同步过了"，冲突/拒绝结果也被历史响应掩盖。
     * 豁免后**不会重复写入**：逐条幂等键 + {@code iv_movement.uk_movement_idem}
     * + {@code pf_pda_conflict.uk_pda_idem} 三层防线照常生效（PoC-3 实测：重复补传 3 次全部 {@code duplicated}）。
     */
    private static final String[] BATCH_IDEMPOTENT_EXEMPT_PATHS = {"/api/v1/pda/sync/batch"};

    /**
     * 会话建立前的接口：**不要求 {@code Idempotency-Key}**。
     *
     * <p>冲突：{@code /api/**} 写接口一律要求幂等键，而登录/刷新/查当前身份发生在"客户端还没有会话"
     * 或"令牌本身就是唯一凭证"的阶段——调用方无从生成业务幂等键，强行要求只会让前端随便塞个 UUID
     * （把防线变成形式主义）。这三个接口不产生业务副作用；管理类写接口（建用户/建角色/授权）**照旧要求**。
     */
    private static final String[] AUTH_PRE_SESSION_EXEMPT_PATHS = {
            // 服务间后台任务用内部凭据换服务令牌：属"会话前"接口，与 login 同类（README §54.5）
            "/api/v1/auth/service-token","/api/v1/auth/login", "/api/v1/auth/refresh",
            "/api/v1/auth/me"};

    /** JSON：幂等拦截器校验请求体时使用 Spring 的 ObjectMapper，保证与业务序列化口径一致。 */
    private final ObjectMapper objectMapper;

    /** 授权拦截器（P1.3 起在 common：网关感知，不再依赖 au 域）。 */
    private final AuthorizationInterceptor authorizationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 授权拦截器：**最先执行**——身份没确定之前做别的校验（幂等、设备绑定）都没有意义，
        // 而且它负责把 tenantId/userId/warehouseIds 写进 TenantContext，供后续拦截器与业务代码使用
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
        // 幂等拦截器：覆盖全部 /api 写接口；放行外部回调、PDA 批量补传与登录前接口（理由见常量注释）
        registry.addInterceptor(new IdempotencyInterceptor(objectMapper))
                .addPathPatterns("/api/**")
                .excludePathPatterns(CALLBACK_PATHS)
                .excludePathPatterns(BATCH_IDEMPOTENT_EXEMPT_PATHS)
                .excludePathPatterns(AUTH_PRE_SESSION_EXEMPT_PATHS)
                .order(1);
    }
}
