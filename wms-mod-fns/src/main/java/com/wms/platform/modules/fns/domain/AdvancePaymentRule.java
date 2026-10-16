package com.wms.platform.modules.fns.domain;

import java.math.BigDecimal;

/**
 * 垫付与限制阶段规则（{@code 项目一方案.md} §4.8）。
 *
 * <p>原文：**实际运费 &gt; 冻结额、且商家余额不足时，差额记为平台垫付**，进台账；
 * 垫付超过阈值（**单商家累计 &gt; ¥2000 或连续 3 单**）→ 触发限制阶段；
 * **不接受无台账垫付**（禁止口头挂账）。
 *
 * <p>计算垫付额时的关键取舍（有单测）：
 * <ul>
 *   <li>垫付额 = 实费 - 冻结额 - **商家可用余额**。余额能覆盖的部分不算垫付——
 *       否则会把"商家自己付的钱"也记成平台垫付，追偿时多收商家的钱；</li>
 *   <li>冻结额已覆盖实费 → 垫付 0（正常路径，不该产生任何垫付台账记录）；</li>
 *   <li>阈值用**严格大于**（原文">&gt; ¥2000"）：恰好 2000 不限制；连续 3 单**含**第 3 单即限制。</li>
 * </ul>
 */
public final class AdvancePaymentRule {

    /** 单商家累计垫付限制阈值（元）。 */
    public static final BigDecimal LIMIT_AMOUNT = new BigDecimal("2000.0000");

    /** 单商家连续垫付单数限制阈值。 */
    public static final int LIMIT_CONSECUTIVE_COUNT = 3;

    private AdvancePaymentRule() {
    }

    /**
     * 计算需要平台垫付的金额。
     *
     * @param actualFee      承运商回传实费（元）
     * @param frozenAmount   该运单已冻结金额（元）
     * @param merchantBalance 商家可用余额（元）
     * @return 垫付金额（0 表示无需垫付）
     */
    public static BigDecimal advanceNeeded(BigDecimal actualFee, BigDecimal frozenAmount, BigDecimal merchantBalance) {
        // 1. 入参缺失：视为无需垫付（由调用方保证必填；这里不做静默"补 0"以外的推断）
        if (actualFee == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal frozen = frozenAmount == null ? BigDecimal.ZERO : frozenAmount;
        BigDecimal balance = merchantBalance == null ? BigDecimal.ZERO : merchantBalance;

        // 2. 实费未超冻结额：正常扣减，不产生垫付
        BigDecimal remain = actualFee.subtract(frozen);
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        // 3. 超出部分先用商家余额抵扣
        BigDecimal afterBalance = remain.subtract(balance);
        // 4. 余额仍不够的差额才是平台垫付
        return afterBalance.compareTo(BigDecimal.ZERO) > 0 ? afterBalance : BigDecimal.ZERO;
    }

    /**
     * 判定是否触发限制阶段。
     *
     * @param cumulativeAdvance      该商家累计垫付金额（元）
     * @param consecutiveAdvanceCount 连续垫付单数
     * @return 判定结果
     */
    public static Result evaluateLimit(BigDecimal cumulativeAdvance, int consecutiveAdvanceCount) {
        // 1. 累计超阈值 → 限制
        if (cumulativeAdvance != null && cumulativeAdvance.compareTo(LIMIT_AMOUNT) > 0) {
            return new Result(true, "累计垫付 " + cumulativeAdvance
                    + " 超过阈值 " + LIMIT_AMOUNT.stripTrailingZeros().toPlainString() + "，触发限制阶段（限制出库/入库）");
        }
        // 2. 连续垫付达上限 → 限制
        if (consecutiveAdvanceCount >= LIMIT_CONSECUTIVE_COUNT) {
            return new Result(true, "连续 " + consecutiveAdvanceCount + " 单需平台垫付，触发限制阶段（限制出库/入库）");
        }
        // 3. 未触发
        return new Result(false, "垫付在阈值内，不触发限制");
    }

    /**
     * 限制判定结果。
     *
     * @param limited 是否触发限制阶段
     * @param message 说明
     */
    public record Result(boolean limited, String message) {
    }
}
