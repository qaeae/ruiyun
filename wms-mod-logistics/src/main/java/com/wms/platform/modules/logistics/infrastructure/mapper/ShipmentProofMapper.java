package com.wms.platform.modules.logistics.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentProofPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 发货单影像 Mapper（B1：条件构造器收口在 Mapper 的 default 方法里）。
 */
@Mapper
public interface ShipmentProofMapper extends BaseMapper<ShipmentProofPo> {

    /**
     * 按单号查影像（时间倒序）。
     *
     * @param tenantId   租户
     * @param shipmentNo 发货单号
     * @param bizType    用途（空则不过滤）
     * @return 影像列表
     */
    default List<ShipmentProofPo> selectByShipment(long tenantId, String shipmentNo, String bizType) {
        return selectList(Wrappers.<ShipmentProofPo>lambdaQuery()
                .eq(ShipmentProofPo::getTenantId, tenantId)
                .eq(ShipmentProofPo::getShipmentNo, shipmentNo)
                .eq(bizType != null && !bizType.isBlank(), ShipmentProofPo::getBizType, bizType)
                .orderByDesc(ShipmentProofPo::getCreatedAt));
    }

    /**
     * 按对象键查（用于"这张影像是否已登记过"的判断）。
     *
     * @param tenantId  租户
     * @param objectKey 对象键
     * @return 登记记录；无则返回 {@code null}
     */
    default ShipmentProofPo selectByObjectKey(long tenantId, String objectKey) {
        return selectOne(Wrappers.<ShipmentProofPo>lambdaQuery()
                .eq(ShipmentProofPo::getTenantId, tenantId)
                .eq(ShipmentProofPo::getObjectKey, objectKey)
                .last("limit 1"));
    }
}
