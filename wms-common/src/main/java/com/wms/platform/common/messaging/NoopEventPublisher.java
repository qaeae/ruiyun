package com.wms.platform.common.messaging;

import com.wms.platform.common.messaging.po.OutboxPo;
import lombok.extern.slf4j.Slf4j;

/**
 * 空发布器（未启用 MQ 时使用）。
 *
 * <p>行为是"什么都不做"而不是"假装成功"：调用方（{@link OutboxService}）在投递前会检查
 * {@code wms.messaging.publisher-enabled}，因此这里被调用说明配置有误——所以打 WARN，
 * 让"以为发了其实没发"这类问题在日志里立刻可见。
 */
@Slf4j
public class NoopEventPublisher implements EventPublisher {

    @Override
    public void publish(OutboxPo po) {
        log.warn("[发件箱] 未启用消息中间件，事件未投递（保持待投递，启用后会自动补发）id={} eventType={}",
                po.getId(), po.getEventType());
    }

    @Override
    public boolean available() {
        return false;
    }
}
