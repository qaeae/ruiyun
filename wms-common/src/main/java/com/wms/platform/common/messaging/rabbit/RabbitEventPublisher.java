package com.wms.platform.common.messaging.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.common.messaging.EventPublisher;
import com.wms.platform.common.messaging.EventTopics;
import com.wms.platform.common.messaging.po.OutboxPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ 发布器。
 *
 * <p><b>可靠性三件套</b>：
 * <ol>
 *   <li><b>持久化消息</b>：{@code deliveryMode=PERSISTENT}，broker 重启不丢；</li>
 *   <li><b>发布确认</b>：配置 {@code publisher-confirm-type=correlated}，broker 确认后才算成功
 *       （未确认会抛异常 → 发件箱记错误并重试）；</li>
 *   <li><b>路由键随事件落库</b>（P3.1）：路由决策在"写发件箱"时一次确定并持久化，
 *       投递阶段只读取——避免"投递时才判断、判错就投到无人绑定的交换机"。</li>
 * </ol>
 *
 * <p>消息体直接发送 payload JSON 字符串（不二次包裹）：消费侧拿到的是什么，排查时在管理台看到的就是什么。
 * 关联 ID 用发件箱记录 ID，便于"从管理台一条消息反查到哪次业务动作"。
 */
@Slf4j
@RequiredArgsConstructor
public class RabbitEventPublisher implements EventPublisher {

    /** JSON（路由键缺失时从载荷推导，兼容历史数据）。 */
    private final ObjectMapper objectMapper;

    /** Rabbit 模板（含发布确认配置）。 */
    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(OutboxPo po) {
        String routingKey = resolveRoutingKey(po);
        CorrelationData correlation = new CorrelationData("outbox-" + po.getId());
        rabbitTemplate.convertAndSend(EventTopics.APPROVAL_EXCHANGE, routingKey,
                po.getPayload().getBytes(StandardCharsets.UTF_8), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setMessageId(String.valueOf(po.getId()));
                    // 全链路（P4）：把原始请求的 traceId 放进消息头，消费侧据此恢复上下文
                    String traceId = po.getTraceId() == null || po.getTraceId().isBlank()
                            ? org.slf4j.MDC.get(TraceConst.MDC_TRACE_ID) : po.getTraceId();
                    if (traceId != null && !traceId.isBlank()) {
                        message.getMessageProperties().setHeader(HeaderConst.TRACE_ID, traceId);
                    }
                    return message;
                }, correlation);
        log.debug("[MQ] 已投递 eventType={} routingKey={} outboxId={}", po.getEventType(), routingKey, po.getId());
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * 解析投递路由键（三级兜底）。
     *
     * <p><b>为什么允许从载荷推导</b>：路由键是 P3.1 才落库的字段，历史数据可能是空的。
     * 此时若退化成"用事件类型当路由键"，会投到一个**没有任何绑定的交换机**上——
     * RabbitMQ 对不可路由的消息默认**直接丢弃**，于是又回到
     * "审批通过了但业务永不执行"的最坏情况。从载荷解析 bizType 再推导，老数据也能投到正确的域队列。
     *
     * @param po 发件箱记录
     * @return 路由键
     */
    private String resolveRoutingKey(OutboxPo po) {
        if (po.getRoutingKey() != null && !po.getRoutingKey().isBlank()) {
            return po.getRoutingKey();
        }
        String derived = deriveFromPayload(po.getPayload());
        if (derived != null) {
            log.warn("[MQ] 发件箱记录缺少路由键，已从载荷推导 id={} routingKey={}（建议排查写入方）",
                    po.getId(), derived);
            return derived;
        }
        log.error("[MQ] 无法确定路由键，将按事件类型投递 id={} eventType={}（很可能不可路由而被丢弃）",
                po.getId(), po.getEventType());
        return po.getEventType();
    }

    /**
     * 从载荷 JSON 推导路由键。
     *
     * @param payload 载荷
     * @return 路由键；无法推导返回 {@code null}
     */
    private String deriveFromPayload(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode bizType = node.get("bizType");
            if (bizType == null || bizType.isNull()) {
                return null;
            }
            return ApprovalBizType.of(bizType.asText()).routingKey();
        } catch (Exception ex) {
            log.warn("[MQ] 从载荷推导路由键失败：{}", ex.getMessage());
            return null;
        }
    }
}
