package com.wms.platform.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.approval.ApprovalActionHandler;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.common.approval.ApprovalBusiness;
import com.wms.platform.common.approval.ApprovalContext;
import com.wms.platform.common.approval.exec.ApprovalApprovedEvent;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 审批事件派发器：把消息解析成事件、找到执行方、执行（**各域消费者共用这一份实现**）。
 *
 * <p><b>为什么要有这个类</b>：按域拆消费者后（P3.1），每个域各写一份消费逻辑就会出现
 * "租户上下文没设""异常没处理""幂等说明不一致"等分叉。把这段易错的公共逻辑收在一处，
 * 各域消费者只剩一行：{@code dispatcher.dispatch(domain, message)}。
 *
 * <p><b>幂等是前提，不是选项</b>：MQ 是"至少一次"投递，同一条事件可能被消费多次
 * （投递后 broker 未确认、消费后 ack 丢失、发件箱重投……）。执行方自带幂等键
 * （库存调整 {@code APPROVAL:{approvalNo}}、结算下发同款），重复消费不会重复扣减/重复付款。
 *
 * <p><b>租户上下文</b>：MQ 消费线程没有 HTTP 请求，{@code TenantContext} 是 ThreadLocal，
 * 必须在这里显式设置（否则下游 Mapper 拿到空租户：要么查不到数据、要么写错租户），
 * 消费结束后清理，避免线程复用时串号。
 *
 * <p><b>失败处理</b>：抛异常 → Spring AMQP 按配置重试 → 重试耗尽进**本域死信队列**
 * （不静默丢消息，人工可在 DLQ 里看到并处理）。
 */
@Slf4j
@RequiredArgsConstructor
public class ApprovalEventDispatcher {

    /** JSON。 */
    private final ObjectMapper objectMapper;

    /** 执行方注册表（只包含本服务 classpath 上的实现）。 */
    private final ApprovalBusiness approvalBusiness;

    /**
     * 派发一条审批通过事件。
     *
     * @param domain  域码（仅用于日志与自检，避免消费错域的事件）
     * @param message 原始消息
     */
    public void dispatch(String domain, Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String messageId = message.getMessageProperties() == null
                ? null : message.getMessageProperties().getMessageId();
        ApprovalApprovedEvent event = parse(body, messageId);
        ApprovalBizType bizType = ApprovalBizType.of(event.bizType());
        // 1. 自检：事件归属的域与本消费者不一致 = 路由/绑定配错了，宁可失败也不要错误执行
        if (!domain.equals(bizType.domainCode())) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "事件归属域与队列不一致：queue=" + domain + " bizType=" + event.bizType()
                            + "，请检查路由键与队列绑定");
        }
        try {
            // 2. 恢复全链路 traceId（P4）：消费线程没有原请求的 MDC，
            //    从消息头取回后写入 MDC，于是"这次消费属于哪次审批请求"在日志里可直接搜到
            Object headerTraceId = message.getMessageProperties() == null
                    ? null : message.getMessageProperties().getHeaders().get(HeaderConst.TRACE_ID);
            if (headerTraceId != null) {
                org.slf4j.MDC.put(TraceConst.MDC_TRACE_ID, String.valueOf(headerTraceId));
            }
            // 3. 设置租户上下文（消费线程没有 HTTP 上下文）
            TenantContext.set(event.tenantId(), event.approverId(), Set.of(), null);
            // 4. 找到执行方并执行（执行方内部用自己的幂等键保证重复消费安全）
            ApprovalActionHandler handler = approvalBusiness.findHandler(bizType);
            handler.execute(new ApprovalContext(event.tenantId(), event.approvalId(), event.approvalNo(),
                    bizType, event.bizId(), event.bizNo(), event.warehouseId(), event.merchantId(),
                    event.amount() == null ? null : new BigDecimal(event.amount()),
                    event.payloadJson(), event.payloadHash(), event.reason(), event.applyBy(), event.approverId()));
            log.info("[MQ消费] 审批事件已执行 domain={} approvalNo={} bizType={} messageId={}",
                    domain, event.approvalNo(), event.bizType(), messageId);
        } finally {
            // 5. 清理 ThreadLocal 与 MDC（线程池复用会串号）
            TenantContext.clear();
            org.slf4j.MDC.remove(TraceConst.MDC_TRACE_ID);
        }
    }

    /**
     * 解析事件载荷。
     *
     * @param body      载荷
     * @param messageId 消息 ID（日志用）
     * @return 事件
     */
    private ApprovalApprovedEvent parse(String body, String messageId) {
        try {
            return objectMapper.readValue(body, ApprovalApprovedEvent.class);
        } catch (Exception ex) {
            // 载荷不可解析 = 生产者/消费者契约不一致，重试也不会好 → 抛出让其进死信队列人工处理
            log.error("[MQ消费] 事件载荷无法解析，转死信 messageId={} body={}", messageId, body, ex);
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "审批事件载荷无法解析（生产者与消费者契约不一致？）", ex);
        }
    }
}
