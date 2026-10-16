package com.wms.platform.modules.fns.api.vo;

import java.math.BigDecimal;

/**
 * 结算批次视图（03 API-FNS-03/04）。
 *
 * @param stbNo         批次号
 * @param carrierCode   承运商
 * @param period        账期
 * @param status        状态
 * @param waybillCount  运单数
 * @param totalAmount   金额
 * @param instructionNo 机构侧指令号（模拟通道带 {@code MOCK-} 前缀）
 * @param failReason    失败原因
 */
public record SettleBatchVo(String stbNo, String carrierCode, String period, String status, int waybillCount,
                            BigDecimal totalAmount, String instructionNo, String failReason) {
}
