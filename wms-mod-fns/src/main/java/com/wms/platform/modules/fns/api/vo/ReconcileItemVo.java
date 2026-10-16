package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;

/**
 * 对账明细视图（03 API-FNS-01）。
 *
 * @param id           明细 ID
 * @param waybillNo    运单号
 * @param platformFee  平台费用（null = 平台无对应费用）
 * @param carrierFee   承运商费用
 * @param diffAmount   差异额
 * @param diffRate     差异率
 * @param diffType     差异类型
 * @param handleResult 处理结果
 * @param liability    责任方
 * @param remark       说明
 */
public record ReconcileItemVo(Long id, String waybillNo, BigDecimal platformFee, BigDecimal carrierFee,
                              BigDecimal diffAmount, BigDecimal diffRate, String diffType, String handleResult,
                              String liability, String remark) {
}
