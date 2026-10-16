package com.wms.platform.common.constant;

/**
 * HTTP 请求/响应头常量（规范条目 B6：跨模块共享常量集中在常量接口，禁止散落魔法值）。
 *
 * <p>说明：常量类一律声明为 <b>interface</b>（外部规范的原生风格），字段隐式 {@code public static final}，
 * 且不提供实现——仅作常量载体。
 */
public interface HeaderConst {

    /** 幂等键请求头（写接口必传，见 03-接口定义.md §1.3）。 */
    String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** 幂等键最大长度，与 {@code pf_idempotent.idem_key VARCHAR(96)} 对齐。 */
    int IDEMPOTENCY_KEY_MAX_LENGTH = 96;

    /** 全链路追踪头：调用方可传入以串联跨系统日志，服务端缺失时本地生成。 */
    String TRACE_ID = "X-Trace-Id";

    /**
     * 设备编号请求头（PDA/扫码枪等现场设备必传）。
     * 服务端据此做设备绑定校验（03 §1.1），并写入审计与库存流水的 {@code device_code}。
     */
    String DEVICE_CODE = "X-Device-Code";

    /**
     * 认证令牌请求头（RFC 6750 风格：{ Authorization: Bearer <token>}）。
     * 03 API-AUTH-01 登录后由前端带入；服务端由 { AuthorizationInterceptor} 校验。
     */
    String AUTHORIZATION = "Authorization";

    /** 外部回调验签头：签名值。 */
    String SIGN = "X-Sign";

    /** 外部回调验签头：时间戳（毫秒），偏差超过 5 分钟拒绝。 */
    String TIMESTAMP = "X-Timestamp";

    /** 外部回调验签头：随机串，5 分钟内不可重复（防重放）。 */
    String NONCE = "X-Nonce";

    // ==========================================================================================
    // 网关身份头（P1.3）：网关验签令牌后，把身份**声明**注入给下游服务。
    //
    // 为什么把这些常量放在共享内核而不是各写各的：
    //   网关（WebFlux 应用）与业务服务（Servlet 应用）各写一份字符串，只要有一处拼错
    //   （大小写、复数、下划线），症状就是"鉴权突然不生效"或"权限老是 403"，
    //   而且**编译期完全看不出来**。放在同一个常量类里，两边引同一个字面量。
    //
    // 安全前提：这些头**只有网关能注入**，服务侧用 {@code X-Gateway-Token}（内部凭据）判断；
    // 客户端自己带同名头会被网关清除（见网关的 GatewayAuthFilter）。
    // ==========================================================================================

    /** 身份头：用户 ID。 */
    String USER_ID = "X-User-Id";

    /** 身份头：租户 ID。 */
    String TENANT_ID = "X-Tenant-Id";

    /** 身份头：可见仓库 ID（逗号分隔，空表示全部）。 */
    String WAREHOUSE_IDS = "X-Warehouse-Ids";

    /** 身份头：是否超管（true/false）。 */
    String USER_ADMIN = "X-User-Admin";

    /** 身份头：登录名（/auth/me 回显用）。 */
    String USERNAME = "X-Username";

    /** 身份头：权限码（逗号分隔）——服务据此做授权，无需再查库或验签。 */
    String USER_PERMISSIONS = "X-User-Permissions";

    /** 身份头：角色码（逗号分隔）。 */
    String USER_ROLES = "X-User-Roles";

    /** 身份头：令牌签发时间（epoch 毫秒）。 */
    String TOKEN_ISSUED_AT = "X-Token-Issued-At";

    /** 身份头：令牌过期时间（epoch 毫秒）。 */
    String TOKEN_EXPIRES_AT = "X-Token-Expires-At";

    /** 内部凭据头：证明"上面的身份头是网关注入的"。 */
    String GATEWAY_TOKEN = "X-Gateway-Token";

    /** 来源标记头：便于审计与排查（取值如 {@code gateway}）。 */
    String AUTH_SOURCE = "X-Auth-Source";
}
