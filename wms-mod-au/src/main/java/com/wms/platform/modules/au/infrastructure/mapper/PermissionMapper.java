package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuPermissionPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 权限点 Mapper（表 {@code au_permission}）。 */
@Mapper
public interface PermissionMapper extends BaseMapper<AuPermissionPo> {

    /**
     * 按权限码批量查（授权时校验权限码已登记）。
     *
     * @param tenantId  租户
     * @param permCodes 权限码集合
     * @return 列表
     */
    default List<AuPermissionPo> selectByCodes(long tenantId, List<String> permCodes) {
        if (permCodes == null || permCodes.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<AuPermissionPo>lambdaQuery()
                .eq(AuPermissionPo::getTenantId, tenantId)
                .in(AuPermissionPo::getPermCode, permCodes));
    }

    /**
     * 全部权限点。
     *
     * @param tenantId 租户
     * @return 列表（按排序号、权限码）
     */
    default List<AuPermissionPo> selectListAll(long tenantId) {
        return selectList(Wrappers.<AuPermissionPo>lambdaQuery()
                .eq(AuPermissionPo::getTenantId, tenantId)
                .orderByAsc(AuPermissionPo::getSortNo, AuPermissionPo::getPermCode));
    }

    /**
     * 按 ID 集合查权限点（角色详情回显用；Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId 租户
     * @param ids      权限 ID 集合
     * @return 列表
     */
    default List<AuPermissionPo> selectByIds(long tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<AuPermissionPo>lambdaQuery()
                .eq(AuPermissionPo::getTenantId, tenantId)
                .in(AuPermissionPo::getId, ids));
    }

    /**
     * 查某用户的权限码集合（**一次联表查完**，避免"先查角色再逐个查权限"的 N+1）。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @return 权限码列表（去重）
     */
    @Select("""
            SELECT DISTINCT p.perm_code
              FROM au_user_role ur
              JOIN au_role r        ON r.id = ur.role_id AND r.deleted = 0 AND r.status = '启用'
              JOIN au_role_permission rp ON rp.role_id = r.id AND rp.deleted = 0
              JOIN au_permission p  ON p.id = rp.perm_id AND p.deleted = 0
             WHERE ur.tenant_id = #{tenantId} AND ur.user_id = #{userId} AND ur.deleted = 0
            """)
    List<String> selectPermCodesByUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    /**
     * 查某用户的角色码集合。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @return 角色码列表
     */
    @Select("""
            SELECT DISTINCT r.role_code
              FROM au_user_role ur
              JOIN au_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = '启用'
             WHERE ur.tenant_id = #{tenantId} AND ur.user_id = #{userId} AND ur.deleted = 0
            """)
    List<String> selectRoleCodesByUser(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
