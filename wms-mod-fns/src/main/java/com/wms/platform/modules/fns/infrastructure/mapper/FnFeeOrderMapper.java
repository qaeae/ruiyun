package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.platform.modules.fns.infrastructure.po.FnFeeOrderPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 费用单 Mapper（表 {@code fn_fee_order}）。
 *
 * <p>本类目前只暴露**对账取数**所需的只读查询：对账单的平台侧金额来自我们自己的物流运费费用单
 * （{@code fee_type=LOGISTICS}，{@code biz_no} = 运单号）。完整的计费（仓储费/操作费/超占费）
 * 与冻结扣减链路属于 `02` M4 的后续切片。
 */
@Mapper
public interface FnFeeOrderMapper extends BaseMapper<FnFeeOrderPo> {

    /**
     * 取某运单的平台侧物流运费（不存在返回 {@code null}，**不能返回 0**）。
     *
     * <p>为什么必须区分 null 与 0：{@code null} 表示"这笔运费根本没进我们的账"，
     * 属于必须挂人工的异常；把它当 0 会让对账显示"差异 = 承运商金额"，
     * 看起来只是金额对不上，而真相是漏计费——两者的处置方式完全不同。
     *
     * @param tenantId 租户
     * @param waybillNo 运单号
     * @return 金额，无记录返回 {@code null}
     */
    @Select("SELECT amount FROM fn_fee_order WHERE tenant_id = #{tenantId} AND fee_type = 'LOGISTICS' "
            + "AND biz_no = #{waybillNo} AND status <> '已冲正' AND deleted = 0 ORDER BY id LIMIT 1")
    java.math.BigDecimal selectLogisticsFee(@Param("tenantId") long tenantId, @Param("waybillNo") String waybillNo);
}
