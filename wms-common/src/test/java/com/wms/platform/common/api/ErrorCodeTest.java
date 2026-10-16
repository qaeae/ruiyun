package com.wms.platform.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码自检：码值唯一、码段与设计文档一致、未知码不透传。
 *
 * <p>为什么值得写这条测试：错误码是**前后端与外部渠道的共同契约**，
 * 一旦重复或改值，前端文案分流会静默错乱，且没有任何编译期保护。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("码值全局唯一（含 0）")
    void codesShouldBeUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(seen.add(code.getCode()))
                    .as("错误码重复: %s (%d)", code.name(), code.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("成功码为 0，且每个码都有非空文案")
    void okCodeAndMessages() {
        assertThat(ErrorCode.OK.getCode()).isZero();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getMessage()).as("%s 文案缺失", code.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("码段符合 03-接口定义.md §1.2：1xxxx 通用 … 8xxxx 权限")
    void codeSegmentsMatchDesign() {
        // (错误码, 期望码段) —— 与设计文档逐条对应，改码段必须同步改文档
        assertThat(ErrorCode.PARAM_ERROR.getCode() / 10000).isEqualTo(1);
        assertThat(ErrorCode.ILLEGAL_STATUS_TRANSITION.getCode() / 10000).isEqualTo(2);
        assertThat(ErrorCode.WAYBILL_FETCH_FAILED.getCode() / 10000).isEqualTo(3);
        assertThat(ErrorCode.BALANCE_INSUFFICIENT.getCode() / 10000).isEqualTo(4);
        assertThat(ErrorCode.AVAILABLE_STOCK_INSUFFICIENT.getCode() / 10000).isEqualTo(5);
        assertThat(ErrorCode.FORBIDDEN_CATEGORY.getCode() / 10000).isEqualTo(6);
        assertThat(ErrorCode.DIFF_EXCEED_THRESHOLD_NEED_MANUAL.getCode() / 10000).isEqualTo(7);
        assertThat(ErrorCode.APPROVER_MUST_DIFFER.getCode() / 10000).isEqualTo(8);
    }

    @Test
    @DisplayName("未知码反查返回 SYSTEM_ERROR，不把未知码透传给调用方")
    void unknownCodeFallsBackToSystemError() {
        assertThat(ErrorCode.of(99999)).isEqualTo(ErrorCode.SYSTEM_ERROR);
        assertThat(ErrorCode.of(ErrorCode.FORBIDDEN_CATEGORY.getCode())).isEqualTo(ErrorCode.FORBIDDEN_CATEGORY);
    }

    @Test
    @DisplayName("码值按设计为 5 位数字")
    void codesHaveFiveDigits() {
        Arrays.stream(ErrorCode.values())
                .filter(c -> c != ErrorCode.OK)
                .forEach(c -> assertThat(String.valueOf(c.getCode()))
                        .as("%s 应为 5 位码", c.name())
                        .hasSize(5));
    }
}
