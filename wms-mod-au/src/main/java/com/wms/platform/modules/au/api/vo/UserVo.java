package com.wms.platform.modules.au.api.vo;

import java.util.List;

/**
 * 用户视图（03 API-AUTH-02/03；含角色码与授权仓库，便于核对授权）。
 *
 * @param id           用户 ID
 * @param username     登录名
 * @param name         姓名
 * @param phoneMasked  脱敏手机号（完整号码存密文，接口默认不出）
 * @param status       状态
 * @param adminFlag    是否超管
 * @param roleCodes    角色码
 * @param warehouseIds 授权仓库
 */
public record UserVo(Long id, String username, String name, String phoneMasked, String status, boolean adminFlag,
                     List<String> roleCodes, List<Long> warehouseIds) {
}
