package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.common.messaging.OutboxService;
import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发件箱补投任务（每分钟）。
 *
 * <p><b>为什么必须有它</b>：审批通过时虽然会"提交后立即投递一次"，但那次是 best-effort——
 * 网络抖动、broker 重启、进程在提交后立刻崩溃，都会导致事件没发出去。
 * 没有补投任务，这些事件就永远躺在发件箱里：**业务已通过、下游永远不执行**，
 * 而这恰恰是最难发现的一类故障（审批单看着是"已通过"，没人知道业务没生效）。
 *
 * <p>与"立即投递"的分工：立即投递解决**延迟**（毫秒级生效），本任务解决**可靠性**（最终一定发出）。
 * 投递是至少一次，重复由消费侧幂等键兜住。
 */
@Component
@RequiredArgsConstructor
public class OutboxRelayJob implements JobHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    /** 发件箱服务。 */
    private final OutboxService outboxService;

    @Override
    public JobName supportJob() {
        return JobName.OUTBOX_RELAY;
    }

    @Override
    public String execute() {
        // 投递所有到期的待投递事件（成功置"已投递"，失败按退避重排）
        int sent = outboxService.publishDue();
        log.info("[发件箱补投] 本次成功投递 {} 条", sent);
        return "投递成功 " + sent + " 条；失败或未到期记录留待下轮";
    }
}
