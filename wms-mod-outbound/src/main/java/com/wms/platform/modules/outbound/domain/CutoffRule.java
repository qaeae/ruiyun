package com.wms.platform.modules.outbound.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.time.LocalTime;

/**
 * 截单判定（{@code 02} M7-06：截单时间与当日单/次日单分批）。
 *
 * <p>口径：**在截单时刻（含）之前建的单是当日单**，之后是次日单。
 * {@code is_same_day} 只影响"当日批次"的作业安排与前置校验，**不影响能否下单**
 * （{@code 01} §2.1 把"截单前"写在建单前置条件里，但 {@code 02} M7-06 的定义是分批而非拒绝——
 * 拒绝会让商家 18:01 完全下不了单，而"次日单"才是现场真正需要的行为）。
 *
 * <p>边界取"含"是刻意的：截单时间 18:00 的现场理解是"18:00 整仍算当日"，写成严格小于会让
 * 每个整点瞬间的单据归错批次，且这种差异只在整点前后复现，极难排查。
 */
public final class CutoffRule {

    private CutoffRule() {
    }

    /**
     * 是否为当日单。
     *
     * @param now    建单时间
     * @param cutoff 截单时刻（东八区本地时刻）
     * @return 当日单返回 {@code true}
     */
    public static boolean isSameDay(java.time.LocalDateTime now, LocalTime cutoff) {
        if (now == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "建单时间不能为空");
        }
        LocalTime effective = cutoff == null ? LocalTime.MAX : cutoff;
        return !now.toLocalTime().isAfter(effective);
    }
}
