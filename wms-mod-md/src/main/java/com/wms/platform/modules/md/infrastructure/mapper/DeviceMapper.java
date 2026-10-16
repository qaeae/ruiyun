package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.DevicePo;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备档案 Mapper（表 {@code md_device}）。
 *
 * <p>规范落地（B1）：<b>所有 Wrapper / SQL 只允许写在本接口的 {@code default} 方法里</b>，
 * 应用服务不得出现 {@code Wrappers}——由 {@code wms-archtest} 阻断。
 *
 * <p>启停为什么走这里而不是在 Service 里 {@code po.setStatus(...)}：设备档案虽然不属"单据状态机"，
 * 但状态字段集中在此更新可保证**同一处 SQL 带 {@code updated_at/updated_by} 审计**，
 * 且不会触碰 {@code ModuleBoundaryTest} 的"状态只能由状态机修改"规则（那条规则针对单据）。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<DevicePo> {

    /**
     * 按设备编号查询（唯一键 {@code uk_md_device}）。
     *
     * @param tenantId   租户 ID
     * @param deviceCode 设备编号
     * @return 设备记录，不存在返回 {@code null}
     */
    default DevicePo selectByCode(long tenantId, String deviceCode) {
        return selectOne(Wrappers.<DevicePo>lambdaQuery()
                .eq(DevicePo::getTenantId, tenantId)
                .eq(DevicePo::getDeviceCode, deviceCode)
                .last("limit 1"));
    }

    /**
     * 按仓库 + 类型查询设备列表（设备台账页与排障用）。
     *
     * @param tenantId    租户 ID
     * @param warehouseId 仓库 ID（可空，表示不限）
     * @param deviceType  设备类型（可空，表示不限）
     * @return 设备列表（按编号排序，保证结果稳定）
     */
    default List<DevicePo> selectByCondition(long tenantId, Long warehouseId, String deviceType) {
        return selectList(Wrappers.<DevicePo>lambdaQuery()
                .eq(DevicePo::getTenantId, tenantId)
                .eq(warehouseId != null, DevicePo::getWarehouseId, warehouseId)
                .eq(deviceType != null && !deviceType.isBlank(), DevicePo::getDeviceType, deviceType)
                .orderByAsc(DevicePo::getDeviceCode));
    }

    /**
     * 更新启用状态（启停唯一入口）。
     *
     * @param tenantId   租户 ID
     * @param deviceCode 设备编号
     * @param status     目标状态（启用/停用）
     * @param updatedBy  操作人
     * @return 受影响行数
     */
    default int updateStatus(long tenantId, String deviceCode, String status, Long updatedBy) {
        return update(null, Wrappers.<DevicePo>lambdaUpdate()
                .eq(DevicePo::getTenantId, tenantId)
                .eq(DevicePo::getDeviceCode, deviceCode)
                .set(DevicePo::getStatus, status)
                .set(DevicePo::getUpdatedBy, updatedBy)
                .set(DevicePo::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 刷新最近在线时间（由设备请求顺带触发，不引入主动心跳）。
     *
     * @param tenantId   租户 ID
     * @param deviceCode 设备编号
     * @return 受影响行数
     */
    default int touchOnline(long tenantId, String deviceCode) {
        return update(null, Wrappers.<DevicePo>lambdaUpdate()
                .eq(DevicePo::getTenantId, tenantId)
                .eq(DevicePo::getDeviceCode, deviceCode)
                .set(DevicePo::getLastOnlineAt, LocalDateTime.now()));
    }

    /**
     * 编辑设备（只允许改名称/仓库/位置/备注；<b>设备编号不可改</b>——现场已贴码）。
     *
     * @param tenantId     租户 ID
     * @param deviceCode   设备编号
     * @param name         设备名称
     * @param warehouseId  归属仓库
     * @param locationDesc 摆放位置
     * @param remark       备注
     * @param updatedBy    操作人
     * @return 受影响行数
     */
    default int updateProfile(long tenantId, String deviceCode, String name, Long warehouseId,
                              String locationDesc, String remark, Long updatedBy) {
        LambdaUpdateWrapper<DevicePo> wrapper = Wrappers.<DevicePo>lambdaUpdate()
                .eq(DevicePo::getTenantId, tenantId)
                .eq(DevicePo::getDeviceCode, deviceCode)
                .set(DevicePo::getName, name)
                .set(DevicePo::getWarehouseId, warehouseId)
                .set(DevicePo::getLocationDesc, locationDesc)
                .set(DevicePo::getRemark, remark)
                .set(DevicePo::getUpdatedBy, updatedBy)
                .set(DevicePo::getUpdatedAt, LocalDateTime.now());
        return update(null, wrapper);
    }
}
