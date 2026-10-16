package com.wms.platform.modules.outbound.infrastructure.mapstruct;

import com.wms.platform.common.mapstruct.MapStructExt;
import com.wms.platform.common.mapstruct.MapperCentralConfig;
import com.wms.platform.modules.outbound.api.vo.OutOrderBriefVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderItemVo;
import com.wms.platform.modules.outbound.api.vo.OutStatusLogVo;
import com.wms.platform.modules.outbound.infrastructure.po.BizStatusLogPo;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderItemPo;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderPo;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 出库域 1:1 映射器（规范条目 B8）。
 *
 * <p><b>放 infrastructure 而不是 api</b>：它依赖 {@code Po}（infrastructure），
 * 而 ArchUnit 禁止 {@code api} 依赖 {@code infrastructure}；
 * <b>不声明集合方法</b>：批量转换统一走 {@code MapStructExt.toPojoList(list, INSTANCE::toVo)}。
 *
 * <p><b>只做 1:1 映射</b>：出库单详情（{@code OutOrderDetailVo}）是多源装配
 * （出库单 + 明细 + 掩码字段组装），在应用服务里显式装配——那不是"Bean 拷贝"，
 * 而是三个来源的合成，用映射器硬套反而会让"哪些字段来自哪里"变得不可读。
 */
@Mapper(config = MapperCentralConfig.class)
public interface OutOrderMS extends MapStructExt {

    /** 单例（不依赖 Spring 注入，便于单测与静态调用）。 */
    OutOrderMS INSTANCE = Mappers.getMapper(OutOrderMS.class);

    /**
     * 明细分录 → 视图。
     *
     * @param po 明细
     * @return 明细视图
     */
    OutOrderItemVo toVo(OutOrderItemPo po);

    /**
     * 出库单 → 列表行视图（列表不含 PII，此处自然不映射收件人字段）。
     *
     * @param po 出库单
     * @return 列表行视图
     */
    OutOrderBriefVo toVo(OutOrderPo po);

    /**
     * 状态流水 → 轨迹视图。
     *
     * @param po 状态流水
     * @return 轨迹视图
     */
    OutStatusLogVo toVo(BizStatusLogPo po);
}
