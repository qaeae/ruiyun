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
 * 对账差异超期告警任务（`04` §六：「差额 → T+1 告警，**T+3 内处理完毕**」）。
 *
 * <p><b>为什么需要"超期"这个动作，而不只是"有差异就报"</b>：差异当天存在是正常的
 * （对账本来就是为了发现差异），制度要求的是**限时处理**。若只报"有差异"，
 * 一条挂了两个月的差异会混在当天的新差异里，谁也看不出哪条该催。
 * 本任务专门捞"超过 3 天仍未确认"的对账单——这才是需要升级处理的对象。
 *
 * <p>只读任务：不改任何业务数据，只告警。这类任务的风险最低，也最该先上
 * （它能让"制度写了但没人执行"变成可见的问题）。
 */
@Component
@RequiredArgsConstructor
public class ReconcileBillAlertJob implements JobHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ReconcileBillAlertJob.class);

    /** 制度时限（天）：T+3 内处理完毕。 */
    private static final int OVERDUE_DAYS = 3;

    /** JDBC（只读告警查询，无需 Service 封装）。 */
    private final JdbcTemplate jdbc;

    @Override
    public JobName supportJob() {
        return JobName.RECONCILE_BILL_ALERT;
    }

    @Override
    public String execute() {
        // 1. 捞超期未确认的对账单（状态仍为「差异待处理」且创建超过 3 天）
        List<String> overdue = jdbc.queryForList(
                "SELECT rcl_no FROM fn_reconcile_bill WHERE tenant_id = 1 AND deleted = 0 "
                        + "AND status = '差异待处理' AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "ORDER BY created_at",
                String.class, OVERDUE_DAYS);
        // 2. 一并统计当前待处理总数，便于判断"是偶发还是积压"
        Integer openTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fn_reconcile_bill WHERE tenant_id = 1 AND deleted = 0 "
                        + "AND status = '差异待处理'", Integer.class);
        if (!overdue.isEmpty()) {
            // 告警文案要能直接指导动作：单号（去查哪张单）+ 超期天数 + 制度要求
            log.error("[对账超期告警] {} 张对账单超过 {} 天未处理（制度要求 T+{} 内处理完毕）：{}",
                    overdue.size(), OVERDUE_DAYS, OVERDUE_DAYS, overdue);
        }
        return String.format("待处理对账单 %d 张，其中超期（>%d 天）%d 张%s",
                openTotal == null ? 0 : openTotal, OVERDUE_DAYS, overdue.size(),
                overdue.isEmpty() ? "" : " → 超期单号：" + overdue);
    }
}
