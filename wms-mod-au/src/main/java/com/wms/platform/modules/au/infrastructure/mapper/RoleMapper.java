package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuRolePo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 角色 Mapper（表 {@code au_role}）。 */
@Mapper
public interface RoleMapper extends BaseMapper<AuRolePo> {

    /**
     * 按角色码查。
     *
     * @param tenantId 租户
     * @param roleCode 角色码
     * @return 角色，不存在返回 {@code null}
     */
    default AuRolePo selectByCode(long tenantId, String roleCode) {
        return selectOne(Wrappers.<AuRolePo>lambdaQuery()
                .eq(AuRolePo::getTenantId, tenantId)
                .eq(AuRolePo::getRoleCode, roleCode)
                .last("limit 1"));
    }

    /**
     * 按角色码批量查（授权时校验角色码存在）。
     *
     * @param tenantId  租户
     * @param roleCodes 角色码集合
     * @return 列表
     */
    default List<AuRolePo> selectByCodes(long tenantId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<AuRolePo>lambdaQuery()
                .eq(AuRolePo::getTenantId, tenantId)
                .in(AuRolePo::getRoleCode, roleCodes));
    }

    /**
     * 全部角色（授予界面用）。
     *
     * @param tenantId 租户
     * @return 列表（按角色码排序）
     */
    default List<AuRolePo> selectListAll(long tenantId) {
        return selectList(Wrappers.<AuRolePo>lambdaQuery()
                .eq(AuRolePo::getTenantId, tenantId)
                .orderByAsc(AuRolePo::getRoleCode));
    }
}
