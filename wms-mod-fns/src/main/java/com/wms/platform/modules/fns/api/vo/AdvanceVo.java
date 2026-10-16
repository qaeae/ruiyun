package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 垫付记录视图（03 API-FNS-06）。
 *
 * @param waybillNo     运单号（幂等键）
 * @param merchantId    商家
 * @param amount        垫付金额
 * @param status        状态
 * @param billingPeriod 账期
 * @param occurredAt    发生时间
 * @param clearedAt     核销时间
 * @param remark        备注
 */
public record AdvanceVo(String waybillNo, Long merchantId, BigDecimal amount, String status, String billingPeriod,
                        LocalDateTime occurredAt, LocalDateTime clearedAt, String remark) {
}
