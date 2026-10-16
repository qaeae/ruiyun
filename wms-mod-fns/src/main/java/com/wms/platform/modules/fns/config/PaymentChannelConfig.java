package com.wms.platform.modules.fns.config;

import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.payment.port.PaymentInstructionPort;
import com.wms.platform.common.payment.simulated.SimulatedPaymentChannel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付通道装配（{@code 04} 资金合规落地方案 §4）。
 *
 * <p><b>为什么 REAL 模式必须 fail-fast</b>：K2（持牌机构选型与开户）尚未启动，
 * 一期没有真实适配器。如果 {@code mode=REAL} 时静默回退到模拟通道，就会出现
 * **"配置写着真实、实际只打日志"**的形态——这是资金系统里最危险的假绿：
 * 演练时一切正常，上线后一分钱都没划出去，而对账单显示"已结算"。
 * 因此宁可在启动时直接失败并给出指引。
 */
@Configuration
@RequiredArgsConstructor
public class PaymentChannelConfig {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(PaymentChannelConfig.class);

    /** 配置。 */
    private final WmsProperties properties;

    /**
     * 支付通道 Bean。
     *
     * @return 通道实现
     */
    @Bean
    public PaymentInstructionPort paymentInstructionPort() {
        String mode = properties.getPayment().getChannel().getMode();
        if ("REAL".equalsIgnoreCase(mode)) {
            // 真实适配器待 K2 落地：此处**不提供回退**，直接把配置错误暴露在启动阶段
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "已配置 wms.payment.channel.mode=REAL，但本版本尚未接入持牌机构适配器。"
                            + "请先完成 K2（机构选型 + 物流方分账接收方开户），再实现 PaymentInstructionPort 的真实适配器；"
                            + "在此之前请显式配置为 MOCK（仅限非生产环境）。");
        }
        if (!"MOCK".equalsIgnoreCase(mode)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "非法的支付通道模式：" + mode + "（仅支持 MOCK / REAL）");
        }
        log.warn("支付通道使用模拟模式（mode=MOCK）：只记账、不发生真实资金流转，禁止用于生产");
        return new SimulatedPaymentChannel();
    }
}
