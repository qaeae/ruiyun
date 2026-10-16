package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.RateCardPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 价目表 Mapper（B1：条件构造器收口在 Mapper 的 default 方法里）。
 */
@Mapper
public interface RateCardMapper extends BaseMapper<RateCardPo> {

    /**
     * 取某编码的最大版本号（新增版本时用）。
     *
     * @param tenantId 租户
     * @param cardCode 编码
     * @return 最大版本；无记录返回 0
     */
    default int maxVersion(long tenantId, String cardCode) {
        List<RateCardPo> list = selectList(Wrappers.<RateCardPo>lambdaQuery()
                .eq(RateCardPo::getTenantId, tenantId)
                .eq(RateCardPo::getCardCode, cardCode)
                .orderByDesc(RateCardPo::getVersion)
                .last("limit 1"));
        return list.isEmpty() ? 0 : list.get(0).getVersion();
    }

    /**
     * 取当前启用的价目表（同一编码同时最多一个启用版本）。
     *
     * @param tenantId    租户
     * @param cardCode    编码
     * @return 价目表；无则返回 {@code null}
     */
    default RateCardPo selectEnabled(long tenantId, String cardCode) {
        return selectOne(Wrappers.<RateCardPo>lambdaQuery()
                .eq(RateCardPo::getTenantId, tenantId)
                .eq(RateCardPo::getCardCode, cardCode)
                .eq(RateCardPo::getStatus, RateCardPo.STATUS_ENABLED)
                .last("limit 1"));
    }

    /**
     * 取某编码下的全部版本（停用历史版本时用）。
     *
     * @param tenantId 租户
     * @param cardCode 编码
     * @return 版本列表
     */
    default List<RateCardPo> selectVersions(long tenantId, String cardCode) {
        return selectList(Wrappers.<RateCardPo>lambdaQuery()
                .eq(RateCardPo::getTenantId, tenantId)
                .eq(RateCardPo::getCardCode, cardCode)
                .orderByDesc(RateCardPo::getVersion));
    }
}
