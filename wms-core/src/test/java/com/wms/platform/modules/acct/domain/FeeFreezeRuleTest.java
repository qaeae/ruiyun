package com.wms.platform.modules.acct.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费用冻结规则单元测试（{@code 04} §4.3）。
 *
 * <p>为什么这几条值得写成测试：冻结额算错、单号碰撞，都是"平时看不出来、出事就是钱的事"——
 * 算错 10% 缓冲会持续少冻（资金敞口），单号取模会偶发唯一键冲突（业务直接失败）。
 */
@DisplayName("费用冻结规则")
class FeeFreezeRuleTest {

    /** 今天（单号前缀用）。 */
    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    @Test
    @DisplayName("冻结额 = 预估价 × (1 + 缓冲系数)")
    void frozenAmountAppliesBuffer() {
        assertThat(FeeFreezeRule.frozenAmount(new BigDecimal("10.0000"), new BigDecimal("0.1000")))
                .isEqualByComparingTo("11.0000");
    }

    @Test
    @DisplayName("缓冲系数为空时按 0 处理（不放大冻结额）")
    void nullBufferMeansNoBuffer() {
        assertThat(FeeFreezeRule.frozenAmount(new BigDecimal("8.5000"), null))
                .isEqualByComparingTo("8.5000");
    }

    @Test
    @DisplayName("冻结额四舍五入到 4 位小数（与 DECIMAL(18,4) 对齐）")
    void frozenAmountRoundsToFourScale() {
        // 3.3333 × 1.1 = 3.66663 → 3.6666
        assertThat(FeeFreezeRule.frozenAmount(new BigDecimal("3.3333"), new BigDecimal("0.1000")))
                .isEqualByComparingTo("3.6666");
    }

    @Test
    @DisplayName("预估价非正：拒绝（不做 0 元冻结）")
    void nonPositiveEstimateRejected() {
        assertThatThrownBy(() -> FeeFreezeRule.frozenAmount(BigDecimal.ZERO, new BigDecimal("0.1000")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
        assertThatThrownBy(() -> FeeFreezeRule.frozenAmount(null, new BigDecimal("0.1000")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("缓冲系数为负：拒绝（配置错误，不能倒贴）")
    void negativeBufferRejected() {
        assertThatThrownBy(() -> FeeFreezeRule.frozenAmount(new BigDecimal("1.0000"), new BigDecimal("-0.1")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    @DisplayName("单号：带 FRZ/LG 前缀与日期，且不碰撞、长度在列宽内")
    void noRuleIsUniqueAndFitsColumn() {
        long id1 = 103993333059158016L;
        long id2 = id1 + 1;
        String no1 = FeeFreezeRule.freezeNo(id1);
        String no2 = FeeFreezeRule.freezeNo(id2);
        assertThat(no1).startsWith("FRZ" + TODAY).isNotEqualTo(no2);
        // ac_fee_freeze.freeze_no VARCHAR(32) / ac_ledger.ledger_no VARCHAR(32)
        assertThat(no1.length()).isLessThanOrEqualTo(32);
        assertThat(FeeFreezeRule.ledgerNo(id1)).startsWith("LG" + TODAY);
        assertThat(FeeFreezeRule.ledgerNo(id1).length()).isLessThanOrEqualTo(32);
    }
}
