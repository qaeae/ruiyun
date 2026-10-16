package com.wms.platform.modules.fns.config;

import com.wms.platform.common.messaging.ApprovalEventDispatcher;
import com.wms.platform.common.messaging.EventTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 财务域的审批事件消费者（P3.1：队列归属跟随模块归属）。
 *
 * <p>财务域事件包括：账单冲正、退款、押金退还、对账差异放行、**结算下发**。
 * 只有依赖 fns 模块的服务才会消费这些事件（详见 {@code IvApprovalEventConsumer} 的类注释）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
public class FnsApprovalEventConsumer {

    /** 共享派发器。 */
    private final ApprovalEventDispatcher approvalEventDispatcher;

    /**
     * 监听**财务域**队列。
     *
     * @param message 原始消息
     */
    @RabbitListener(queues = "wms.approval.fns")
    public void onApprovalApproved(Message message) {
        approvalEventDispatcher.dispatch(EventTopics.DOMAIN_FNS, message);
    }

    /**
     * 启动提示（排障用）。
     *
     * @return 提示
     */
    @Bean
    public String fnsApprovalConsumerBanner() {
        log.info("[MQ消费] 已订阅财务域审批事件队列 {}（消费者随 fns 模块一起部署）",
                EventTopics.queueOf(EventTopics.DOMAIN_FNS));
        return "fns-approval-consumer";
    }
}
