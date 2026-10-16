package com.wms.platform.modules.outbound.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 称重偏差校验规则（03 §1.2 的 {@code 20003}：称重偏差超阈值拦停；02 M7-03）。
 *
 * <p>放在 {@code domain} 而不是设备端口里的原因：**"偏差多少算超标"是业务规则，不是设备能力**。
 * 设备端口只负责"读到多少 kg、稳不稳定"；规则一旦写进端口，换秤/换实现就会把阈值逻辑复制一份。
 *
 * <p>两条边界口径（容易踩，已明确约定）：
 * <ul>
 *   <li>偏差**严格大于**阈值才拦停——03 的措辞是"偏差 &gt; 20%"，恰好 20% 放行（避免边界抖动误拦）；</li>
 *   <li>应收重量缺失或 ≤0 时**跳过校验**（主数据没重量就停线，比放行更糟）；
 *       但"实称读到 0"属异常，直接拦停。</li>
 * </ul>
 */
public final class WeightCheck {

    /** 默认偏差阈值（20%，03 §1.2）。 */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.20");

    /** 偏差率计算精度（与 {@code DECIMAL(12,4)} 口径一致）。 */
    private static final int SCALE = 4;

    private WeightCheck() {
    }

    /**
     * 用默认阈值（20%）校验。
     *
     * @param expectedKg 应收重量（kg，来自 SKU 主数据或订单快照）
     * @param actualKg   实称重量（kg）
     * @return 校验结果
     */
    public static Result check(BigDecimal expectedKg, BigDecimal actualKg) {
        return check(expectedKg, actualKg, DEFAULT_THRESHOLD);
    }

    /**
     * 校验称重偏差。
     *
     * @param expectedKg 应收重量（kg）
     * @param actualKg   实称重量（kg）
     * @param threshold  偏差阈值（如 {@code 0.20}）
     * @return 校验结果（含偏差率与结论）
     */
    public static Result check(BigDecimal expectedKg, BigDecimal actualKg, BigDecimal threshold) {
        // 1. 应收重量缺失或非正：跳过校验（不拦停），但要留下说明，便于事后追主数据缺失
        if (expectedKg == null || expectedKg.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.skipped("应收重量缺失或非正，跳过偏差校验");
        }
        // 2. 实称读数异常（缺失或 ≤0）：直接拦停——秤没读到东西却走了称重动作，属设备/流程异常
        if (actualKg == null || actualKg.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.blocked(null, threshold, "实称重量缺失或非正，请重新称重");
        }
        // 3. 计算偏差率 = |实称 - 应收| / 应收（保留 4 位，四舍五入）
        BigDecimal deviation = actualKg.subtract(expectedKg).abs()
                .divide(expectedKg, SCALE, RoundingMode.HALF_UP);
        // 4. 严格大于阈值才拦停（恰好等于阈值放行，见类注释）
        if (deviation.compareTo(threshold) > 0) {
            return Result.blocked(deviation, threshold,
                    "称重偏差 " + deviation.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                            + "% 超过阈值 "
                            + threshold.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%");
        }
        // 5. 通过
        return Result.passed(deviation, threshold);
    }

    /**
     * 校验结果。
     *
     * @param pass           是否通过（跳过校验也算通过）
     * @param deviationRatio 偏差率（跳过校验时为 {@code null}）
     * @param threshold      使用的阈值
     * @param message        结论说明（中文）
     */
    public record Result(boolean pass, BigDecimal deviationRatio, BigDecimal threshold, String message) {

        /**
         * 通过。
         *
         * @param deviationRatio 偏差率
         * @param threshold      阈值
         * @return 通过结果
         */
        public static Result passed(BigDecimal deviationRatio, BigDecimal threshold) {
            return new Result(true, deviationRatio, threshold, "称重校验通过");
        }

        /**
         * 跳过校验（应收重量缺失）。
         *
         * @param message 说明
         * @return 通过但已跳过
         */
        public static Result skipped(String message) {
            return new Result(true, null, null, message);
        }

        /**
         * 拦停。
         *
         * @param deviationRatio 偏差率（实称异常时为 {@code null}）
         * @param threshold      阈值
         * @param message        拦截原因
         * @return 拦停结果
         */
        public static Result blocked(BigDecimal deviationRatio, BigDecimal threshold, String message) {
            return new Result(false, deviationRatio, threshold, message);
        }
    }
}
