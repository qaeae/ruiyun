package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;

/**
 * 垫付限额预检结果（03 API-FNS-06）。
 *
 * <p>给下单流程用：**下单前**先问一句"该商家还能不能垫"，避免事后再回滚。
 *
 * @param merchantId           商家
 * @param cumulativeAdvance    累计垫付金额
 * @param consecutiveCount     近期垫付笔数（一期口径：近 7 天，见服务注释）
 * @param limited              是否已超限（超限则新垫付应拦截转人工）
 * @param limitAmount          金额阈值（¥2000）
 * @param limitCount           笔数阈值（3 笔）
 * @param message              说明
 */
public record AdvanceLimitVo(Long merchantId, BigDecimal cumulativeAdvance, long consecutiveCount, boolean limited,
                             BigDecimal limitAmount, int limitCount, String message) {
}
