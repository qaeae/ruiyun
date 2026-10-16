package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.modules.ops.app.remote.FnsTaskPort;
import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 日终勾稽任务（`04` §5.2：「日终勾稽四层与差异告警」）。
 *
 * <p><b>为什么跑 T-1 而不是当天</b>：当天数据还在变（运单在途、回调在途、批次在生成），
 * 拿"还在动的账"去勾稽必然报差异。跑 T-1 才能得到一份**可复现**的结论
 * （重跑同一天得到同样结果，这是勾稽能被信任的前提）。
 *
 * <p><b>没有通道/承运商文件会怎样</b>：资金层与三方层记为「待接入」而不是"有差异"——
 * 这是刻意的（见 `DailyReconcileRule`）：每天都报假差异会让差异清单失去信号价值。
 * 任务的摘要行会把"待接入层数"一并写出来，运维一眼能看出是"没数据"还是"真差异"。
 */
@Component
@RequiredArgsConstructor
public class DailyReconcileJob implements JobHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DailyReconcileJob.class);

    /** 日终勾稽服务。 */
    private final FnsTaskPort fnsTaskPort;

    // 对账状态口径：拆服务后来自 fns 的 HTTP 契约（不能再引用对方常量）
    private static final String STATUS_DIFF = "有差异";
    private static final String STATUS_WAITING = "待接入";
    private static final String STATUS_MATCH = "一致";

    @Override
    public JobName supportJob() {
        return JobName.DAILY_RECONCILE;
    }

    @Override
    public String execute() {
        LocalDate bizDate = LocalDate.now().minusDays(1);
        // 1. 执行四层勾稽（通道文件一期由运营导入，定时任务不传 → 资金/三方层为「待接入」）
        List<Map<String, Object>> results = fnsTaskPort.runDailyReconciliation(bizDate);
        // 2. 统计各状态层数（差异必须显眼：这是本任务存在的唯一理由）
        long diff = results.stream().filter(vo -> STATUS_DIFF.equals(vo.get("status"))).count();
        long waiting = results.stream()
                .filter(vo -> STATUS_WAITING.equals(vo.get("status"))).count();
        long match = results.stream().filter(vo -> STATUS_MATCH.equals(vo.get("status"))).count();
        if (diff > 0) {
            // 制度要求（04 §六）：差额 → T+1 告警，T+3 内处理完毕
            log.error("[日终勾稽告警] bizDate={} 有 {} 层存在差异，需 T+3 内处理：{}", bizDate, diff, results);
        }
        return String.format("bizDate=%s 一致 %d 层 / 有差异 %d 层 / 待接入 %d 层（待接入=缺通道或承运商侧数据，不计入差异告警）",
                bizDate, match, diff, waiting);
    }
}
