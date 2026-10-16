package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结算批次卡单告警任务。
 *
 * <p><b>为什么这个告警值得单独做</b>：结算批次卡住 = **钱没付出去**。
 * 对账已确认、批次已生成，但没人下发（忘记点）或下发失败（通道报错未跟进）——
 * 这两种情况都不会有人主动上报，只会等到承运商来催款。
 * 承运商催款时再查，往往已经拖过账期，商务上很被动。
 *
 * <p>只读任务：只告警，不改状态（失败批次的重试要走复核流程 `au_approval`，本任务无权代劳）。
 */
@Component
@RequiredArgsConstructor
public class SettleStuckAlertJob implements JobHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(SettleStuckAlertJob.class);

    /** 卡单判定时长（天）：超过 1 天未下发即视为卡住。 */
    private static final int STUCK_DAYS = 1;

    /** JDBC（只读告警查询）。 */
    private final JdbcTemplate jdbc;

    @Override
    public JobName supportJob() {
        return JobName.SETTLE_STUCK_ALERT;
    }

    @Override
    public String execute() {
        // 1. 待下发超期（有人生成批次但忘了下发）
        List<String> pending = jdbc.queryForList(
                "SELECT stb_no FROM fn_settle_batch WHERE tenant_id = 1 AND deleted = 0 AND status = '待下发' "
                        + "AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) ORDER BY created_at",
                String.class, STUCK_DAYS);
        // 2. 下发失败（通道报错，需要人工核实后重试）
        List<String> failed = jdbc.queryForList(
                "SELECT CONCAT(stb_no, '(', IFNULL(fail_reason, '无原因'), ')') FROM fn_settle_batch "
                        + "WHERE tenant_id = 1 AND deleted = 0 AND status = '下发失败' ORDER BY created_at",
                String.class);
        if (!pending.isEmpty()) {
            log.error("[结算卡单告警] {} 个批次生成后超过 {} 天未下发（钱没付出去）：{}", pending.size(), STUCK_DAYS,
                    pending);
        }
        if (!failed.isEmpty()) {
            log.error("[结算失败告警] {} 个批次下发失败，需人工核实后重走复核流程：{}", failed.size(), failed);
        }
        return String.format("待下发超期 %d 个%s；下发失败 %d 个%s", pending.size(),
                pending.isEmpty() ? "" : pending.toString(), failed.size(),
                failed.isEmpty() ? "" : failed.toString());
    }
}
