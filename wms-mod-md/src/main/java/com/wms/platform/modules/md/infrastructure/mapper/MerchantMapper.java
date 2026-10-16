package com.wms.platform.modules.md.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.md.infrastructure.po.MdMerchantPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 商家主数据 Mapper（表 {@code md_merchant}）。
 */
@Mapper
public interface MerchantMapper extends BaseMapper<MdMerchantPo> {

    /**
     * 按编号查（唯一键判重用）。
     *
     * @param tenantId   租户
     * @param merchantNo 商家编号
     * @return 商家，不存在返回 {@code null}
     */
    default MdMerchantPo selectByNo(long tenantId, String merchantNo) {
        return selectOne(Wrappers.<MdMerchantPo>lambdaQuery()
                .eq(MdMerchantPo::getTenantId, tenantId)
                .eq(MdMerchantPo::getMerchantNo, merchantNo)
                .last("limit 1"));
    }

    /**
     * 列表（按编号排序，结果稳定）。
     *
     * @param tenantId 租户
     * @param status   状态（可空 = 不限）
     * @return 列表
     */
    default List<MdMerchantPo> selectListByStatus(long tenantId, String status) {
        return selectList(Wrappers.<MdMerchantPo>lambdaQuery()
                .eq(MdMerchantPo::getTenantId, tenantId)
                .eq(status != null && !status.isBlank(), MdMerchantPo::getStatus, status)
                .orderByAsc(MdMerchantPo::getMerchantNo));
    }
    /**
     * 按 ID 查。
     *
     * @param tenantId 租户
     * @param id       商家 ID
     * @return 商家，不存在返回 { null}
     */
    default MdMerchantPo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<MdMerchantPo>lambdaQuery()
                .eq(MdMerchantPo::getTenantId, tenantId)
                .eq(MdMerchantPo::getId, id)
                .last("limit 1"));
    }

    /**
     * 改状态（**Wrapper 收口在 Mapper**，B1：Service 不得使用条件构造器）。
     *
     * @param tenantId  租户
     * @param id        商家 ID
     * @param status    目标状态
     * @param updatedBy 操作人
     * @return 影响行数
     */
    default int updateStatus(long tenantId, long id, String status, Long updatedBy) {
        return update(null, Wrappers.<MdMerchantPo>lambdaUpdate()
                .eq(MdMerchantPo::getTenantId, tenantId)
                .eq(MdMerchantPo::getId, id)
                .set(MdMerchantPo::getStatus, status)
                .set(MdMerchantPo::getUpdatedBy, updatedBy));
    }
}
