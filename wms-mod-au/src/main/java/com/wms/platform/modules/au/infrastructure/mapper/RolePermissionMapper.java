package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuRolePermissionPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 角色权限 Mapper（表 {@code au_role_permission}）。 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<AuRolePermissionPo> {

    /**
     * 角色已授予的权限 ID。
     *
     * @param tenantId 租户
     * @param roleId   角色
     * @return 权限 ID 列表
     */
    @Select("SELECT perm_id FROM au_role_permission WHERE tenant_id = #{tenantId} AND role_id = #{roleId} "
            + "AND deleted = 0")
    List<Long> selectPermIdsByRole(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    /**
     * 是否已存在该角色-权限关系。
     *
     * @param tenantId 租户
     * @param roleId   角色
     * @param permId   权限
     * @return 条数
     */
    default long countAny(long tenantId, long roleId, long permId) {
        return selectCount(Wrappers.<AuRolePermissionPo>lambdaQuery()
                .eq(AuRolePermissionPo::getTenantId, tenantId)
                .eq(AuRolePermissionPo::getRoleId, roleId)
                .eq(AuRolePermissionPo::getPermId, permId));
    }

    /**
     * 清空某角色的全部权限（**整批替换**用；Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId 租户
     * @param roleId   角色
     * @return 影响行数
     */
    default int deleteByRole(long tenantId, long roleId) {
        return delete(Wrappers.<AuRolePermissionPo>lambdaQuery()
                .eq(AuRolePermissionPo::getTenantId, tenantId)
                .eq(AuRolePermissionPo::getRoleId, roleId));
    }

    /**
     * 物理删除某角色的该权限关系行（**含已逻辑删除的残留**），为重新插入让路。
     *
     * <p>唯一键 uk_au_role_perm 不含 deleted，而"整批替换"的删除是逻辑删除 →
     * 不清理就会撞唯一键（实测 19999 系统错误）。
     *
     * @param tenantId 租户
     * @param roleId 角色
     * @param permId 权限
     * @return 影响行数
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM au_role_permission WHERE tenant_id = #{tenantId} "
            + "AND role_id = #{roleId} AND perm_id = #{permId}")
    int deleteAny(@Param("tenantId") long tenantId, @Param("roleId") long roleId, @Param("permId") long permId);

    /**
     * **物理**删除某角色的全部权限关系行（含已逻辑删除的残留）。
     *
     * <p>为什么必须是物理删除：唯一键 uk_au_role_perm 不含 deleted，
     * 而 deleteByRole 是逻辑删除 → 残留行会与重新插入冲突（实测 19999 系统错误）。
     * 也不能靠"先 countAny 再删"：@TableLogic 让 countAny 只看未删行，逻辑删后它恒为 0。
     *
     * @param tenantId 租户
     * @param roleId 角色
     * @return 影响行数
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM au_role_permission "
            + "WHERE tenant_id = #{tenantId} AND role_id = #{roleId}")
    int deleteAllByRole(@Param("tenantId") long tenantId, @Param("roleId") long roleId);
}
