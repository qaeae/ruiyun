package com.wms.platform.modules.iv.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盘点规则测试（03 API-IV-03）。
 *
 * <p>每条对应一类真实事故：拿"审批时的账面"算差异（录入期间的出库被算成盘盈）、
 * 创建人自审（一个人把货凭空消掉）、提交后还能改数（审批内容与执行内容不一致）、
 * 无差异也写流水（污染流水台账）、重复审批二次调整（库存被调两次）。
 */
@DisplayName("盘点规则（StocktakeRule）")
class StocktakeRuleTest {

    @Test
    @DisplayName("差异 = 实盘 − 账面快照（盘盈正、盘亏负）")
    void diffCalculation() {
        assertThat(StocktakeRule.diff(new BigDecimal("100"), new BigDecimal("95")))
                .as("盘亏 5").isEqualByComparingTo("-5");
        assertThat(StocktakeRule.diff(new BigDecimal("100"), new BigDecimal("103")))
                .as("盘盈 3").isEqualByComparingTo("3");
        assertThat(StocktakeRule.diff(new BigDecimal("100"), new BigDecimal("100")))
                .as("无差异").isEqualByComparingTo("0");
        assertThat(StocktakeRule.diff(null, new BigDecimal("7")))
                .as("快照缺失按 0 处理").isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("实盘为负或为空 → 拒绝（负数不是盘亏，而是录错了）")
    void invalidActualQty() {
        assertThatThrownBy(() -> StocktakeRule.diff(BigDecimal.TEN, new BigDecimal("-1")))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为负");
        assertThatThrownBy(() -> StocktakeRule.diff(BigDecimal.TEN, null))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("只有「待录入」能录入明细（已提交的单不许改数）")
    void onlyDraftIsRecordable() {
        StocktakeRule.assertRecordable(StocktakeRule.STATUS_DRAFT);

        assertThatThrownBy(() -> StocktakeRule.assertRecordable(StocktakeRule.STATUS_PENDING))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION))
                .hasMessageContaining("仅「待录入」可录入明细");
        assertThatThrownBy(() -> StocktakeRule.assertRecordable(StocktakeRule.STATUS_APPROVED))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("提交审批：需有明细、且不能重复提交")
    void submitRules() {
        StocktakeRule.assertSubmittable(StocktakeRule.STATUS_DRAFT, 3);

        assertThatThrownBy(() -> StocktakeRule.assertSubmittable(StocktakeRule.STATUS_DRAFT, 0))
                .hasMessageContaining("没有明细");
        assertThatThrownBy(() -> StocktakeRule.assertSubmittable(StocktakeRule.STATUS_PENDING, 3))
                .hasMessageContaining("不可重复提交");
    }

    @Test
    @DisplayName("双人复核：创建人不得自审（80002）——盘点是账实差异合法化的唯一入口")
    void approverMustDifferFromCreator() {
        // 1. 正常：不同人审批通过
        StocktakeRule.assertApprovable(StocktakeRule.STATUS_PENDING, 1001L, 1002L);

        // 2. 自审：拒绝，且错误码为 80002
        assertThatThrownBy(() -> StocktakeRule.assertApprovable(StocktakeRule.STATUS_PENDING, 1001L, 1001L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER))
                .hasMessageContaining("不能是创建人");

        // 3. 审批人缺失（令牌未带 userId）：同样拒绝，不能默默放行
        assertThatThrownBy(() -> StocktakeRule.assertApprovable(StocktakeRule.STATUS_PENDING, 1001L, null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER));
    }

    @Test
    @DisplayName("非「待审批」不可审批 / 不可驳回（防止跨状态跳转）")
    void stateTransitionsAreStrict() {
        assertThatThrownBy(() -> StocktakeRule.assertApprovable(StocktakeRule.STATUS_DRAFT, 1L, 2L))
                .hasMessageContaining("仅「待审批」可审批");
        assertThatThrownBy(() -> StocktakeRule.assertRejectable(StocktakeRule.STATUS_APPROVED))
                .hasMessageContaining("仅「待审批」可驳回");
        assertThatThrownBy(() -> StocktakeRule.assertApprovable(StocktakeRule.STATUS_REJECTED, 1L, 2L))
                .as("已驳回是终态，需重新建单").isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("是否需要生成调整流水：有差异且未调整过；无差异不写流水")
    void needsAdjustRules() {
        assertThat(StocktakeRule.needsAdjust(new BigDecimal("-5"), StocktakeRule.ITEM_RECORDED)).isTrue();
        assertThat(StocktakeRule.needsAdjust(new BigDecimal("3"), StocktakeRule.ITEM_RECORDED)).isTrue();
        assertThat(StocktakeRule.needsAdjust(BigDecimal.ZERO, StocktakeRule.ITEM_RECORDED))
                .as("无差异不动库存（否则流水被 0 变动污染）").isFalse();
        assertThat(StocktakeRule.needsAdjust(null, StocktakeRule.ITEM_PENDING))
                .as("未录入的明细不调整").isFalse();
        assertThat(StocktakeRule.needsAdjust(new BigDecimal("5"), StocktakeRule.ITEM_ADJUSTED))
                .as("已调整过不重复调整（重复审批不会二次改库存）").isFalse();
    }

    @Test
    @DisplayName("单号格式：PT + yyyyMMdd + 4 位序号")
    void stocktakeNoFormat() {
        String no = StocktakeRule.stocktakeNo(7L);

        assertThat(no).startsWith("PT").hasSize(14).endsWith("0007");
    }
}
