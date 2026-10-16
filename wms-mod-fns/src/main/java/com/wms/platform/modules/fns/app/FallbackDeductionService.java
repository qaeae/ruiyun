package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.api.vo.FallbackCandidateVo;
import com.wms.platform.modules.fns.domain.FallbackDeductionRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底扣减服务（03 API-FNS-05）。
 *
 * <p><b>业务背景</b>：运费按"成本转嫁"处理，商家按**预估价**先被冻结/扣减；承运商回传实际运费后再多退少补。
 * 如果承运商一直不回传（丢件、对方对账拖延、系统对接异常），这笔钱会**永久挂在预估价上**：
 * 平台既不能给商家结算清楚，也无法跟承运商对账。因此必须有兜底：
 * 揽收后满 {@code FallbackDeductionRule.FALLBACK_DAYS}（7 天）即按预估价先行扣减，
 * 后续回传时按"多退少补"（{@code REFUND}/{@code SUPPLEMENT}/{@code NONE}）结算。
 *
 * <p><b>为什么用费用单承载兜底扣减</b>：{@code fn_fee_order.uk_fee_biz(tenant, fee_type, biz_type, biz_id)}
 * 天然提供幂等——同一运单重复扫描会撞唯一键，不会重复扣款。相比"先在 Service 里查一遍"，
 * 它还能挡住并发（定时任务双实例跑）这个更难测的场景。
 */
public interface FallbackDeductionService {

    /**
     * 扫描到期未回传的运单并落兜底扣减（幂等，可重复执行）。
     *
     * @param now 当前时间（显式传入便于测试与补跑历史）
     * @return 本次候选清单（含"已扣过"标记）
     */
    List<FallbackCandidateVo> sweep(LocalDateTime now);

    /**
     * 承运商回传实际运费后结算（多退少补）。
     *
     * @param waybillNo 运单号
     * @param actualFee 实际运费
     * @return 调整结论
     */
    FallbackDeductionRule.Adjustment settleLateReturn(String waybillNo, BigDecimal actualFee);
}
