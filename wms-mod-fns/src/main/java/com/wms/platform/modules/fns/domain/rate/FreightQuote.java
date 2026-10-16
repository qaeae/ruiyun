package com.wms.platform.modules.fns.domain.rate;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费试算结果（**可解释、可复现**）。
 *
 * <p>除了金额，还返回"为什么是这个金额"所需的全部信息：
 * 命中的价目表版本、命中的规则描述、实重/体积重/计费重、续重档数、是否触发最低消费、缓冲系数。
 *
 * <p>这不是过度设计——运费是最容易被质询的一项：
 * 商家问"为什么这单 12.5 元"、T+1 对账发现差额、承运商账期结算，
 * 都必须能用**当时的版本**复算出同一个数。少一个字段就要人去翻日志（往往已经滚动删掉了）。
 *
 * @param freightFee      基础运费（未含缓冲，元）
 * @param estimatedFee    预估价（含缓冲系数，元）——`03` API-CS-04 的返回字段
 * @param cardCode        命中的价目表编码
 * @param cardVersion     命中的价目表版本（**复算与审计的关键**）
 * @param bufferRatio     缓冲系数
 * @param actualWeightKg  实重
 * @param volumeWeightKg  体积重
 * @param chargeableWeight 计费重（= max(实重, 体积重)）
 * @param additionalUnits 续重档数（向上取整的倍数）
 * @param minChargeApplied 是否触发了最低消费
 * @param matchedRule     命中规则的可读描述
 * @param trace           计费过程（逐步说明，直接可读）
 */
public record FreightQuote(BigDecimal freightFee, BigDecimal estimatedFee, String cardCode, int cardVersion,
                           BigDecimal bufferRatio, BigDecimal actualWeightKg, BigDecimal volumeWeightKg,
                           BigDecimal chargeableWeight, int additionalUnits, boolean minChargeApplied,
                           String matchedRule, List<String> trace) {
}
