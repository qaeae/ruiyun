package com.wms.platform.common.messaging;

import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.messaging.mapper.OutboxMapper;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.common.messaging.po.OutboxPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发件箱写入与投递（事务性发件箱的"写"与"投"两半）。
 *
 * <p><b>写</b>（{@link #write}）：在**调用方的事务里**插入一条待投递记录。
 * 因为审批状态变更与这条记录在同一个数据库事务里，所以二者要么都成功、要么都失败，
 * 不存在"审批通过了但事件没记下来"的中间态。
 *
 * <p><b>投</b>（{@link #publishDue}）：按顺序取出到期的待投递记录并投给 MQ，
 * 成功则置为"已投递"，失败则记错误、按退避时间安排重试。
 * 投递是**至少一次**语义（消息可能重复），所以消费侧必须幂等——本项目已有幂等键
 * （{@code APPROVAL:{approvalNo}}），这正是"发件箱 + 幂等消费"能成立的前提。
 *
 * <p><b>谁来驱动投递</b>：两种触发同时存在，互为补充：
 * <ol>
 *   <li><b>立即尝试</b>：业务事务提交后马上投一次（把端到端延迟从"一个定时周期"压到毫秒级，E2E 才不用等）；</li>
 *   <li><b>定时补投</b>：由 ops 的 {@code OUTBOX_RELAY} 任务周期扫描（进程崩溃/网络抖动后自动补上）。</li>
 * </ol>
 * 只有定时补投时，延迟取决于扫描周期；只有立即投递时，崩溃就会丢消息。两者都要。
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    /** 单批最大条数（避免一次拉太多把内存打满）。 */
    private static final int BATCH_SIZE = 200;

    /** 首次重试退避（秒）。 */
    private static final long BACKOFF_BASE_SECONDS = 5L;

    /** 最大重试次数（超过则置"失败"，由告警/人工介入，不静默丢弃）。 */
    private static final int MAX_RETRY = 8;

    /** 发件箱 Mapper。 */
    private final OutboxMapper outboxMapper;

    /** 雪花 ID 生成器。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 消息发布器（未启用 MQ 时为空实现）。 */
    private final EventPublisher eventPublisher;

    /** 配置（投递开关与最大重试）。 */
    private final WmsProperties wmsProperties;

    /**
     * 写入一条待投递事件（**在调用方事务内**）。
     *
     * @param tenantId      租户
     * @param eventType     事件类型
     * @param routingKey    投递路由键（由业务类型决定，见 ApprovalBizType#routingKey）
     * @param aggregateType 聚合根类型
     * @param aggregateId   聚合根 ID
     * @param payloadJson   载荷 JSON
     * @return 记录 ID
     */
    public long write(long tenantId, String eventType, String routingKey, String aggregateType,
                      long aggregateId, String payloadJson) {
        long id = idGenerator.nextId();
        // 记下产生该事件的请求 traceId：补投是定时线程执行的，MDC 里没有它（P4 全链路）
        String traceId = org.slf4j.MDC.get(TraceConst.MDC_TRACE_ID);
        outboxMapper.insert(OutboxPo.of(id, tenantId, eventType, routingKey, traceId,
                aggregateType, aggregateId, payloadJson));
        return id;
    }

    /**
     * 立即投递一条（业务事务提交后调用，best-effort）。
     *
     * <p>失败只记日志：真正的保障是定时补投，因此这里**不向业务抛异常**——
     * "消息发慢一点"不该让"审批通过"这个动作失败。
     *
     * @param tenantId 租户
     * @param outboxId 记录 ID
     * @return 是否投递成功
     */
    public boolean publishNow(long tenantId, long outboxId) {
        OutboxPo po = outboxMapper.selectById(outboxId);
        if (po == null || !OutboxPo.STATUS_PENDING.equals(po.getStatus())) {
            return true;
        }
        return publishOne(po);
    }

    /**
     * 扫描并投递到期的待投递记录（定时任务入口）。
     *
     * @return 本次成功投递条数
     */
    public int publishDue() {
        if (!wmsProperties.getMessaging().isPublisherEnabled()) {
            // 未启用 MQ：不发也不改状态（保持"待投递"，等启用后自然补发）
            return 0;
        }
        List<OutboxPo> due = outboxMapper.selectDue(BATCH_SIZE);
        int sent = 0;
        for (OutboxPo po : due) {
            if (publishOne(po)) {
                sent++;
            }
        }
        if (!due.isEmpty()) {
            log.info("[发件箱] 本轮扫描 {} 条，成功投递 {} 条", due.size(), sent);
        }
        return sent;
    }

    /**
     * 投递单条并更新状态。
     *
     * @param po 记录
     * @return 是否成功
     */
    private boolean publishOne(OutboxPo po) {
        try {
            eventPublisher.publish(po);
            // 条件更新：并发下只有第一个成功者能置为已投递（避免重复计数）
            int affected = outboxMapper.markSent(po.getTenantId(), po.getId());
            if (affected == 0) {
                log.debug("[发件箱] 记录已被其它线程投递 id={}（幂等跳过）", po.getId());
            }
            return true;
        } catch (Exception ex) {
            scheduleRetry(po, ex);
            return false;
        }
    }

    /**
     * 记录失败并按退避安排下次重试。
     *
     * @param po 记录
     * @param ex 异常
     */
    private void scheduleRetry(OutboxPo po, Exception ex) {
        int retry = (po.getRetryCount() == null ? 0 : po.getRetryCount()) + 1;
        long backoffSeconds = BACKOFF_BASE_SECONDS * Math.min(retry, 12);
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        String status = retry >= MAX_RETRY ? OutboxPo.STATUS_FAILED : OutboxPo.STATUS_PENDING;
        outboxMapper.markFailed(po.getTenantId(), po.getId(), ex.getMessage(), nextRetryAt, status);
        if (OutboxPo.STATUS_FAILED.equals(status)) {
            log.error("[发件箱] 投递失败已达上限，转人工处理 id={} eventType={} 原因={}",
                    po.getId(), po.getEventType(), ex.getMessage());
        } else {
            log.warn("[发件箱] 投递失败，{} 秒后重试（第 {} 次）id={} 原因={}",
                    backoffSeconds, retry, po.getId(), ex.getMessage());
        }
    }
}
