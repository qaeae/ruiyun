package com.wms.platform.modules.au.api.vo;

import java.time.Instant;
import java.util.List;

/**
 * 登录/刷新结果（03 API-AUTH-01：返回 {@code roleCodes}、{@code warehouseIds}）。
 *
 * @param token        访问令牌（前端放 {@code Authorization: Bearer <token>}）
 * @param expiresAt    过期时间（前端据此提前刷新，而不是等 403 再重登）
 * @param userId       用户 ID
 * @param username     登录名
 * @param adminFlag    是否超管
 * @param roleCodes    角色码（前端按角色控制菜单）
 * @param permissions  权限码（前端按权限控制按钮）
 * @param warehouseIds 授权仓库（前端仓库下拉框只列这些）
 */
public record LoginVo(String token, Instant expiresAt, Long userId, String username, boolean adminFlag,
                      List<String> roleCodes, List<String> permissions, List<Long> warehouseIds) {
}
