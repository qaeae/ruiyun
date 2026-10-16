package com.wms.platform.modules.iv.config;

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
 * 库存域的审批事件消费者（P3.1：**队列归属跟随模块归属**）。
 *
 * <p><b>为什么消费者写在 iv 模块里、而不是公共模块</b>：早期版本把消费者放在公共层，
 * 结果是"每个带 AMQP 的服务都进了同一个队列的竞争组"——`wms-auth` 抢到结算事件却没有对应执行器，
 * 消息被重试耗尽后进了死信队列（**审批已通过但业务不生效，且取决于谁先抢到消息**）。
 *
 * <p>现在：库存队列的消费者存在于 iv 模块内 ⇒ **只有依赖 iv 模块的服务才会消费库存事件**。
 * `wms-auth` / `wms-masterdata` 不依赖 iv，就根本不存在这个监听器——
 * 这不再依赖配置开关，而是由**依赖关系天然保证**（配错的可能性被结构性消除了）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
public class IvApprovalEventConsumer {

    /** 共享派发器（解析事件、设租户上下文、找执行方、幂等说明都在里面）。 */
    private final ApprovalEventDispatcher approvalEventDispatcher;

    /**
     * 监听**库存域**队列：只收 {@code approval.approved.iv} 路由过来的事件。
     *
     * @param message 原始消息
     */
    @RabbitListener(queues = "wms.approval.iv")
    public void onApprovalApproved(Message message) {
        approvalEventDispatcher.dispatch(EventTopics.DOMAIN_IV, message);
    }

    /**
     * 供启动日志确认"本服务承担库存域执行职责"（排障时一眼看出谁在消费）。
     *
     * @return 启动提示
     */
    @Bean
    public String ivApprovalConsumerBanner() {
        log.info("[MQ消费] 已订阅库存域审批事件队列 {}（消费者随 iv 模块一起部署）",
                EventTopics.queueOf(EventTopics.DOMAIN_IV));
        return "iv-approval-consumer";
    }
}
