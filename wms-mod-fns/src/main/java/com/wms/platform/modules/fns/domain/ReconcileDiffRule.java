package com.wms.platform.modules.fns.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 对账差异判定规则（{@code 项目一方案.md} §4.4）。
 *
 * <p>三条阈值口径（原文）：**单笔差异 ≤ 1 元或 ≤ 1% → 自动平账**；差异 **&gt; 1% → 挂账**；
 * 差异 **&gt; 20% → 强制人工**。
 *
 * <p>放 {@code domain} 且做成纯函数的原因：这是**钱的口径**，必须能被单测逐字钉住，
 * 且要被多个入口复用（对账批处理、承运商账单导入、运营手工复核）。
 * 一旦把阈值写进 Service 或 SQL，第二个入口就会抄一份，然后两份慢慢不一致。
 *
 * <p>已明确的边界与口径（都有单测）：
 * <ul>
 *   <li>比例分母用**平台记录费用**（{@code platform_fee}）；分母为 0 或负时直接转**强制人工**——
 *       "平台记 0 元而承运商收费"本身就是异常，按比例算是无穷大，自动平账会直接漏损；</li>
 *   <li>恰好 1% → 自动平账（原文是 ≤1%）；恰好 20% → 挂账（原文是 &gt;20% 才强制人工）；</li>
 *   <li>「≤1 元」与「≤1%」是**或**关系：小额单笔（如 0.5 元）即便占比很高也自动平账，
 *       否则 1 元的小差异会把人工资源耗光。</li>
 * </ul>
 */
public final class ReconcileDiffRule {

    /** 自动平账的绝对金额阈值（元）：≤ 该值直接平账。 */
    public static final BigDecimal AUTO_AMOUNT_THRESHOLD = new BigDecimal("1.0000");

    /** 自动平账的比例阈值：≤ 该比例直接平账。 */
    public static final BigDecimal AUTO_RATE_THRESHOLD = new BigDecimal("0.01");

    /** 强制人工的比例阈值：> 该比例必须人工审核，不可自动通过。 */
    public static final BigDecimal MANUAL_RATE_THRESHOLD = new BigDecimal("0.20");

    /** 偏差率计算精度。 */
    private static final int SCALE = 6;

    private ReconcileDiffRule() {
    }

    /**
     * 判定单笔差异。
     *
     * @param platformFee 平台记录费用（元）
     * @param carrierFee  承运商账单费用（元）
     * @return 判定结果
     */
    public static Result check(BigDecimal platformFee, BigDecimal carrierFee) {
        // 1. 入参缺失：无法判定，转人工（不要用 0 参与运算掩盖问题）
        if (platformFee == null || carrierFee == null) {
            return new Result(platformFee, carrierFee, null, null, HandleResult.MANUAL,
                    "费用缺失（平台=" + platformFee + "，承运商=" + carrierFee + "），转人工核对");
        }

        // 2. 计算差异额与差异率（分母为平台记录费用）
        BigDecimal diff = carrierFee.subtract(platformFee);
        BigDecimal absDiff = diff.abs();
        if (platformFee.compareTo(BigDecimal.ZERO) <= 0) {
            // 2.1 分母非正：无法计算比例，直接强制人工
            return new Result(platformFee, carrierFee, absDiff, null, HandleResult.MANUAL,
                    "平台记录费用非正（" + platformFee + "），无法计算差异率，转人工核对");
        }
        BigDecimal rate = absDiff.divide(platformFee, SCALE, RoundingMode.HALF_UP);

        // 3. 无差异：自动平账
        if (absDiff.compareTo(BigDecimal.ZERO) == 0) {
            return new Result(platformFee, carrierFee, absDiff, rate, HandleResult.AUTO, "无差异");
        }

        // 4. 阈值内（≤1 元 或 ≤1%）：自动平账
        if (absDiff.compareTo(AUTO_AMOUNT_THRESHOLD) <= 0 || rate.compareTo(AUTO_RATE_THRESHOLD) <= 0) {
            return new Result(platformFee, carrierFee, absDiff, rate, HandleResult.AUTO,
                    "差异在阈值内（≤" + AUTO_AMOUNT_THRESHOLD.stripTrailingZeros().toPlainString()
                            + " 元或 ≤" + toPercent(AUTO_RATE_THRESHOLD) + "），自动平账");
        }

        // 5. 超过 20%：强制人工（不可自动通过）
        if (rate.compareTo(MANUAL_RATE_THRESHOLD) > 0) {
            return new Result(platformFee, carrierFee, absDiff, rate, HandleResult.MANUAL,
                    "差异率 " + toPercent(rate) + " 超过 " + toPercent(MANUAL_RATE_THRESHOLD)
                            + "，强制人工审核");
        }

        // 6. 其余（>1% 且 ≤20%）：挂账待处理
        return new Result(platformFee, carrierFee, absDiff, rate, HandleResult.PENDING,
                "差异率 " + toPercent(rate) + " 超过自动平账阈值，挂账待处理");
    }

    /**
     * 人工裁定：按责任归属给出结论（{@code handle_result = 责任方承担}）。
     *
     * @param base       原始判定结果
     * @param liability  责任方
     * @param remark     裁定说明
     * @return 裁定后的结果（结论为 {@link HandleResult#LIABILITY}）
     */
    public static Result judge(Result base, Liability liability, String remark) {
        return new Result(base.platformFee(), base.carrierFee(), base.diffAmount(), base.diffRate(),
                HandleResult.LIABILITY, base.message() + "；人工裁定责任方=" + liability.getLabel()
                + (remark == null ? "" : "（" + remark + "）"), liability);
    }

    /**
     * 比例转百分比文案（便于日志与运营界面阅读）。
     *
     * @param rate 比例
     * @return 形如 {@code 20%} 的文案
     */
    private static String toPercent(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 判定结果。
     *
     * @param platformFee  平台记录费用
     * @param carrierFee   承运商账单费用
     * @param diffAmount   差异额（绝对值）
     * @param diffRate     差异率（分母非正时为 {@code null}）
     * @param handleResult 处理结论
     * @param message      结论说明（中文）
     * @param liability    责任方（未裁定时为 {@code null}）
     */
    public record Result(BigDecimal platformFee, BigDecimal carrierFee, BigDecimal diffAmount,
                         BigDecimal diffRate, HandleResult handleResult, String message,
                         Liability liability) {

        /**
         * 简化构造（未裁定责任方）。
         *
         * @param platformFee  平台记录费用
         * @param carrierFee   承运商账单费用
         * @param diffAmount   差异额
         * @param diffRate     差异率
         * @param handleResult 处理结论
         * @param message      说明
         */
        public Result(BigDecimal platformFee, BigDecimal carrierFee, BigDecimal diffAmount,
                      BigDecimal diffRate, HandleResult handleResult, String message) {
            this(platformFee, carrierFee, diffAmount, diffRate, handleResult, message, null);
        }

        /**
         * 是否强制人工（不可自动通过）。
         *
         * @return 强制人工返回 {@code true}
         */
        public boolean requireManual() {
            return handleResult == HandleResult.MANUAL;
        }
    }
}
