package com.wms.platform.common.security.token;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.time.Instant;
import java.util.Set;

/**
 * 访问令牌（登录后签发的凭证载体）。
 *
 * <p><b>令牌里放什么、不放什么</b>：放 {@code userId/tenantId/roleCodes/warehouseIds} 与过期时间——
 * 这些是"每次请求都要用"的身份信息；**不放手机号、姓名等 PII**，因为令牌会出现在日志、
 * 浏览器存储、代理缓存里，放进去等于扩大了泄漏面。
 *
 * <p>{@code warehouseIds} 放进令牌而不是每次查库，是为了避免"每个请求一次权限查询"；
 * 代价是**改授权后需要重新登录/刷新令牌**才能生效。这个取舍对一期是合适的
 * （授权变更低频），并已在文档里写明；若以后要求"改完立即生效"，
 * 再引入版本号（{@code permVersion}）比对即可，不必推翻结构。
 *
 * @param userId       用户 ID
 * @param tenantId     租户 ID
 * @param username     登录名
 * @param adminFlag    是否超管
 * @param roleCodes    角色码集合
 * @param permissions  权限码集合（由角色汇总得到）
 * @param warehouseIds 授权仓库集合（数据权限）
 * @param issuedAt     签发时间
 * @param expiresAt    过期时间
 */
public record  AccessToken(long userId, long tenantId, String username, boolean adminFlag, Set<String> roleCodes,
                          Set<String> permissions, Set<Long> warehouseIds, Instant issuedAt, Instant expiresAt) {

    /**
     * 构造并校验。
     *
     * @param userId       用户 ID
     * @param tenantId     租户 ID
     * @param username     登录名
     * @param adminFlag    是否超管
     * @param roleCodes    角色码
     * @param permissions  权限码
     * @param warehouseIds 授权仓库
     * @param issuedAt     签发时间
     * @param expiresAt    过期时间
     */
    public AccessToken {
        roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        warehouseIds = warehouseIds == null ? Set.of() : Set.copyOf(warehouseIds);
    }

    /**
     * 是否过期。
     *
     * @param now 当前时间
     * @return 过期返回 {@code true}
     */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    /**
     * 是否拥有权限（超管直接放行）。
     *
     * @param permission 权限码
     * @return 拥有返回 {@code true}
     */
    public boolean hasPermission(String permission) {
        return adminFlag || permissions.contains(permission);
    }

    /**
     * 是否可访问该仓库（数据权限）。**超管可跨仓，普通用户必须显式授权**（03 API-AUTH-03）。
     *
     * @param warehouseId 仓库 ID
     * @return 可访问返回 {@code true}
     */
    public boolean canAccessWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            return true;
        }
        return adminFlag || warehouseIds.contains(warehouseId);
    }

    /**
     * 仓库数据权限校验。
     *
     * @param warehouseId 仓库 ID
     * @throws BizException 无权限时抛 {@code 40300}
     */
    public void assertWarehouseAccess(Long warehouseId) {
        if (!canAccessWarehouse(warehouseId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "无该仓库的数据权限：" + warehouseId + "（跨仓任职需由平台运营显式授权）");
        }
    }
}
