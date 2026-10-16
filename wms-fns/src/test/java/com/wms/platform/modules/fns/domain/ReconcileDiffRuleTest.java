package com.wms.platform.modules.fns.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账差异阈值规则测试（{@code 项目一方案.md} §4.4）。
 *
 * <p>**这里的每个边界都对应一种钱的损失方式**：阈值放宽 → 承运商多收被自动放过；
 * 阈值收紧 → 1 元的小差异把人工资源耗光、月初账单出不来。所以边界必须逐条钉住。
 */
@DisplayName("对账差异阈值规则")
class ReconcileDiffRuleTest {

    /** 100 元平台记录费用。 */
    private static final BigDecimal BASE_100 = new BigDecimal("100.0000");

    @Test
    @DisplayName("无差异 → 自动平账")
    void noDiffShouldAutoSettle() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BASE_100, BASE_100);

        assertThat(result.handleResult()).isEqualTo(HandleResult.AUTO);
        assertThat(result.diffAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("差异 0.5 元（≤1 元）→ 自动平账")
    void smallAmountShouldAutoSettle() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(new BigDecimal("50.0000"), new BigDecimal("50.5000"));

        assertThat(result.handleResult()).isEqualTo(HandleResult.AUTO);
    }

    @Test
    @DisplayName("「≤1 元」与「≤1%」是或关系：10 元基数差 0.5 元（5%）仍自动平账")
    void smallAmountWinsRegardlessOfRate() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(new BigDecimal("10.0000"), new BigDecimal("10.5000"));

        assertThat(result.diffRate()).isEqualByComparingTo("0.050000");
        assertThat(result.handleResult()).as("金额阈值优先，避免小额差异耗尽人工").isEqualTo(HandleResult.AUTO);
    }

    @Test
    @DisplayName("恰好 1%（10 元 / 1000 元）→ 自动平账")
    void exactlyOnePercentShouldAutoSettle() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(new BigDecimal("1000.0000"), new BigDecimal("1010.0000"));

        assertThat(result.diffAmount()).isEqualByComparingTo("10");
        assertThat(result.diffRate()).isEqualByComparingTo("0.010000");
        assertThat(result.handleResult()).as("原文为「≤1%」，恰好 1% 应自动平账").isEqualTo(HandleResult.AUTO);
    }

    @Test
    @DisplayName("差异 1.2%（>1% 且 ≤20%）→ 挂账待处理")
    void aboveOnePercentShouldPend() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BASE_100, new BigDecimal("101.2000"));

        assertThat(result.handleResult()).isEqualTo(HandleResult.PENDING);
        assertThat(result.requireManual()).as("挂账不等于强制人工").isFalse();
    }

    @Test
    @DisplayName("恰好 20% → 挂账（原文为「>20% 才强制人工」）")
    void exactlyTwentyPercentShouldPend() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BASE_100, new BigDecimal("120.0000"));

        assertThat(result.diffRate()).isEqualByComparingTo("0.200000");
        assertThat(result.handleResult()).isEqualTo(HandleResult.PENDING);
    }

    @Test
    @DisplayName("差异 25% → 强制人工（不可自动通过）")
    void aboveTwentyPercentShouldRequireManual() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BASE_100, new BigDecimal("125.0000"));

        assertThat(result.handleResult()).isEqualTo(HandleResult.MANUAL);
        assertThat(result.requireManual()).isTrue();
    }

    @Test
    @DisplayName("欠重（实费低于平台记录）同样按绝对值判定")
    void underweightShouldUseAbsoluteValue() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BASE_100, new BigDecimal("70.0000"));

        assertThat(result.diffAmount()).isEqualByComparingTo("30");
        assertThat(result.handleResult()).isEqualTo(HandleResult.MANUAL);
    }

    @Test
    @DisplayName("平台记录费用为 0 → 强制人工（按比例算是无穷大，自动平账会直接漏损）")
    void zeroBaseShouldRequireManual() {
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(BigDecimal.ZERO, new BigDecimal("10.0000"));

        assertThat(result.handleResult()).isEqualTo(HandleResult.MANUAL);
        assertThat(result.diffRate()).isNull();
    }

    @Test
    @DisplayName("费用缺失 → 强制人工（不用 0 参与运算掩盖问题）")
    void nullFeeShouldRequireManual() {
        assertThat(ReconcileDiffRule.check(null, new BigDecimal("10.0000")).requireManual()).isTrue();
        assertThat(ReconcileDiffRule.check(BASE_100, null).requireManual()).isTrue();
    }

    @Test
    @DisplayName("人工裁定：按责任方承担并保留原判定信息")
    void judgeShouldSetLiability() {
        ReconcileDiffRule.Result base = ReconcileDiffRule.check(BASE_100, new BigDecimal("130.0000"));

        ReconcileDiffRule.Result judged = ReconcileDiffRule.judge(base, Liability.LOGISTICS, "承运商多计重量");

        assertThat(judged.handleResult()).isEqualTo(HandleResult.LIABILITY);
        assertThat(judged.liability()).isEqualTo(Liability.LOGISTICS);
        assertThat(judged.message()).contains("物流").contains("承运商多计重量");
        assertThat(judged.handleResult().isSettleable()).as("责任方承担后可进结算").isTrue();
    }

    @Test
    @DisplayName("可结算性：自动平账/责任方承担可结算，挂账/强制人工不可")
    void settleableSemantics() {
        assertThat(HandleResult.AUTO.isSettleable()).isTrue();
        assertThat(HandleResult.LIABILITY.isSettleable()).isTrue();
        assertThat(HandleResult.PENDING.isSettleable()).as("差异未定性前不得划拨").isFalse();
        assertThat(HandleResult.MANUAL.isSettleable()).isFalse();
    }
}
