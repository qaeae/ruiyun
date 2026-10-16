package com.wms.platform.modules.outbound.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 称重偏差规则测试（03 §1.2 的 {@code 20003}）。
 *
 * <p>边界是这里的重点：**恰好 20% 放行、超过 20% 拦停**，以及"应收重量缺失时跳过校验"。
 * 这三条口径如果不测，现场会以"拦停太严导致停线"或"该拦的没拦导致运费亏损"的形式暴露。
 */
@DisplayName("称重偏差校验（WeightCheck）")
class WeightCheckTest {

    /** 应收重量 10kg。 */
    private static final BigDecimal EXPECTED = new BigDecimal("10.0000");

    @Test
    @DisplayName("偏差 10% → 通过")
    void withinThresholdShouldPass() {
        WeightCheck.Result result = WeightCheck.check(EXPECTED, new BigDecimal("11.0000"));

        assertThat(result.pass()).isTrue();
        assertThat(result.deviationRatio()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("恰好 20% → 通过（03 措辞为「偏差 > 20%」，边界放行避免抖动误拦）")
    void exactlyThresholdShouldPass() {
        WeightCheck.Result result = WeightCheck.check(EXPECTED, new BigDecimal("12.0000"));

        assertThat(result.pass()).as("恰好等于阈值不拦停").isTrue();
        assertThat(result.deviationRatio()).isEqualByComparingTo("0.2000");
    }

    @Test
    @DisplayName("超过 20% → 拦停，并给出可读原因")
    void aboveThresholdShouldBlock() {
        WeightCheck.Result result = WeightCheck.check(EXPECTED, new BigDecimal("12.1000"));

        assertThat(result.pass()).isFalse();
        assertThat(result.message()).contains("超过阈值");
    }

    @Test
    @DisplayName("欠重同样拦停（|偏差| 取绝对值，缺斤少两也要拦）")
    void underweightShouldBlock() {
        WeightCheck.Result result = WeightCheck.check(EXPECTED, new BigDecimal("7.0000"));

        assertThat(result.pass()).isFalse();
        assertThat(result.deviationRatio()).isEqualByComparingTo("0.3000");
    }

    @Test
    @DisplayName("应收重量缺失或非正 → 跳过校验（主数据没重量就停线比放行更糟）")
    void missingExpectedShouldSkip() {
        assertThat(WeightCheck.check(null, new BigDecimal("9.0000")).pass()).isTrue();
        assertThat(WeightCheck.check(BigDecimal.ZERO, new BigDecimal("9.0000")).pass()).isTrue();
        assertThat(WeightCheck.check(null, new BigDecimal("9.0000")).deviationRatio()).isNull();
    }

    @Test
    @DisplayName("实称读数缺失或非正 → 拦停（秤没读到东西却走了称重动作，属异常）")
    void missingActualShouldBlock() {
        assertThat(WeightCheck.check(EXPECTED, null).pass()).isFalse();
        assertThat(WeightCheck.check(EXPECTED, BigDecimal.ZERO).pass()).isFalse();
    }

    @Test
    @DisplayName("自定义阈值可用（不同品类可配不同口径）")
    void customThresholdShouldWork() {
        assertThat(WeightCheck.check(EXPECTED, new BigDecimal("10.5000"), new BigDecimal("0.10")).pass())
                .as("5% 偏差在 10% 阈值内").isTrue();
        assertThat(WeightCheck.check(EXPECTED, new BigDecimal("10.5000"), new BigDecimal("0.01")).pass())
                .as("5% 偏差超过 1% 阈值").isFalse();
    }
}
