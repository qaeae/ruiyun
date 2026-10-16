package com.wms.platform.modules.md.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 箱规换算测试（{@code 02} M2-02）。
 *
 * <p>每条用例对应一类真实事故：coefficient 配 0（量纲塌成 0）、自换算（换算链成环）、
 * 无路径时静默返回原值（"2 托 = 2 件"这种错当天没人发现）、
 * 直配与推导冲突（两个口径，谁也不知道该信哪个）。
 */
@DisplayName("箱规单位换算（UnitConversion）")
class UnitConversionTest {

    @Test
    @DisplayName("单跳换算：1 箱 = 24 件，12 箱 = 288 件")
    void directConversion() {
        Map<String, BigDecimal> factors = Map.of(UnitConversion.key("箱", "件"), new BigDecimal("24"));

        assertThat(UnitConversion.convert(new BigDecimal("12"), "箱", "件", factors))
                .isEqualByComparingTo("288.000000");
    }

    @Test
    @DisplayName("两跳推导：托→箱 + 箱→件 ⇒ 托→件（只配了相邻两级也能算）")
    void twoHopConversion() {
        Map<String, BigDecimal> factors = Map.of(
                UnitConversion.key("托", "箱"), new BigDecimal("20"),
                UnitConversion.key("箱", "件"), new BigDecimal("24"));

        assertThat(UnitConversion.convert(BigDecimal.ONE, "托", "件", factors))
                .as("1 托 = 20 箱 × 24 件").isEqualByComparingTo("480.000000");
    }

    @Test
    @DisplayName("反向换算：只配了 件→箱，也能算 箱→件（取倒数）")
    void reverseConversion() {
        Map<String, BigDecimal> factors = Map.of(UnitConversion.key("件", "箱"), new BigDecimal("24"));

        assertThat(UnitConversion.convert(new BigDecimal("48"), "箱", "件", factors))
                .isEqualByComparingTo("2.000000");
    }

    @Test
    @DisplayName("同单位直接返回（按统一精度归整）")
    void sameUnit() {
        assertThat(UnitConversion.convert(new BigDecimal("3"), "箱", "箱", Map.of()))
                .isEqualByComparingTo("3.000000");
    }

    @Test
    @DisplayName("无换算路径 → 拒绝，不返回原值（静默返回原值等于把量纲错藏起来）")
    void missingPathShouldFail() {
        assertThatThrownBy(() -> UnitConversion.convert(BigDecimal.ONE, "托", "件", Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR))
                .hasMessageContaining("未配置换算关系");
    }

    @Test
    @DisplayName("配置校验：单位非法 / 自换算 / 系数非正 一律拒绝")
    void configValidation() {
        assertThatThrownBy(() -> UnitConversion.assertConfigValid("打", "件", BigDecimal.TEN))
                .isInstanceOf(BizException.class).hasMessageContaining("不支持的单位");
        assertThatThrownBy(() -> UnitConversion.assertConfigValid("箱", "箱", BigDecimal.TEN))
                .hasMessageContaining("不能相同");
        assertThatThrownBy(() -> UnitConversion.assertConfigValid("箱", "件", BigDecimal.ZERO))
                .hasMessageContaining("必须大于 0");
        assertThatThrownBy(() -> UnitConversion.assertConfigValid("箱", "件", null))
                .hasMessageContaining("必须大于 0");
    }

    @Test
    @DisplayName("系数为 0 会让整仓数量塌成 0 —— 单跳换算也必须挡住")
    void directConvertWithZeroFactorShouldFail() {
        assertThatThrownBy(() -> UnitConversion.convertDirect(BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(BizException.class).hasMessageContaining("换算系数非法");
    }

    @Test
    @DisplayName("换算精度：1/3 类无限小数按 6 位四舍五入，不产生漂移")
    void precisionIsStable() {
        BigDecimal result = UnitConversion.convertDirect(BigDecimal.ONE, new BigDecimal("0.333333"));

        assertThat(result).isEqualByComparingTo("0.333333");
        assertThat(result.scale()).as("统一 6 位精度").isEqualTo(6);
    }

    @Test
    @DisplayName("矛盾检测：直配与经中间单位推导不一致时能被发现（而不是悄悄采信其中一个）")
    void conflictDetection() {
        Map<String, BigDecimal> factors = new HashMap<>();
        factors.put(UnitConversion.key("托", "件"), new BigDecimal("100"));
        factors.put(UnitConversion.key("托", "箱"), new BigDecimal("5"));
        factors.put(UnitConversion.key("箱", "件"), new BigDecimal("24"));

        List<String> conflicts = UnitConversion.findConflicts(factors);

        assertThat(conflicts).as("100 ≠ 5×24=120，必须报矛盾").hasSize(1);
        assertThat(conflicts.get(0)).contains("托→件").contains("120");

        // 一致时无矛盾
        Map<String, BigDecimal> consistent = Map.of(
                UnitConversion.key("托", "件"), new BigDecimal("120"),
                UnitConversion.key("托", "箱"), new BigDecimal("5"),
                UnitConversion.key("箱", "件"), new BigDecimal("24"));
        assertThat(UnitConversion.findConflicts(consistent)).isEmpty();
    }
}
