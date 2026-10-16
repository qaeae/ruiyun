package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日终勾稽结果视图（03 API-FNS-07）。
 *
 * @param id             记录 ID
 * @param bizDate        业务日期
 * @param layer          层（FUND/FEE/CARRIER/INVENTORY）
 * @param status         状态（一致/有差异/待接入/已处理）
 * @param platformAmount 平台侧金额
 * @param channelAmount  对方侧金额
 * @param diffAmount     差额
 * @param diffCount      差异笔数
 * @param detailJson     差异明细
 * @param handledBy      处理人
 */
public record DailyReconciliationVo(Long id, LocalDate bizDate, String layer, String status,
                                    BigDecimal platformAmount, BigDecimal channelAmount, BigDecimal diffAmount,
                                    int diffCount, String detailJson, Long handledBy) {
}
