package com.wms.platform.modules.au.api.vo;

import java.util.List;

/**
 * 角色视图（03 API-AUTH-02）。
 *
 * @param id        角色 ID
 * @param roleCode  角色码
 * @param name      名称
 * @param status    状态
 * @param permCodes 已授予的权限码
 */
public record RoleVo(Long id, String roleCode, String name, String status, List<String> permCodes) {
}
