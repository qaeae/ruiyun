package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道未回调补查任务（`04` §5.3：「每 10 分钟扫描 `ac_channel_order.status = '处理中'`
 * 且 `sent_at < now() − 5min` → **主动查询通道**补正」）。
 *
 * <p><b>它解决的真实问题</b>：回调可能永远不到（通道侧故障、网络抖动、地址配错）。
 * 只等回调的系统会出现"钱已经划出去了，但平台状态还停在处理中"——
 * 于是批次永远不结、对账永远对不上，而且**没有任何错误日志**（因为根本没人调用我们）。
 * 主动补查是唯一能发现这类问题的手段。
 *
 * <p><b>K2 未接入时它做什么（诚实说明）</b>：`ac_channel_order` 表在一期已建但**还没有写入方**
 * （真实通道适配器待 K2），所以本任务目前是"扫 0 条并如实报告"，而不是假装在补查。
 * 这类"跑得通但业务上还没有输入"的任务，摘要必须写明原因——
 * 否则运维看到 `0 条` 会误以为"通道一切正常"，而真相是"通道根本还没接"。
 * K2 到位后只需在 `PaymentInstructionPort` 真实实现里落 `ac_channel_order`，本任务自动开始生效。
 */
@Component
@RequiredArgsConstructor
public class ChannelQueryRetryJob implements JobHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ChannelQueryRetryJob.class);

    /** 超过该时长仍未回调即主动查询（分钟）：`04` §5.3 取 5 分钟。 */
    private static final int RETRY_AFTER_MINUTES = 5;

    /** JDBC（只读扫描 + 留痕）。 */
    private final JdbcTemplate jdbc;

    @Override
    public JobName supportJob() {
        return JobName.CHANNEL_QUERY_RETRY;
    }

    @Override
    public String execute() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(RETRY_AFTER_MINUTES);
        // 1. 捞"发出去超过 5 分钟仍处理中"的通道指令
        List<String> stuck = jdbc.queryForList(
                "SELECT channel_txn_no FROM ac_channel_order WHERE tenant_id = 1 AND deleted = 0 "
                        + "AND status = '处理中' AND sent_at < ? ORDER BY sent_at",
                String.class, deadline);
        // 2. 通道未接入时如实说明（避免"0 条"被误读为"一切正常"）
        Integer totalChannelOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ac_channel_order WHERE tenant_id = 1 AND deleted = 0", Integer.class);
        if (totalChannelOrders == null || totalChannelOrders == 0) {
            return String.format("扫描 %d 分钟以上未回调的指令：0 条；"
                            + "但 ac_channel_order 无任何记录 → 通道尚未接入（K2 未启动），本任务当前无实际作用对象",
                    RETRY_AFTER_MINUTES);
        }
        if (!stuck.isEmpty()) {
            log.error("[通道补查告警] {} 笔指令发出超过 {} 分钟仍未回调，需主动查询通道补正：{}", stuck.size(),
                    RETRY_AFTER_MINUTES, stuck);
        }
        return String.format("扫描 %d 分钟以上未回调的指令：%d 条（通道指令总数 %d）%s", RETRY_AFTER_MINUTES,
                stuck.size(), totalChannelOrders, stuck.isEmpty() ? "" : " → " + stuck);
    }
}
