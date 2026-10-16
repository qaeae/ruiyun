package com.wms.platform.modules.ops.app.impl;

import com.wms.platform.modules.ops.app.remote.FnsTaskPort;
import com.wms.platform.modules.ops.app.JobHandler;
import com.wms.platform.modules.ops.domain.JobName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 兜底扣减扫描任务（`01` §1.2 第 8 行：「揽收后 7 天无回传 → 定时任务兜底扣减」）。
 *
 * <p><b>幂等性来源</b>：任务本身只是"扫描 + 落账"，落账靠 `fn_fee_order.uk_fee_biz` 判重，
 * 因此租约过期重入、手工补跑、重复调度都不会重复扣款。这正是"租约 + 幂等"两层缺一不可的原因：
 * 租约只保证同一时刻一个实例在跑，不保证"只跑一次"。
 */
@Component
@RequiredArgsConstructor
public class FallbackSweepJob implements JobHandler {

    /** 兜底扣减服务。 */
    private final FnsTaskPort fnsTaskPort;

    @Override
    public JobName supportJob() {
        return JobName.FALLBACK_SWEEP;
    }

    @Override
    public String execute() {
        // 1. 扫描到期未回传的运单（服务内部已限制单次 500 条，避免一次捞爆内存/事务）
        List<Map<String, Object>> candidates = fnsTaskPort.sweepFallback();
        // 2. 摘要写进执行历史：运维看这一行就能判断"今天该扣几笔、其中几笔是新扣的"
        long newly = candidates.stream().filter(c -> !Boolean.TRUE.equals(c.get("alreadyDeducted"))).count();
        long already = candidates.size() - newly;
        return String.format("候选 %d 笔（本次新扣 %d 笔，已扣过 %d 笔，重复扫描未重复扣款）",
                candidates.size(), newly, already);
    }
}
