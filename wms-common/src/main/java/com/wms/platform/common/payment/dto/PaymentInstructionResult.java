package com.wms.platform.common.payment.dto;

import java.time.LocalDateTime;

/**
 * 支付/划拨指令结果。
 *
 * @param accepted      机构是否受理
 * @param instructionNo 机构侧指令号（受理时非空；对账时用它跟机构流水核对）
 * @param message       说明
 * @param occurredAt    发生时间
 */
public record PaymentInstructionResult(boolean accepted, String instructionNo, String message,
                                       LocalDateTime occurredAt) {

    /**
     * 受理。
     *
     * @param instructionNo 机构侧指令号
     * @param message       说明
     * @return 结果
     */
    public static PaymentInstructionResult accepted(String instructionNo, String message) {
        return new PaymentInstructionResult(true, instructionNo, message, LocalDateTime.now());
    }

    /**
     * 拒绝。
     *
     * @param message 说明
     * @return 结果
     */
    public static PaymentInstructionResult rejected(String message) {
        return new PaymentInstructionResult(false, null, message, LocalDateTime.now());
    }
}
