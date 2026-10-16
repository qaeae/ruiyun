package com.wms.platform.common.messaging.rabbit;

import com.wms.platform.common.messaging.EventTopics;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（仅 {@code wms.messaging.mode=RABBIT} 时生效）：**每个域一个队列**。
 *
 * <pre>
 *   exchange wms.approval（topic）
 *     ├─ approval.approved.iv  → queue wms.approval.iv   → DLQ wms.approval.iv.dlq
 *     ├─ approval.approved.fns → queue wms.approval.fns  → DLQ wms.approval.fns.dlq
 *     └─ approval.approved.md  → queue wms.approval.md   → DLQ wms.approval.md.dlq
 * </pre>
 *
 * <p><b>为什么改成按域隔离</b>：早期所有服务共享一个队列 ⇒ 每个带 AMQP 的服务都进入竞争组，
 * `wms-auth` 抢走结算事件却没有对应执行器 → 重试耗尽 → 进死信队列
 * （症状："审批已通过但业务不生效"，且取决于谁先抢到消息）。按域隔离后，
 * **队列归属跟随模块归属**——服务没依赖 iv 模块，就根本不存在库存域的消费者。
 *
 * <p><b>为什么用代码声明而不是只在管理台手建</b>：队列/交换机/绑定是**应用契约的一部分**，
 * 手工建会漏（新环境部署时忘记建 → 消息被投到没有绑定的交换机上直接丢弃）。
 * 声明式拓扑在应用启动时幂等创建，新环境一次跑起来就是对的。
 *
 * <p><b>为什么所有域的队列都在这里声明（哪怕该域消费者没部署）</b>：
 * "未接入执行方"的事件会**堆在队列里可见**（有积压 = 有人该处理但没处理），
 * 而不是因为"没有绑定"被 broker 静默丢弃。可观测性是排障的前提。
 *
 * <p><b>死信队列（DLQ）不是可选项</b>：消费失败重试耗尽后，消息必须落到某个地方被人看见。
 * 没有 DLQ 的话，重试耗尽后消息要么被丢弃、要么堆积在业务队列头部阻塞后续消息——
 * 两种情况都会让"某笔审批通过了但业务没执行"变成静默故障。
 */
@Configuration
@ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
public class RabbitMessagingConfig {

    /**
     * 审批事件交换机（topic：便于后续按前缀订阅多个事件）。
     *
     * @return 交换机
     */
    @Bean
    public TopicExchange approvalExchange() {
        return new TopicExchange(EventTopics.APPROVAL_EXCHANGE, true, false);
    }

    /**
     * 死信交换机。
     *
     * @return 交换机
     */
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(EventTopics.DLX_EXCHANGE, true, false);
    }

    /**
     * 库存域队列（带死信路由到本域 DLQ）。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalIvQueue() {
        return domainQueue(EventTopics.DOMAIN_IV);
    }

    /**
     * 财务域队列。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalFnsQueue() {
        return domainQueue(EventTopics.DOMAIN_FNS);
    }

    /**
     * 主数据域队列。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalMdQueue() {
        return domainQueue(EventTopics.DOMAIN_MD);
    }

    /**
     * 库存域死信队列（人工排查入口）。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalIvDlq() {
        return QueueBuilder.durable(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_IV)).build();
    }

    /**
     * 财务域死信队列。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalFnsDlq() {
        return QueueBuilder.durable(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_FNS)).build();
    }

    /**
     * 主数据域死信队列。
     *
     * @return 队列
     */
    @Bean
    public Queue approvalMdDlq() {
        return QueueBuilder.durable(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_MD)).build();
    }

    /**
     * 库存域绑定：{@code approval.approved.iv} → 库存队列。
     *
     * @param approvalIvQueue  库存队列
     * @param approvalExchange 审批交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalIvBinding(Queue approvalIvQueue, TopicExchange approvalExchange) {
        return BindingBuilder.bind(approvalIvQueue).to(approvalExchange)
                .with(EventTopics.routingKeyOf(EventTopics.DOMAIN_IV));
    }

    /**
     * 财务域绑定。
     *
     * @param approvalFnsQueue 财务队列
     * @param approvalExchange 审批交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalFnsBinding(Queue approvalFnsQueue, TopicExchange approvalExchange) {
        return BindingBuilder.bind(approvalFnsQueue).to(approvalExchange)
                .with(EventTopics.routingKeyOf(EventTopics.DOMAIN_FNS));
    }

    /**
     * 主数据域绑定。
     *
     * @param approvalMdQueue  主数据队列
     * @param approvalExchange 审批交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalMdBinding(Queue approvalMdQueue, TopicExchange approvalExchange) {
        return BindingBuilder.bind(approvalMdQueue).to(approvalExchange)
                .with(EventTopics.routingKeyOf(EventTopics.DOMAIN_MD));
    }

    /**
     * 库存 DLQ 绑定。
     *
     * @param approvalIvDlq 库存 DLQ
     * @param dlxExchange   死信交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalIvDlqBinding(Queue approvalIvDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(approvalIvDlq).to(dlxExchange)
                .with(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_IV));
    }

    /**
     * 财务 DLQ 绑定。
     *
     * @param approvalFnsDlq 财务 DLQ
     * @param dlxExchange    死信交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalFnsDlqBinding(Queue approvalFnsDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(approvalFnsDlq).to(dlxExchange)
                .with(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_FNS));
    }

    /**
     * 主数据 DLQ 绑定。
     *
     * @param approvalMdDlq 主数据 DLQ
     * @param dlxExchange   死信交换机
     * @return 绑定
     */
    @Bean
    public Binding approvalMdDlqBinding(Queue approvalMdDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(approvalMdDlq).to(dlxExchange)
                .with(EventTopics.deadLetterQueueOf(EventTopics.DOMAIN_MD));
    }

    /**
     * 构造某域的队列（带死信路由到本域 DLQ）。
     *
     * @param domain 域码
     * @return 队列
     */
    private Queue domainQueue(String domain) {
        return QueueBuilder.durable(EventTopics.queueOf(domain))
                .deadLetterExchange(EventTopics.DLX_EXCHANGE)
                .deadLetterRoutingKey(EventTopics.deadLetterQueueOf(domain))
                .build();
    }
}
