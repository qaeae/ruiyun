package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.MdWarehousePo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 仓库主数据 Mapper（表 {@code md_warehouse}）。
 */
@Mapper
public interface WarehouseMapper extends BaseMapper<MdWarehousePo> {

    /**
     * 按编码查。
     *
     * @param tenantId      租户
     * @param warehouseCode 仓库编码
     * @return 仓库，不存在返回 {@code null}
     */
    default MdWarehousePo selectByCode(long tenantId, String warehouseCode) {
        return selectOne(Wrappers.<MdWarehousePo>lambdaQuery()
                .eq(MdWarehousePo::getTenantId, tenantId)
                .eq(MdWarehousePo::getWarehouseCode, warehouseCode)
                .last("limit 1"));
    }

    /**
     * 按 ID 查。
     *
     * @param tenantId 租户
     * @param id       仓库 ID
     * @return 仓库，不存在返回 {@code null}
     */
    default MdWarehousePo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<MdWarehousePo>lambdaQuery()
                .eq(MdWarehousePo::getTenantId, tenantId)
                .eq(MdWarehousePo::getId, id)
                .last("limit 1"));
    }

    /**
     * 列表。
     *
     * @param tenantId 租户
     * @return 列表（按编码排序）
     */
    default List<MdWarehousePo> selectListAll(long tenantId) {
        return selectList(Wrappers.<MdWarehousePo>lambdaQuery()
                .eq(MdWarehousePo::getTenantId, tenantId)
                .orderByAsc(MdWarehousePo::getWarehouseCode));
    }
    /**
     * 改状态（Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId  租户
     * @param id        仓库 ID
     * @param status    目标状态
     * @param updatedBy 操作人
     * @return 影响行数
     */
    default int updateStatus(long tenantId, long id, String status, Long updatedBy) {
        return update(null, Wrappers.<MdWarehousePo>lambdaUpdate()
                .eq(MdWarehousePo::getTenantId, tenantId)
                .eq(MdWarehousePo::getId, id)
                .set(MdWarehousePo::getStatus, status)
                .set(MdWarehousePo::getUpdatedBy, updatedBy));
    }
}
