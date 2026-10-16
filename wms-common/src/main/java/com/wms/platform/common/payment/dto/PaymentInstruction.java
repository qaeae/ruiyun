package com.wms.platform.common.payment.dto;

import java.math.BigDecimal;

/**
 * 支付/划拨指令。
 *
 * @param idemNo     幂等号（**同一批次必须复用同一个值**，机构侧据此去重）
 * @param bizType    业务类型（如 LOGISTICS_SETTLE 物流结算）
 * @param bizNo      业务单号（如结算批次号）
 * @param payeeType  收款方类型（承运商/商家）
 * @param payeeCode  收款方编码
 * @param amount     金额（正数）
 * @param currency   币种
 * @param remark     备注
 */
public record PaymentInstruction(String idemNo, String bizType, String bizNo, String payeeType, String payeeCode,
                                 BigDecimal amount, String currency, String remark) {

    /**
     * 静态工厂：物流结算划拨。
     *
     * @param stbNo   结算批次号
     * @param carrier 承运商编码
     * @param amount  金额
     * @return 指令
     */
    public static PaymentInstruction logisticsSettle(String stbNo, String carrier, BigDecimal amount) {
        return new PaymentInstruction("SETTLE:" + stbNo, "LOGISTICS_SETTLE", stbNo, "CARRIER", carrier, amount,
                "CNY", "物流运费月结划拨（平台不持有资金，由持牌机构直付承运商）");
    }
}
