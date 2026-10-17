package com.wms.platform.modules.outbound.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 截单判定与单号规则单元测试（{@code 02} M7-06、{@code 01} §1.1）。
 *
 * <p>截单的边界（含 18:00）与单号的唯一性都是"只在临界点出错、且出错很难复现"的那类问题：
 * 前者只影响整点瞬间的单据批次，后者表现为偶发唯一键冲突。因此都值得钉住。
 */
@DisplayName("截单判定与单号规则")
class CutoffAndNoRuleTest {

    @Test
    @DisplayName("截单前（含整点）是当日单，之后是次日单")
    void cutoffBoundaryInclusive() {
        LocalTime cutoff = LocalTime.of(18, 0);
        assertThat(CutoffRule.isSameDay(LocalDateTime.of(2026, 10, 16, 17, 59, 59), cutoff))
                .as("截单前").isTrue();
        assertThat(CutoffRule.isSameDay(LocalDateTime.of(2026, 10, 16, 18, 0, 0), cutoff))
                .as("恰好 18:00 整仍算当日（避免整点瞬间单据归错批次）").isTrue();
        assertThat(CutoffRule.isSameDay(LocalDateTime.of(2026, 10, 16, 18, 0, 1), cutoff))
                .as("18:00 之后是次日单").isFalse();
    }

    @Test
    @DisplayName("截单时间为空：全部按当日单处理，且不影响能否下单")
    void nullCutoffMeansAllSameDay() {
        assertThat(CutoffRule.isSameDay(LocalDateTime.of(2026, 10, 16, 23, 59), null)).isTrue();
        assertThatThrownBy(() -> CutoffRule.isSameDay(null, LocalTime.NOON))
                .isInstanceOf(com.wms.platform.common.api.BizException.class);
    }

    @Test
    @DisplayName("出库单号：DO + 日期 + 36 进制雪花，不碰撞且在列宽内")
    void doNoIsUniqueAndFitsColumn() {
        long id1 = 103993333059158016L;
        String no1 = OutOrderNoRule.doNo(id1);
        String no2 = OutOrderNoRule.doNo(id1 + 1);
        assertThat(no1).startsWith("DO").hasSizeLessThanOrEqualTo(32).isNotEqualTo(no2);
    }

    @Test
    @DisplayName("部分发货余量单号：原号加 #P 后缀，避开防重复下单唯一键")
    void partialSourceOrderNoAvoidsUniqueKey() {
        assertThat(OutOrderNoRule.partialSourceOrderNo("SO-1", "DO-1", 1)).isEqualTo("SO-1#P1");
        assertThat(OutOrderNoRule.partialSourceOrderNo("SO-1", "DO-1", 2)).isEqualTo("SO-1#P2");
        assertThat(OutOrderNoRule.partialSourceOrderNo(null, "DO-9", 1))
                .as("上游单号缺失时用原 DO 号兜底").isEqualTo("DO-9#P1");
    }

    @Test
    @DisplayName("状态取值反查：中文取值（落库）与非法的处理")
    void statusLookup() {
        assertThat(OutOrderStatus.of("待接单")).isEqualTo(OutOrderStatus.PENDING);
        assertThat(OutOrderStatus.of("已出库").isTerminal()).isTrue();
        assertThatThrownBy(() -> OutOrderStatus.of("待接单x"))
                .as("脏数据必须显式失败，不能静默当成某个状态")
                .isInstanceOf(com.wms.platform.common.api.BizException.class);
    }

    @Test
    @DisplayName("weight check 口径未变（回归：恰 20% 放行，超过拦停）")
    void weightCheckBoundaryRegression() {
        assertThat(WeightCheck.check(new BigDecimal("10.0000"), new BigDecimal("12.0000")).pass())
                .as("恰 20% 放行").isTrue();
        assertThat(WeightCheck.check(new BigDecimal("10.0000"), new BigDecimal("12.0020")).pass())
                .as("超过 20% 拦停").isFalse();
        // 口径说明：偏差率保留 4 位小数后再比较，因此 20.001% 会被四舍五入成 20.0000% 而放行。
        // 这是刻意保留的既有口径（4 位小数与 DECIMAL 精度一致），不是为了"多拦一点"而改的。
        assertThat(WeightCheck.check(new BigDecimal("10.0000"), new BigDecimal("12.0001")).pass())
                .as("偏差率四舍五入到 4 位后为 20.0000%，按「严格大于才拦停」放行").isTrue();
    }
}
