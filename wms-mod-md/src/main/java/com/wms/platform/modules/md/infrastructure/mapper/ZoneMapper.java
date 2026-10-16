package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.MdZonePo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 库区 Mapper（表 {@code md_zone}）。
 */
@Mapper
public interface ZoneMapper extends BaseMapper<MdZonePo> {

    /**
     * 按编码查（仓库内唯一）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param zoneCode    库区编码
     * @return 库区，不存在返回 {@code null}
     */
    default MdZonePo selectByCode(long tenantId, long warehouseId, String zoneCode) {
        return selectOne(Wrappers.<MdZonePo>lambdaQuery()
                .eq(MdZonePo::getTenantId, tenantId)
                .eq(MdZonePo::getWarehouseId, warehouseId)
                .eq(MdZonePo::getZoneCode, zoneCode)
                .last("limit 1"));
    }

    /**
     * 按 ID 查。
     *
     * @param tenantId 租户
     * @param id       库区 ID
     * @return 库区，不存在返回 {@code null}
     */
    default MdZonePo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<MdZonePo>lambdaQuery()
                .eq(MdZonePo::getTenantId, tenantId)
                .eq(MdZonePo::getId, id)
                .last("limit 1"));
    }

    /**
     * 仓库下的库区列表。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @return 列表（按编码排序）
     */
    default List<MdZonePo> selectByWarehouse(long tenantId, long warehouseId) {
        return selectList(Wrappers.<MdZonePo>lambdaQuery()
                .eq(MdZonePo::getTenantId, tenantId)
                .eq(MdZonePo::getWarehouseId, warehouseId)
                .orderByAsc(MdZonePo::getZoneCode));
    }
}
