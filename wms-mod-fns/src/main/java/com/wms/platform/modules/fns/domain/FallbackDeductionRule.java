package com.wms.platform.modules.fns.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用兜底扣减规则（{@code 项目一方案.md} §5.8、§9）。
 *
 * <p>原文：**揽收后 7 天未收到承运商费用回传的，按预估价先行扣减并挂"待核销"，收到回传后多退少补**。
 *
 * <p>为什么必须有兜底：承运商回传迟到是行业常态（对手工录单的中小承运商尤其如此）。
 * 若一直等回传，商家的冻结额会长期占着不放、账单无法出、月结无法划拨——
 * 财务月初的账期会被单个承运商的接口拖住。兜底的代价是"先按预估价扣、可能多退少补"，
 * 这个代价是可接受且有账可查的。
 *
 * <p>两条口径（都有单测）：
 * <ul>
 *   <li>**达到** 7 天即触发（{@code now >= 揽收时间 + 7 天}），不是"超过 7 天后再等一天"；</li>
 *   <li>已收到回传则永不兜底（哪怕已经过了 7 天）——回传优先于兜底。</li>
 * </ul>
 */
public final class FallbackDeductionRule {

    /** 兜底等待天数（揽收后 N 天未回传）。 */
    public static final int FALLBACK_DAYS = 7;

    private FallbackDeductionRule() {
    }

    /**
     * 判定是否触发兜底扣减。
     *
     * @param collectedAt     揽收时间（承运商回传或轨迹得到的实际揽收时间）
     * @param now             当前时间
     * @param feeBackReceived 是否已收到费用回传
     * @return 判定结果（含兜底时点）
     */
    public static Result evaluate(LocalDateTime collectedAt, LocalDateTime now, boolean feeBackReceived) {
        // 1. 入参缺失：不触发（宁可等人工处理，也不要用假时间扣钱）
        if (collectedAt == null || now == null) {
            return new Result(false, null, "缺少揽收时间或当前时间，不触发兜底");
        }
        // 2. 兜底时点 = 揽收时间 + 7 天
        LocalDateTime deadline = collectedAt.plusDays(FALLBACK_DAYS);
        // 3. 已收到回传：永不兜底（回传优先）
        if (feeBackReceived) {
            return new Result(false, deadline, "已收到费用回传，不触发兜底");
        }
        // 4. 达到兜底时点即触发
        if (!now.isBefore(deadline)) {
            return new Result(true, deadline,
                    "揽收后已满 " + FALLBACK_DAYS + " 天未收到费用回传，按预估价先行扣减");
        }
        // 5. 未到时点：继续等待
        return new Result(false, deadline, "未到兜底时点（" + deadline + "）");
    }

    /**
     * 兜底后收到真实回传：计算多退少补金额。
     *
     * @param fallbackDeducted 兜底已扣金额（= 预估价）
     * @param actualFee        回传实费
     * @return 调整结果（调整额带符号：正数=需补扣，负数=应退还）
     */
    public static Adjustment settle(BigDecimal fallbackDeducted, BigDecimal actualFee) {
        // 1. 入参缺失：转人工（钱的计算不接受空值）
        if (fallbackDeducted == null || actualFee == null) {
            return new Adjustment(null, Action.MANUAL, "费用缺失，转人工核对");
        }
        // 2. 调整额 = 实费 - 已扣
        BigDecimal adjust = actualFee.subtract(fallbackDeducted);
        int cmp = adjust.compareTo(BigDecimal.ZERO);
        // 3. 实费更低 → 多扣了，需退还
        if (cmp < 0) {
            return new Adjustment(adjust, Action.REFUND, "实费低于兜底预估价，应退还差额 " + adjust.abs());
        }
        // 4. 实费更高 → 少扣了，需补扣
        if (cmp > 0) {
            return new Adjustment(adjust, Action.SUPPLEMENT, "实费高于兜底预估价，应补扣差额 " + adjust);
        }
        // 5. 一致 → 仅清"待核销"
        return new Adjustment(BigDecimal.ZERO, Action.NONE, "实费与兜底预估价一致，清除待核销标记");
    }

    /**
     * 兜底判定结果。
     *
     * @param shouldFallback 是否触发兜底
     * @param deadline       兜底时点（揽收时间 + 7 天）
     * @param message        说明
     */
    public record Result(boolean shouldFallback, LocalDateTime deadline, String message) {
    }

    /** 调整动作。 */
    public enum Action {

        /** 应退还差额（多退）。 */
        REFUND("应退还"),

        /** 应补扣差额（少补）。 */
        SUPPLEMENT("应补扣"),

        /** 无需调整。 */
        NONE("无需调整"),

        /** 转人工。 */
        MANUAL("转人工");

        /** 中文名。 */
        private final String label;

        Action(String label) {
            this.label = label;
        }

        /**
         * 中文名。
         *
         * @return 中文名
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * 多退少补调整结果。
     *
     * @param adjustAmount 调整额（带符号：正数补扣、负数退还；转人工时为 {@code null}）
     * @param action       调整动作
     * @param message      说明
     */
    public record Adjustment(BigDecimal adjustAmount, Action action, String message) {
    }
}
