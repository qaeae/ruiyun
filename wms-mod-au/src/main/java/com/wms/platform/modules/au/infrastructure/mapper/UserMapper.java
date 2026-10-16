package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuUserPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户 Mapper（表 {@code au_user}）。 */
@Mapper
public interface UserMapper extends BaseMapper<AuUserPo> {

    /**
     * 按登录名查。
     *
     * @param tenantId 租户
     * @param username 登录名
     * @return 用户，不存在返回 {@code null}
     */
    default AuUserPo selectByUsername(long tenantId, String username) {
        return selectOne(Wrappers.<AuUserPo>lambdaQuery()
                .eq(AuUserPo::getTenantId, tenantId)
                .eq(AuUserPo::getUsername, username)
                .last("limit 1"));
    }

    /**
     * 按 ID 查。
     *
     * @param tenantId 租户
     * @param id       用户 ID
     * @return 用户，不存在返回 {@code null}
     */
    default AuUserPo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<AuUserPo>lambdaQuery()
                .eq(AuUserPo::getTenantId, tenantId)
                .eq(AuUserPo::getId, id)
                .last("limit 1"));
    }

    /**
     * 用户列表。
     *
     * @param tenantId 租户
     * @return 列表（按登录名排序）
     */
    default List<AuUserPo> selectListAll(long tenantId) {
        return selectList(Wrappers.<AuUserPo>lambdaQuery()
                .eq(AuUserPo::getTenantId, tenantId)
                .orderByAsc(AuUserPo::getUsername));
    }

    /**
     * 记录登录时间（登录成功才更新；失败不更新，避免"失败也算活跃"）。
     *
     * @param tenantId 租户
     * @param id       用户 ID
     * @return 影响行数
     */
    @Update("UPDATE au_user SET last_login_at = NOW(3) WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int updateLastLogin(@Param("tenantId") long tenantId, @Param("id") long id);

    /**
     * 改状态（Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId  租户
     * @param id        用户 ID
     * @param status    目标状态
     * @param updatedBy 操作人
     * @return 影响行数
     */
    default int updateStatus(long tenantId, long id, String status, Long updatedBy) {
        return update(null, Wrappers.<AuUserPo>lambdaUpdate()
                .eq(AuUserPo::getTenantId, tenantId)
                .eq(AuUserPo::getId, id)
                .set(AuUserPo::getStatus, status)
                .set(AuUserPo::getUpdatedBy, updatedBy));
    }
}
