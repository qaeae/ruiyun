package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.LocationPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 库位主数据 Mapper（表 {@code md_location}）。
 */
@Mapper
public interface LocationMapper extends BaseMapper<LocationPo> {

    /**
     * 按库位编码查（仓库内唯一）。
     *
     * @param tenantId     租户
     * @param warehouseId  仓库
     * @param locationCode 库位编码
     * @return 库位，不存在返回 {@code null}
     */
    default LocationPo selectByCode(long tenantId, long warehouseId, String locationCode) {
        return selectOne(Wrappers.<LocationPo>lambdaQuery()
                .eq(LocationPo::getTenantId, tenantId)
                .eq(LocationPo::getWarehouseId, warehouseId)
                .eq(LocationPo::getLocationCode, locationCode)
                .last("limit 1"));
    }

    /**
     * 按库位 ID 查。
     *
     * @param tenantId   租户
     * @param locationId 库位 ID
     * @return 库位，不存在返回 {@code null}
     */
    default LocationPo selectByIdSafe(long tenantId, long locationId) {
        return selectOne(Wrappers.<LocationPo>lambdaQuery()
                .eq(LocationPo::getTenantId, tenantId)
                .eq(LocationPo::getId, locationId)
                .last("limit 1"));
    }

    /**
     * 仓库下可拣库位列表（拣货路径用）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @return 库位列表（按编码排序，保证结果稳定）
     */
    default List<LocationPo> selectPickable(long tenantId, long warehouseId) {
        return selectList(Wrappers.<LocationPo>lambdaQuery()
                .eq(LocationPo::getTenantId, tenantId)
                .eq(LocationPo::getWarehouseId, warehouseId)
                .eq(LocationPo::getIsPickable, 1)
                .orderByAsc(LocationPo::getLocationCode));
    }
    /**
     * 仓库下的库位列表（主数据列表用）。
     *
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @return 列表（按编码排序，结果稳定）
     */
    default List<LocationPo> selectByWarehouse(long tenantId, long warehouseId) {
        return selectList(Wrappers.<LocationPo>lambdaQuery()
                .eq(LocationPo::getTenantId, tenantId)
                .eq(LocationPo::getWarehouseId, warehouseId)
                .orderByAsc(LocationPo::getLocationCode));
    }

    /**
     * 改状态（Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId  租户
     * @param id        库位 ID
     * @param status    目标状态
     * @param updatedBy 操作人
     * @return 影响行数
     */
    default int updateStatus(long tenantId, long id, String status, Long updatedBy) {
        return update(null, Wrappers.<LocationPo>lambdaUpdate()
                .eq(LocationPo::getTenantId, tenantId)
                .eq(LocationPo::getId, id)
                .set(LocationPo::getStatus, status)
                .set(LocationPo::getUpdatedBy, updatedBy));
    }
}
