package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 兜底扣减候选项（03 API-FNS-05）。
 *
 * @param waybillNo    运单号
 * @param merchantId   商家
 * @param amount       应扣金额（= 预估价）
 * @param pickedUpAt   揽收时间
 * @param deadline     到期时间（揽收 + 7 天）
 * @param alreadyDeducted 是否已扣过（幂等：重复扫描不会重复扣）
 * @param message      说明
 */
public record FallbackCandidateVo(String waybillNo, Long merchantId, BigDecimal amount, LocalDateTime pickedUpAt,
                                 LocalDateTime deadline, boolean alreadyDeducted, String message) {
}
