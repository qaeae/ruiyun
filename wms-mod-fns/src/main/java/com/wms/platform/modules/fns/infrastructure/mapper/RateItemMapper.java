package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.RateItemPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 价目表明细 Mapper。
 */
@Mapper
public interface RateItemMapper extends BaseMapper<RateItemPo> {

    /**
     * 取某价目表下的全部明细（按区域、重量下界排序，便于复现命中顺序）。
     *
     * @param tenantId 租户
     * @param cardId   价目表 ID
     * @return 明细列表
     */
    default List<RateItemPo> selectByCard(long tenantId, long cardId) {
        return selectList(Wrappers.<RateItemPo>lambdaQuery()
                .eq(RateItemPo::getTenantId, tenantId)
                .eq(RateItemPo::getCardId, cardId)
                .orderByAsc(RateItemPo::getRegionCode)
                .orderByAsc(RateItemPo::getWeightFrom));
    }
}
