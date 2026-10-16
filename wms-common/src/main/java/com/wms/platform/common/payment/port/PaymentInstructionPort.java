package com.wms.platform.common.payment.port;

import com.wms.platform.common.payment.dto.PaymentInstruction;
import com.wms.platform.common.payment.dto.PaymentInstructionResult;

/**
 * 支付/分账指令端口（{@code 04} 资金合规落地方案 §4）。
 *
 * <p><b>为什么必须有端口而不是直接调某家机构 SDK</b>：K2（持牌机构选型）尚未启动，
 * 而结算链路的其余部分（批次、幂等、状态机）可以先落地并测试。端口让"钱怎么走"可替换，
 * 而"钱怎么记账、怎么幂等、怎么防重复下发"这些真正容易出错的部分固定下来。
 *
 * <p><b>红线（{@code 04}）</b>：
 * <ul>
 *   <li>平台**不持牌、不做二清**：本端口只描述"向持牌机构发起一次划拨/分账指令"，
 *       资金流不经过平台账户；</li>
 *   <li>生产环境**禁止使用模拟通道**（{@code wms.payment.channel.mode} 必须为 {@code REAL}），
 *       否则会出现"以为在真扣款、其实只是打日志"的形态上线；</li>
 *   <li>指令必须携带幂等号（{@link PaymentInstruction#idemNo()}）：重复下发同一批次 = 重复付款。</li>
 * </ul>
 */
public interface PaymentInstructionPort {

    /**
     * 发起一次支付/划拨指令。
     *
     * @param instruction 指令（含幂等号）
     * @return 结果（含机构侧指令号）
     */
    PaymentInstructionResult pay(PaymentInstruction instruction);
}
