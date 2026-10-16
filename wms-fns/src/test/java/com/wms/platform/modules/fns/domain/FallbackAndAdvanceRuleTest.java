package com.wms.platform.modules.fns.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兜底扣减与垫付限制规则测试（{@code 项目一方案.md} §5.8、§4.8）。
 *
 * <p>兜底与垫付是**两条会把钱先付出去**的路径：口径错一天、阈值错一分，
 * 要么让平台白白垫钱，要么让商家被错误限制出库（停服误伤比多垫一天更严重）。
 */
@DisplayName("兜底扣减与垫付限制规则")
class FallbackAndAdvanceRuleTest {

    @Test
    @DisplayName("揽收后 6 天 23:59 → 未到时点，不触发兜底")
    void beforeDeadlineShouldNotFallback() {
        LocalDateTime collectedAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        FallbackDeductionRule.Result result = FallbackDeductionRule.evaluate(
                collectedAt, collectedAt.plusDays(7).minusMinutes(1), false);

        assertThat(result.shouldFallback()).isFalse();
        assertThat(result.deadline()).isEqualTo(collectedAt.plusDays(7));
    }

    @Test
    @DisplayName("恰好满 7 天 → 触发兜底（达到即触发，不是超过才触发）")
    void exactlySevenDaysShouldFallback() {
        LocalDateTime collectedAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        FallbackDeductionRule.Result result = FallbackDeductionRule.evaluate(
                collectedAt, collectedAt.plusDays(7), false);

        assertThat(result.shouldFallback()).isTrue();
        assertThat(result.message()).contains("7 天");
    }

    @Test
    @DisplayName("已收到费用回传 → 永不兜底（回传优先，即便已过 7 天）")
    void receivedFeeShouldNeverFallback() {
        LocalDateTime collectedAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        assertThat(FallbackDeductionRule.evaluate(collectedAt, collectedAt.plusDays(30), true).shouldFallback())
                .isFalse();
    }

    @Test
    @DisplayName("缺少揽收时间 → 不触发（不用假时间扣钱）")
    void missingCollectedAtShouldNotFallback() {
        assertThat(FallbackDeductionRule.evaluate(null, LocalDateTime.now(), false).shouldFallback()).isFalse();
    }

    @Test
    @DisplayName("兜底后收到真回传：多退少补三种情形")
    void settleAfterFallback() {
        BigDecimal deducted = new BigDecimal("10.0000");

        FallbackDeductionRule.Adjustment refund = FallbackDeductionRule.settle(deducted, new BigDecimal("8.0000"));
        assertThat(refund.action()).isEqualTo(FallbackDeductionRule.Action.REFUND);
        assertThat(refund.adjustAmount()).isEqualByComparingTo("-2.0000");

        FallbackDeductionRule.Adjustment supplement = FallbackDeductionRule.settle(deducted, new BigDecimal("12.0000"));
        assertThat(supplement.action()).isEqualTo(FallbackDeductionRule.Action.SUPPLEMENT);
        assertThat(supplement.adjustAmount()).isEqualByComparingTo("2.0000");

        FallbackDeductionRule.Adjustment same = FallbackDeductionRule.settle(deducted, deducted);
        assertThat(same.action()).isEqualTo(FallbackDeductionRule.Action.NONE);
        assertThat(same.adjustAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("垫付额 = 实费 - 冻结额 - 商家余额（余额能覆盖的部分不算平台垫付）")
    void advanceNeededShouldSubtractBalance() {
        // 实费 15、冻结 10、余额 5 → 余额刚好覆盖，平台无需垫付
        assertThat(AdvancePaymentRule.advanceNeeded(new BigDecimal("15"), new BigDecimal("10"), new BigDecimal("5")))
                .isEqualByComparingTo("0");

        // 余额差 1 元 → 平台垫付 1 元
        assertThat(AdvancePaymentRule.advanceNeeded(new BigDecimal("15"), new BigDecimal("10"), new BigDecimal("4")))
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("冻结额已覆盖实费 → 垫付 0（正常路径不产生垫付台账）")
    void fullyFrozenShouldNotAdvance() {
        assertThat(AdvancePaymentRule.advanceNeeded(new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
        assertThat(AdvancePaymentRule.advanceNeeded(new BigDecimal("8"), new BigDecimal("12"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("限制阈值：恰好 2000 不限制，超过 2000 限制；连续 3 单限制")
    void limitThresholdBoundaries() {
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("2000.0000"), 0).limited())
                .as("原文为「> ¥2000」，恰好 2000 不限制").isFalse();
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("2000.0001"), 0).limited()).isTrue();
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("100.0000"), 3).limited())
                .as("连续 3 单即限制（与金额无关）").isTrue();
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("100.0000"), 2).limited()).isFalse();
    }
}
