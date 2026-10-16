package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuUserRolePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户角色 Mapper（表 {@code au_user_role}）。 */
@Mapper
public interface UserRoleMapper extends BaseMapper<AuUserRolePo> {

    /**
     * 用户已授予的角色 ID。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @return 角色 ID 列表
     */
    @Select("SELECT role_id FROM au_user_role WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND deleted = 0")
    List<Long> selectRoleIdsByUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    /**
     * 是否已存在该用户-角色关系（含已逻辑删除的记录）。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @param roleId   角色
     * @return 条数
     */
    default long countAny(long tenantId, long userId, long roleId) {
        return selectCount(Wrappers.<AuUserRolePo>lambdaQuery()
                .eq(AuUserRolePo::getTenantId, tenantId)
                .eq(AuUserRolePo::getUserId, userId)
                .eq(AuUserRolePo::getRoleId, roleId));
    }

    /**
     * 逻辑删除用户的全部角色（**重新授权整批替换**，避免"少传一个角色变成新增"）。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @param updatedBy 操作人
     * @return 影响行数
     */
    @Update("UPDATE au_user_role SET deleted = 1, updated_by = #{updatedBy} "
            + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND deleted = 0")
    int deleteByUser(@Param("tenantId") long tenantId, @Param("userId") long userId,
                     @Param("updatedBy") Long updatedBy);

    /**
     * 物理清理某条用户-角色关系（含已逻辑删除的残留）。
     *
     * <p>为什么需要物理删除：唯一键 {@code uk_au_user_role(tenant_id, user_id, role_id)} 不区分
     * {@code deleted}，重新授予同一角色时会撞唯一键。整批替换的语义是「就该是这些」，
     * 因此残留记录必须先清掉，否则"撤销后再授权"会静默失败。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @param roleId   角色
     * @return 影响行数
     */
    default int deleteAny(long tenantId, long userId, long roleId) {
        return delete(Wrappers.<AuUserRolePo>lambdaQuery()
                .eq(AuUserRolePo::getTenantId, tenantId)
                .eq(AuUserRolePo::getUserId, userId)
                .eq(AuUserRolePo::getRoleId, roleId));
    }
}
