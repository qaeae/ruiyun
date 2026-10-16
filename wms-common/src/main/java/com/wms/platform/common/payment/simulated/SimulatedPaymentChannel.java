package com.wms.platform.common.payment.simulated;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.payment.dto.PaymentInstruction;
import com.wms.platform.common.payment.dto.PaymentInstructionResult;
import com.wms.platform.common.payment.port.PaymentInstructionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 模拟支付通道（{@code mode=MOCK}）：**只记账不动钱**，用于开发/联调/演练。
 *
 * <p><b>安全设计</b>：本类会做两件"克制"的事——
 * <ol>
 *   <li>金额必须为正：金额 ≤0 的指令是逻辑错误，模拟通道也要拦（否则测试环境放过的 bug 会在生产暴露）；</li>
 *   <li>返回的指令号带 {@code MOCK-} 前缀：任何落库的机构指令号都能一眼看出是模拟数据，
 *       避免演练数据混进真实对账（真实场景里这种混淆极难排查）。</li>
 * </ol>
 */
public class SimulatedPaymentChannel implements PaymentInstructionPort {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentChannel.class);

    /** 模拟指令号前缀。 */
    public static final String MOCK_PREFIX = "MOCK-";

    @Override
    public PaymentInstructionResult pay(PaymentInstruction instruction) {
        // 1. 金额必须为正
        if (instruction.amount() == null || instruction.amount().signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "支付指令金额必须为正，实际：" + instruction.amount() + "（bizNo=" + instruction.bizNo() + "）");
        }
        // 2. 幂等号必填
        if (instruction.idemNo() == null || instruction.idemNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "支付指令必须携带幂等号（重复下发等于重复付款）");
        }
        // 3. 只记账：打一条明确的 WARN 日志，便于在日志里检索演练痕迹
        String instructionNo = MOCK_PREFIX + UUID.randomUUID();
        log.warn("[模拟通道] 未发生真实资金流转 instructionNo={} bizNo={} payee={} amount={} {}",
                instructionNo, instruction.bizNo(), instruction.payeeCode(), instruction.amount(),
                instruction.currency());
        return PaymentInstructionResult.accepted(instructionNo, "模拟通道受理（未发生真实资金流转）");
    }
}
