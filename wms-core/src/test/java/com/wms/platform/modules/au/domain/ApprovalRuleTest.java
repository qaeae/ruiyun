package com.wms.platform.modules.au.domain;

import com.wms.platform.common.approval.ApprovalBizType;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双人复核规则测试（02 M2-05、01 §3.16）。
 *
 * <p>每条对应一种"复核流被绕过"的真实手法：自己批自己、批完改内容、
 * 不说为什么就让人批、拿别人的单重复批。
 */
@DisplayName("双人复核规则（ApprovalRule）")
class ApprovalRuleTest {

    @Test
    @DisplayName("提交：涉金额类型必须给金额、事由必填、内容快照必填")
    void submitValidation() {
        // 正常
        ApprovalRule.assertSubmittable(ApprovalBizType.STOCK_ADJUST, new BigDecimal("5"), "盘亏调整", "{\"a\":1}");

        // 涉金额但没给金额 → 拒绝（复核人看不到风险量级）
        assertThatThrownBy(() -> ApprovalRule.assertSubmittable(ApprovalBizType.STOCK_ADJUST, null, "调一下",
                "{\"a\":1}"))
                .isInstanceOf(BizException.class).hasMessageContaining("必须填写 amount");

        // 不涉金额的类型可以不给金额（如授予超管）
        ApprovalRule.assertSubmittable(ApprovalBizType.ADMIN_GRANT, null, "新任平台负责人", "{\"userId\":9}");

        // 事由必填
        assertThatThrownBy(() -> ApprovalRule.assertSubmittable(ApprovalBizType.REFUND, BigDecimal.ONE, "  ",
                "{\"a\":1}"))
                .isInstanceOf(BizException.class).hasMessageContaining("申请事由");

        // 内容快照必填（它是"批的是哪一份内容"的唯一依据）
        assertThatThrownBy(() -> ApprovalRule.assertSubmittable(ApprovalBizType.REFUND, BigDecimal.ONE, "退款",
                null))
                .isInstanceOf(BizException.class).hasMessageContaining("内容快照");
    }

    @Test
    @DisplayName("双人复核：发起人不得自批（80002）；审批人缺失同样拒绝")
    void approverMustDiffer() {
        // 正常：他人复核
        ApprovalRule.assertReviewable(ApprovalRule.STATUS_PENDING, 1001L, 1002L);

        // 自批 → 80002
        assertThatThrownBy(() -> ApprovalRule.assertReviewable(ApprovalRule.STATUS_PENDING, 1001L, 1001L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER))
                .hasMessageContaining("不能是发起人");

        // 复核人缺失（令牌未带 userId）→ 同样拒绝，不能默默放行
        assertThatThrownBy(() -> ApprovalRule.assertReviewable(ApprovalRule.STATUS_PENDING, 1001L, null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER));
    }

    @Test
    @DisplayName("状态机：仅「待复核」可复核/驳回/撤回；终态不可再改")
    void stateMachineIsStrict() {
        for (String terminal : new String[]{ApprovalRule.STATUS_APPROVED, ApprovalRule.STATUS_REJECTED,
                ApprovalRule.STATUS_WITHDRAWN}) {
            assertThatThrownBy(() -> ApprovalRule.assertReviewable(terminal, 1L, 2L))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.APPROVAL_NOT_PENDING));
            assertThatThrownBy(() -> ApprovalRule.assertWithdrawable(terminal, 1L, 1L))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("内容指纹：不一致即阻断执行（防「批的是 100 元、执行的是 10000 元」）")
    void payloadHashMustMatch() {
        String hash = "abc123";
        // 一致：通过
        ApprovalRule.assertPayloadUnchanged(hash, "ABC123");

        // 不一致 → 80006
        assertThatThrownBy(() -> ApprovalRule.assertPayloadUnchanged(hash, "def456"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_PAYLOAD_CHANGED))
                .hasMessageContaining("不一致，已阻断执行");

        // 落库指纹缺失（脏数据）→ 同样阻断，不能默认放行
        assertThatThrownBy(() -> ApprovalRule.assertPayloadUnchanged(null, "def456"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("驳回必须填原因；撤回仅限发起人本人")
    void rejectAndWithdraw() {
        assertThatThrownBy(() -> ApprovalRule.assertRejectable(ApprovalRule.STATUS_PENDING, " "))
                .isInstanceOf(BizException.class).hasMessageContaining("必须填写原因");
        ApprovalRule.assertRejectable(ApprovalRule.STATUS_PENDING, "数量与盘点结果不符");

        // 撤回：本人可以
        ApprovalRule.assertWithdrawable(ApprovalRule.STATUS_PENDING, 1001L, 1001L);
        // 非本人 → 40300
        assertThatThrownBy(() -> ApprovalRule.assertWithdrawable(ApprovalRule.STATUS_PENDING, 1001L, 1002L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("业务类型白名单：矩阵外的类型拒绝（复核矩阵是制度，不是参数）")
    void bizTypeWhitelist() {
        assertThat(ApprovalBizType.of("STOCK_ADJUST")).isEqualTo(ApprovalBizType.STOCK_ADJUST);
        assertThat(ApprovalBizType.of("settle_dispatch")).isEqualTo(ApprovalBizType.SETTLE_DISPATCH);

        assertThatThrownBy(() -> ApprovalBizType.of("whatever"))
                .isInstanceOf(BizException.class).hasMessageContaining("不在复核矩阵内");
        assertThatThrownBy(() -> ApprovalBizType.of(null))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("单号格式：AP + yyyyMMdd + 雪花ID 的 36 进制（≤ VARCHAR(40)）")
    void approvalNoFormat() {
        String no = ApprovalRule.approvalNo(7L);
        assertThat(no).startsWith("AP").hasSizeLessThanOrEqualTo(40);
        // 36 进制：7 → "7"；日期前缀固定 8 位
        assertThat(no).endsWith("7");
        assertThat(no).matches("AP\\d{8}[0-9A-Z]+");
    }

    @Test
    @DisplayName("回归：单号必须唯一——旧实现 `id % 10000` 会碰撞（联调实测 500）")
    void approvalNoMustBeUnique() {
        // ① 旧实现的真实碰撞场景：两个 snowflake ID 相差正好 10000 时，`% 10000` 结果相同
        long base = 104203844782653440L;
        assertThat(ApprovalRule.approvalNo(base))
                .as("相差 10000 的 ID 在旧实现（取模 1 万）下会生成同一个单号，导致 uk_approval_no 冲突")
                .isNotEqualTo(ApprovalRule.approvalNo(base + 10000L));

        // ② 大批量唯一性：一天内累计审批单量级（这里取 5 万个）必须零碰撞
        java.util.Set<String> generated = new java.util.HashSet<>();
        long start = 104203844782653440L;
        for (int i = 0; i < 50000; i++) {
            generated.add(ApprovalRule.approvalNo(start + i));
        }
        assertThat(generated).as("5 万个 ID 生成的单号必须互不相同").hasSize(50000);
    }
}
