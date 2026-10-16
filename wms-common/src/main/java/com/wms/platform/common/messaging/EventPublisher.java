package com.wms.platform.common.messaging;

import com.wms.platform.common.messaging.po.OutboxPo;

/**
 * 事件发布器（把发件箱记录投给消息中间件）。
 *
 * <p>抽成接口而不是直接依赖 {@code RabbitTemplate} 的原因：**未启用 MQ 时应用也要能跑**
 * （本地开发、集成测试、CI 都不该被中间件卡住）。未启用时注入空实现，
 * 发件箱记录保持"待投递"，等启用后自然补发——数据不丢，只是延迟。
 *
 * <p>约定：{@link #publish} 失败必须抛异常，由 {@link OutboxService} 统一记错误并安排重试；
 * 不要吞掉异常，否则发件箱会误标"已投递"。
 */
public interface EventPublisher {

    /**
     * 投递一条事件。
     *
     * @param po 发件箱记录（含 eventType 与 payload JSON）
     * @throws BizException 投递失败（由调用方安排重试）——实现类抛出运行时异常即可，
     *                      这里不声明 {@code throws}：未检查异常本就不需要声明，
     *                      声明了反而会让架构规则误判为"业务代码直接依赖 JDK 异常类"
     */
    void publish(OutboxPo po);

    /**
     * 是否真正接入了消息中间件（用于日志与健康展示）。
     *
     * @return 已接入返回 true
     */
    boolean available();
}
