package com.wms.platform.common.messaging.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事务性发件箱记录（{@code pf_outbox}）。
 *
 * <p><b>为什么必须有这张表</b>：业务变更（审批单置为已通过）与"要发消息"必须落在**同一个本地事务**里。
 * 若先提交事务再发 MQ：进程在两步之间崩溃 = 业务已生效但消息永远丢失（下游永远不执行）。
 * 若先发 MQ 再提交：消息可能先被消费，而业务随后回滚了（下游执行了不该执行的事）。
 * 事务性发件箱把"要发什么"与业务数据写进同一个库事务，再异步投递：
 * **最多重复投递，绝不丢失**（重复由消费侧的幂等键兜住）。
 */
@Getter
@Setter
@TableName("pf_outbox")
public class OutboxPo {

    /** 待投递。 */
    public static final String STATUS_PENDING = "待投递";

    /** 已投递。 */
    public static final String STATUS_SENT = "已投递";

    /** 失败（重试耗尽）。 */
    public static final String STATUS_FAILED = "失败";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 事件类型（如 APPROVAL_APPROVED）。 */
    private String eventType;

    /** 投递路由键（如 approval.approved.iv）——路由决策在"写"的时候一次确定并落库。 */
    private String routingKey;

    /**
     * 产生该事件的请求 traceId（P4 全链路追踪）。
     *
     * <p>必须落库而不是只放 MDC：投递由**定时任务线程**执行，那时 MDC 里没有原始请求的 traceId。
     * 落库后，补投与消费日志都能打出原始请求的 traceId，一条链路可全程串起来。
     */
    private String traceId;

    /** 聚合根类型（如 APPROVAL）。 */
    private String aggregateType;

    /** 聚合根 ID。 */
    private Long aggregateId;

    /** 事件载荷（JSON 字符串）。 */
    private String payload;

    /** 状态：待投递/已投递/失败。 */
    private String status;

    /** 已重试次数。 */
    private Integer retryCount;

    /** 下次重试时间（投递成功后置空）。 */
    private LocalDateTime nextRetryAt;

    /** 最后一次错误（截断存储，供排查）。 */
    private String lastError;

    /** 创建时间（DB 默认值）。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4：禁止 new + 连续 setter）。
     *
     * @param id            主键
     * @param tenantId      租户
     * @param eventType     事件类型
     * @param traceId       产生事件的请求 traceId（可空）
     * @param routingKey    投递路由键
     * @param aggregateType 聚合根类型
     * @param aggregateId   聚合根 ID
     * @param payload       载荷 JSON
     * @return 发件箱记录（状态=待投递，立即可投递）
     */
    public static OutboxPo of(Long id, Long tenantId, String eventType, String routingKey, String traceId,
                              String aggregateType, Long aggregateId, String payload) {
        OutboxPo po = new OutboxPo();
        po.id = id;
        po.tenantId = tenantId;
        po.eventType = eventType;
        po.routingKey = routingKey;
        po.traceId = traceId;
        po.aggregateType = aggregateType;
        po.aggregateId = aggregateId;
        po.payload = payload;
        po.status = STATUS_PENDING;
        po.retryCount = 0;
        po.nextRetryAt = LocalDateTime.now();
        po.deleted = 0;
        return po;
    }

    /**
     * 标记已投递（语义方法：避免在 Service 里直接改字段——B4/状态机纪律）。
     *
     * @param operatorId 操作人（系统任务传 null）
     */
    public void markSent(Long operatorId) {
        this.status = STATUS_SENT;
        this.lastError = null;
        this.nextRetryAt = null;
        this.updatedBy = operatorId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记投递失败并安排下次重试；超过上限则置为"失败"（由人工或告警介入，不会静默丢弃）。
     *
     * @param error       错误信息
     * @param nextRetryAt 下次重试时间
     * @param maxRetry    最大重试次数
     * @param operatorId  操作人
     */
    public void markFailed(String error, LocalDateTime nextRetryAt, int maxRetry, Long operatorId) {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
        this.lastError = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
        this.status = this.retryCount >= maxRetry ? STATUS_FAILED : STATUS_PENDING;
        this.nextRetryAt = nextRetryAt;
        this.updatedBy = operatorId;
        this.updatedAt = LocalDateTime.now();
    }
}
