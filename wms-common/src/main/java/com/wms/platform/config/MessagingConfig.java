package com.wms.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.approval.ApprovalBusiness;
import com.wms.platform.common.approval.exec.ApprovalExecutor;
import com.wms.platform.common.approval.exec.InProcessApprovalExecutor;
import com.wms.platform.common.approval.exec.OutboxApprovalExecutor;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.messaging.ApprovalEventDispatcher;
import com.wms.platform.common.messaging.EventPublisher;
import com.wms.platform.common.messaging.NoopEventPublisher;
import com.wms.platform.common.messaging.OutboxService;
import com.wms.platform.common.messaging.mapper.OutboxMapper;
import com.wms.platform.common.messaging.rabbit.RabbitEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息装配：**按 {@code wms.messaging.mode} 选择执行策略与发布器**。
 *
 * <p>这是"单体 → 微服务"演进里最关键的可切换点：
 * <ul>
 *   <li>{@code NONE}：审批通过同事务直接执行（强一致，默认，IT 不需要 broker）；</li>
 *   <li>{@code RABBIT}：写发件箱 → 投 MQ → 消费执行（最终一致，跨进程）。</li>
 * </ul>
 * 切换只改配置，不改业务代码——审批服务注入的都是 {@link ApprovalExecutor} 接口。
 */
@Slf4j
@Configuration
public class MessagingConfig {

    /**
     * 消息发布器：RABBIT 模式用真实发布器，否则用空实现。
     *
     * <p>注意 {@code @ConditionalOnProperty} 用 {@code mode=RABBIT} 判定：
     * 未启用时装配空实现，发件箱记录保持"待投递"等启用后补发（数据不丢、只是慢）。
     *
     * @param rabbitTemplate Rabbit 模板
     * @return 发布器
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
    public EventPublisher rabbitEventPublisher(ObjectMapper objectMapper, RabbitTemplate rabbitTemplate) {
        log.info("[消息] 启用 RabbitMQ 发布器（事务性发件箱投递）");
        return new RabbitEventPublisher(objectMapper, rabbitTemplate);
    }

    /**
     * 空发布器（默认）。
     *
     * @return 发布器
     */
    @Bean
    // 用"配置值互斥"而不是 @ConditionalOnMissingBean：后者只在**自动配置类**里可靠，
    // 在普通 @Configuration 中 Bean 定义顺序未定义 → 实测出现过两个执行策略同时注册、启动报
    // "required a single bean, but 2 were found"。按配置值互斥是确定性的。
    @ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "NONE", matchIfMissing = true)
    public EventPublisher noopEventPublisher() {
        return new NoopEventPublisher();
    }

    /**
     * 发件箱服务（所有模式都有：即使不走 MQ，也能记录"待投递事件"备查）。
     *
     * @param outboxMapper   发件箱 Mapper
     * @param idGenerator    雪花 ID
     * @param eventPublisher 发布器
     * @param wmsProperties  配置
     * @return 发件箱服务
     */
    @Bean
    public OutboxService outboxService(OutboxMapper outboxMapper, SnowflakeIdGenerator idGenerator,
                                       EventPublisher eventPublisher, WmsProperties wmsProperties) {
        return new OutboxService(outboxMapper, idGenerator, eventPublisher, wmsProperties);
    }

    /**
     * 进程内执行策略（默认，强一致）。
     *
     * @param approvalBusiness 执行方注册表
     * @return 执行策略
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "NONE", matchIfMissing = true)
    public ApprovalExecutor inProcessApprovalExecutor(ApprovalBusiness approvalBusiness) {
        log.info("[审批] 执行策略=进程内直接执行（强一致；如需事件化请设 wms.messaging.mode=RABBIT）");
        return new InProcessApprovalExecutor(approvalBusiness);
    }

    /**
     * 审批事件派发器（各域消费者共用）。
     *
     * <p>只在 RABBIT 模式下装配：NONE 模式下执行在审批事务内直接完成，没有消费者。
     *
     * @param objectMapper     JSON
     * @param approvalBusiness 执行方注册表
     * @return 派发器
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
    public ApprovalEventDispatcher approvalEventDispatcher(ObjectMapper objectMapper,
                                                           ApprovalBusiness approvalBusiness) {
        return new ApprovalEventDispatcher(objectMapper, approvalBusiness);
    }

    /**
     * 发件箱 + 事件执行策略（{@code mode=RABBIT} 时启用，最终一致）。
     *
     * @param outboxService 发件箱
     * @param objectMapper  JSON
     * @return 执行策略
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.messaging", name = "mode", havingValue = "RABBIT")
    public ApprovalExecutor outboxApprovalExecutor(OutboxService outboxService, ObjectMapper objectMapper) {
        log.info("[审批] 执行策略=发件箱 + MQ 事件化执行（最终一致）");
        return new OutboxApprovalExecutor(outboxService, objectMapper);
    }
}
