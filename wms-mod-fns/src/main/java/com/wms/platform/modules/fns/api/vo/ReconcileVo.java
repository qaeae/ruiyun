package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对账单视图（03 API-FNS-01）。
 *
 * @param rclNo          对账单号
 * @param carrierCode    承运商
 * @param period         账期
 * @param status         状态
 * @param totalWaybills  运单数
 * @param platformAmount 平台金额
 * @param carrierAmount  承运商金额
 * @param diffAmount     差异额
 * @param autoCount      自动平账条数
 * @param manualCount    待人工处理条数
 * @param items          明细（列表接口为 null）
 */
public record ReconcileVo(String rclNo, String carrierCode, String period, String status, int totalWaybills,
                          BigDecimal platformAmount, BigDecimal carrierAmount, BigDecimal diffAmount, int autoCount,
                          int manualCount, List<ReconcileItemVo> items) {
}
