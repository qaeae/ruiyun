package com.wms.platform.common.security.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 紧凑型签名令牌（HS256 结构：{@code base64url(payload).base64url(hmac)}）。
 *
 * <p><b>为什么不用 JWT 库</b>：一期只需要"签发 + 验签 + 过期"三件事。JWT 生态里
 * 最经典的事故恰恰来自"用了库但没校验算法"（伪造 {@code alg:none} 或 RS256→HS256 混淆）。
 * 这里令牌**只有一种算法、算法不写在令牌里**（写死在代码里），从结构上消除了算法混淆的可能。
 *
 * <p><b>为什么 payload 不加密只签名</b>：签名保证"没被改过"，不保证"看不到"。
 * 因此 {@link AccessToken} 里刻意不放 PII（见其类注释）。
 *
 * <p><b>密钥管理</b>：{@code wms.security.tokenSecret} 必须配置；
 * 生产环境使用默认值会**启动即失败**（与资金通道 MOCK 同一条原则：
 * 不能让"以为安全其实没配"的形态上线）。
 */
@Component
@RequiredArgsConstructor
public class Hs256TokenCodec {

    /** 生产环境禁止使用的默认密钥（故意写成显眼的占位符）。 */
    public static final String DEV_DEFAULT_SECRET = "dev-only-change-me";

    /** 负载字段名。 */
    private static final String FIELD_USER_ID = "uid";

    /** 租户字段。 */
    private static final String FIELD_TENANT_ID = "tid";

    /** 登录名字段。 */
    private static final String FIELD_USERNAME = "sub";

    /** 超管字段。 */
    private static final String FIELD_ADMIN = "adm";

    /** 角色字段。 */
    private static final String FIELD_ROLES = "rol";

    /** 权限字段。 */
    private static final String FIELD_PERMS = "prm";

    /** 仓库字段。 */
    private static final String FIELD_WAREHOUSES = "whs";

    /** 签发时间字段。 */
    private static final String FIELD_ISSUED_AT = "iat";

    /** 过期时间字段。 */
    private static final String FIELD_EXPIRES_AT = "exp";

    /**
     * 令牌载荷专用 JSON（**刻意不注入 Spring 的 ObjectMapper**）。
     *
     * <p>原因（2026-10-14 联调踩到）：令牌载荷是**内部格式**，不是对外 HTTP 契约。
     * 我们把 HTTP 响应里的 {@code Long} 统一序列化成字符串（雪花 ID 超出 JS 安全整数），
     * 若这里复用同一个 ObjectMapper，令牌载荷里的 {@code uid}/{@code tid} 也会变成字符串，
     * 而解析端按 {@code (Number)} 取值 → {@code ClassCastException} → "令牌内容无法解析"，
     * **所有已登录用户一起掉线**。
     *
     * <p>教训：全局 JSON 口径变更会波及"复用同一 ObjectMapper 的非 HTTP 用途"——
     * 这类地方必须显式隔离；同时读取端对两种形态都宽容（见 {@link #asLong(Object)}）。
     */
    private static final ObjectMapper TOKEN_JSON = new ObjectMapper();

    /**
     * 宽容取长整数：同时接受 JSON 数字与 JSON 字符串。
     *
     * <p>为什么要宽容：令牌是**已经发出去的东西**（客户端可能还持有 2 小时内签发的旧格式令牌）。
     * 格式一改就让所有人掉线，代价远大于这几行代码。
     *
     * @param value 值（Number 或 String）
     * @return 长整数
     */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text.trim());
        }
        // B5：异常必须带错误码（此处语义就是令牌内容不合法 → 403），不要抛 JDK 异常
        throw new BizException(ErrorCode.FORBIDDEN, "令牌字段不是数字：" + value);
    }

    /**
     * 签发令牌。
     *
     * @param token  令牌内容
     * @param secret 密钥
     * @return 紧凑字符串
     */
    public String encode(AccessToken token, String secret) {
        try {
            // 1. 组装负载（顺序固定：Map 用 LinkedHashMap 保证可读与稳定）
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put(FIELD_USER_ID, token.userId());
            payload.put(FIELD_TENANT_ID, token.tenantId());
            payload.put(FIELD_USERNAME, token.username());
            payload.put(FIELD_ADMIN, token.adminFlag());
            payload.put(FIELD_ROLES, new ArrayList<>(token.roleCodes()));
            payload.put(FIELD_PERMS, new ArrayList<>(token.permissions()));
            payload.put(FIELD_WAREHOUSES, new ArrayList<>(token.warehouseIds()));
            payload.put(FIELD_ISSUED_AT, token.issuedAt().getEpochSecond());
            payload.put(FIELD_EXPIRES_AT, token.expiresAt().getEpochSecond());
            byte[] body = TOKEN_JSON.writeValueAsBytes(payload);
            // 2. 签名
            String payloadPart = Base64.getUrlEncoder().withoutPadding().encodeToString(body);
            String signaturePart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sign(payloadPart, secret));
            return payloadPart + "." + signaturePart;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "令牌签发失败", ex);
        }
    }

    /**
     * 校验并解析令牌（**验签与过期检查都在这里**，调用方拿到的就是可信内容）。
     *
     * @param compact 紧凑字符串
     * @param secret  密钥
     * @return 令牌内容
     * @throws BizException 格式错/验签失败/过期时抛 {@code 40300}
     */
    @SuppressWarnings("unchecked")
    public AccessToken decode(String compact, String secret) {
        // 1. 结构校验
        if (compact == null || compact.isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少访问令牌");
        }
        String[] parts = compact.split("\\.");
        if (parts.length != 2) {
            throw new BizException(ErrorCode.FORBIDDEN, "令牌格式非法");
        }
        // 2. 验签（先验签再解析：解析不可信数据本身就是攻击面）
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(parts[0], secret));
        if (!constantTimeEquals(expected, parts[1])) {
            throw new BizException(ErrorCode.FORBIDDEN, "令牌签名校验失败（可能被篡改或密钥已更换）");
        }
        try {
            // 3. 解析
            Map<String, Object> payload = TOKEN_JSON.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            Instant issuedAt = Instant.ofEpochSecond(asLong(payload.get(FIELD_ISSUED_AT)));
            Instant expiresAt = Instant.ofEpochSecond(asLong(payload.get(FIELD_EXPIRES_AT)));
            AccessToken token = new AccessToken(asLong(payload.get(FIELD_USER_ID)),
                    asLong(payload.get(FIELD_TENANT_ID)),
                    String.valueOf(payload.get(FIELD_USERNAME)),
                    Boolean.TRUE.equals(payload.get(FIELD_ADMIN)),
                    toStringSet((List<Object>) payload.get(FIELD_ROLES)),
                    toStringSet((List<Object>) payload.get(FIELD_PERMS)),
                    toLongSet((List<Object>) payload.get(FIELD_WAREHOUSES)),
                    issuedAt, expiresAt);
            // 4. 过期检查（用服务端当前时间，不信任令牌里的其他时间）
            if (token.isExpired(Instant.now())) {
                throw new BizException(ErrorCode.FORBIDDEN, "令牌已过期，请重新登录或刷新");
            }
            return token;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FORBIDDEN, "令牌内容无法解析", ex);
        }
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param content 内容
     * @param secret  密钥
     * @return 签名
     */
    private byte[] sign(String content, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "令牌密钥未配置（wms.security.token-secret）");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "令牌签名计算失败", ex);
        }
    }

    /**
     * 常量时间比较（避免逐字符早退被计时攻击逐位猜测签名）。
     *
     * @param left  左值
     * @param right 右值
     * @return 相等返回 {@code true}
     */
    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * 字符串集合。
     *
     * @param raw 原始列表
     * @return 集合
     */
    private Set<String> toStringSet(List<Object> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (Object item : raw) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 长整型集合。
     *
     * @param raw 原始列表
     * @return 集合
     */
    private Set<Long> toLongSet(List<Object> raw) {
        Set<Long> result = new LinkedHashSet<>();
        if (raw != null) {
            for (Object item : raw) {
                result.add(asLong(item));
            }
        }
        return result;
    }
}
