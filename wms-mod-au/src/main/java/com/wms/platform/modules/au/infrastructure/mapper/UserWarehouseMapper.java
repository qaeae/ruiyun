package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuUserWarehousePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户仓库数据权限 Mapper（表 {@code au_user_warehouse}）。 */
@Mapper
public interface UserWarehouseMapper extends BaseMapper<AuUserWarehousePo> {

    /**
     * 用户已授权的仓库 ID（登录时写入令牌）。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @return 仓库 ID 列表
     */
    @Select("SELECT warehouse_id FROM au_user_warehouse WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND deleted = 0")
    List<Long> selectWarehouseIdsByUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    /**
     * 逻辑删除用户的全部仓库授权（整批替换）。
     *
     * @param tenantId  租户
     * @param userId    用户
     * @param updatedBy 操作人
     * @return 影响行数
     */
    @Update("UPDATE au_user_warehouse SET deleted = 1, updated_by = #{updatedBy} "
            + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND deleted = 0")
    int deleteByUser(@Param("tenantId") long tenantId, @Param("userId") long userId,
                     @Param("updatedBy") Long updatedBy);

    /**
     * 物理清理某条用户-仓库授权（含已逻辑删除的残留；理由同 {@code UserRoleMapper.deleteAny}）。
     *
     * @param tenantId    租户
     * @param userId      用户
     * @param warehouseId 仓库
     * @return 影响行数
     */
    default int deleteAny(long tenantId, long userId, long warehouseId) {
        return delete(Wrappers.<AuUserWarehousePo>lambdaQuery()
                .eq(AuUserWarehousePo::getTenantId, tenantId)
                .eq(AuUserWarehousePo::getUserId, userId)
                .eq(AuUserWarehousePo::getWarehouseId, warehouseId));
    }
}
