package com.wms.platform.modules.fns.api.vo;

import com.wms.platform.modules.fns.domain.rate.FreightQuote;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费试算结果（`03` API-CS-04 的响应）。
 *
 * <p>除契约要求的 {@code estimatedFee} / 命中版本 / 缓冲系数外，附带计费重与计费轨迹——
 * 客服、对账、申诉都要用它们回答"为什么是这个价"。
 *
 * @param freightFee        基础运费（未含缓冲）
 * @param estimatedFee      预估价（含缓冲）——契约字段
 * @param cardCode          价目表编码
 * @param cardVersion       命中版本——契约字段（复算依据）
 * @param bufferRatio       缓冲系数——契约字段
 * @param actualWeightKg    实重
 * @param volumeWeightKg    体积重
 * @param chargeableWeight  计费重
 * @param additionalUnits   续重档数
 * @param minChargeApplied  是否触发最低消费
 * @param matchedRule       命中规则描述
 * @param trace             计费轨迹
 */
public record FreightQuoteVo(BigDecimal freightFee, BigDecimal estimatedFee, String cardCode, int cardVersion,
                             BigDecimal bufferRatio, BigDecimal actualWeightKg, BigDecimal volumeWeightKg,
                             BigDecimal chargeableWeight, int additionalUnits, boolean minChargeApplied,
                             String matchedRule, List<String> trace) {

    /**
     * 由领域对象转换（静态工厂：禁止在 Controller 里手工 new + set）。
     *
     * @param quote 试算结果
     * @return 展示对象
     */
    public static FreightQuoteVo of(FreightQuote quote) {
        return new FreightQuoteVo(quote.freightFee(), quote.estimatedFee(), quote.cardCode(), quote.cardVersion(),
                quote.bufferRatio(), quote.actualWeightKg(), quote.volumeWeightKg(), quote.chargeableWeight(),
                quote.additionalUnits(), quote.minChargeApplied(), quote.matchedRule(), quote.trace());
    }
}
