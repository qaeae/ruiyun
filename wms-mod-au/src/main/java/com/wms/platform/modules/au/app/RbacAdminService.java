package com.wms.platform.modules.au.app;

import com.wms.platform.modules.au.api.ro.CreateUserRo;
import com.wms.platform.modules.au.api.ro.RoleCreateRo;
import com.wms.platform.modules.au.api.vo.RoleVo;
import com.wms.platform.modules.au.api.vo.UserVo;

import java.util.List;

/**
 * RBAC 管理服务（03 API-AUTH-02 / API-AUTH-03）。
 *
 * <p><b>两条设计约束</b>：
 * <ol>
 *   <li><b>授权是"整批替换"而不是"增量追加"</b>：给用户配角色时按传入集合整体重建，
 *       否则"少传一个角色"会被理解成"不动它"，而前线以为已经撤销了——权限撤销必须确定可达；</li>
 *   <li><b>角色码与权限码都必须已存在</b>：拒绝授予系统里不存在的码（否则会生成一堆
 *       永远不生效的僵尸授权，而且没人能看出是码写错了）。</li>
 * </ol>
 */
public interface RbacAdminService {

    /**
     * 新增用户（口令哈希入库；手机号加密 + 脱敏）。
     *
     * @param ro 入参
     * @return 用户 ID
     */
    Long createUser(CreateUserRo ro);

    /**
     * 新增角色。
     *
     * @param ro 入参
     * @return 角色 ID
     */
    Long createRole(RoleCreateRo ro);

    /**
     * 给角色授予权限（整批替换）。
     *
     * @param roleId    角色 ID
     * @param permCodes 权限码集合
     */
    void grantRolePermissions(long roleId, List<String> permCodes);

    /**
     * 给用户分配角色（整批替换，支持一人多角色）。
     *
     * @param userId    用户 ID
     * @param roleCodes 角色码集合
     */
    void assignUserRoles(long userId, List<String> roleCodes);

    /**
     * 给用户授权仓库数据范围（整批替换；跨仓任职需显式授权）。
     *
     * @param userId       用户 ID
     * @param warehouseIds 仓库 ID 集合
     */
    void assignUserWarehouses(long userId, List<Long> warehouseIds);

    /**
     * 用户启停用。
     *
     * @param userId  用户 ID
     * @param enabled 是否启用
     */
    void changeUserStatus(long userId, boolean enabled);

    /**
     * 用户列表（含角色码，便于核对授权）。
     *
     * @return 列表
     */
    List<UserVo> listUsers();

    /**
     * 角色列表（含权限码）。
     *
     * @return 列表
     */
    List<RoleVo> listRoles();

    /**
     * 全部权限点（授权界面用）。
     *
     * @return 权限码列表
     */
    List<String> listPermissionCodes();

    /**
     * 把 {@code PermissionCodes} 里登记的权限码**补齐到权限表**（幂等）。
     *
     * <p>为什么需要它：权限码是写在代码里的常量，而授权表里必须有对应记录。
     * 若靠人工在界面上一条条加，**新增一个权限码就必然漏**（表现为"新功能所有人都没权限"）。
     * 由代码目录补齐，评审新增权限码时就能看到它。
     *
     * @return 本次新增的权限码数量
     */
    int syncPermissionCatalog();
}
