package com.wms.platform.modules.fns.domain.rate;

import java.math.BigDecimal;
import java.util.List;

/**
 * 价目表（一个版本）。
 *
 * <p>{@code version} 会随试算结果一起返回并落进费用单——这是"任何一笔运费都能复算"的前提：
 * 调价是**新增版本**，历史运费仍引用旧版本。
 *
 * @param cardCode    价目表编码
 * @param carrierCode 承运商编码（ALL 表示通用）
 * @param version     版本号
 * @param bufferRatio 预估价缓冲系数
 * @param items       明细
 */
public record RateCard(String cardCode, String carrierCode, int version, BigDecimal bufferRatio,
                       List<RateItem> items) {
}
