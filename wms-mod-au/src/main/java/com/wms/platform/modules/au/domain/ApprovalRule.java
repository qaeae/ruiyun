package com.wms.platform.modules.au.domain;

import com.wms.platform.common.approval.ApprovalBizType;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 双人复核规则（`02` M2-05、`03` API-AUTH-04/05、`01` §3.16）。
 *
 * <p>状态机：
 * <pre>
 *   待复核 --复核通过--> 已通过（由处理器执行副作用，落 executed=1）
 *        \--驳回----> 已驳回
 *        \--申请人撤回--> 已撤回
 * </pre>
 * 终态（已通过/已驳回/已撤回）不可再改。
 *
 * <p><b>三条红旗</b>——每一条都对应一种"复核流被绕过"的真实手法：
 * <ol>
 *   <li><b>复核人 ≠ 发起人</b>（{@code 80002}）：自己发起自己批，等于没有复核；</li>
 *   <li><b>内容指纹校验</b>（{@code 80006}）：提交后再改内容（"批的是 100 元，执行的是 10000 元"）；</li>
 *   <li><b>必须说明事由</b>：复核人不知道为什么要复核，只能凭感觉点头——那还不如不设复核。</li>
 * </ol>
 */
public final class ApprovalRule {

    /** 待复核。 */
    public static final String STATUS_PENDING = "待复核";

    /** 已通过。 */
    public static final String STATUS_APPROVED = "已通过";

    /** 已驳回。 */
    public static final String STATUS_REJECTED = "已驳回";

    /** 已撤回。 */
    public static final String STATUS_WITHDRAWN = "已撤回";

    private ApprovalRule() {
    }

    /**
     * 生成审批单号：{@code AP + yyyyMMdd + 雪花ID 的 36 进制}。
     *
     * <p><b>为什么不是"4 位流水号"（真实缺陷，P1 联调抓到）</b>：原实现是
     * {@code "AP" + yyyyMMdd + String.format("%04d", sequence % 10000)}——**取模 1 万**。
     * 入参是雪花 ID，取模后只剩 4 位十进制，按生日悖论：一天内累计约 120 张审批单时，
     * 碰撞概率就已接近 50%。实测表现是某次提交直接 500：
     * {@code Duplicate entry '1-AP202610158800' for key 'au_approval.uk_approval_no'}。
     * 唯一键挡住了脏数据（防线有效），但业务已经失败——**单号绝不能靠概率**。
     *
     * <p>现在用 36 进制编码整颗雪花 ID：这是**双射**（一一对应），所以单号在全局唯一，
     * 且因为雪花 ID 递增，单号也自然按时间递增。代价是比"4 位流水号"长一些（约 23 字符），
     * 但仍保留可读前缀（{@code AP} + 日期），长度在 {@code approval_no VARCHAR(40)} 之内。
     *
     * @param id 雪花 ID
     * @return 单号
     */
    public static String approvalNo(long id) {
        return "AP" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + Long.toString(id, 36).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 校验提交入参。
     *
     * @param bizType     业务类型
     * @param amount      金额（可空）
     * @param reason      事由
     * @param payloadJson 内容快照
     * @throws BizException 参数不合法时抛 {@code 10001}
     */
    public static void assertSubmittable(ApprovalBizType bizType, java.math.BigDecimal amount, String reason,
                                         String payloadJson) {
        // 1. 涉及金额的必须填金额（复核人要一眼看到风险量级）
        if (bizType.isAmountRequired() && amount == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    bizType.getLabel() + "涉及金额，必须填写 amount（复核人据此判断风险量级）");
        }
        // 2. 事由必填
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须填写申请事由（否则复核人只能凭感觉点头）");
        }
        // 3. 内容快照必填（它是"批的是哪一份内容"的唯一依据）
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须提交审批内容快照（否则无法校验执行内容与审批内容一致）");
        }
    }

    /**
     * 校验可复核，并校验**双人复核**。
     *
     * @param status    当前状态
     * @param applyBy   发起人
     * @param approver  复核人（取自令牌身份）
     * @throws BizException 状态不允许 {@code 80005}；同人复核 {@code 80002}
     */
    public static void assertReviewable(String status, Long applyBy, Long approver) {
        // 1. 只有「待复核」可复核（终态不可再改；避免"通过 + 驳回"两条结论同时落库）
        if (!STATUS_PENDING.equals(status)) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING,
                    "该审批单当前状态为「" + status + "」，仅「" + STATUS_PENDING + "」可复核");
        }
        // 2. 双人复核：发起人不得自批
        if (approver == null || applyBy == null || approver.equals(applyBy)) {
            throw new BizException(ErrorCode.APPROVER_MUST_DIFFER,
                    "复核人不能是发起人（自己发起自己批 = 没有复核）");
        }
    }

    /**
     * 校验内容指纹（防"审批后改内容"）。
     *
     * @param storedHash  提交时落库的指纹
     * @param actualHash  当前内容的指纹
     * @throws BizException 不一致时抛 {@code 80006}
     */
    public static void assertPayloadUnchanged(String storedHash, String actualHash) {
        if (storedHash == null || !storedHash.equalsIgnoreCase(actualHash)) {
            throw new BizException(ErrorCode.APPROVAL_PAYLOAD_CHANGED,
                    "审批内容与提交时不一致，已阻断执行（防止「批的是 100 元、执行的是 10000 元」）；"
                            + "如需变更请重新发起审批");
        }
    }

    /**
     * 校验可驳回。
     *
     * @param status 当前状态
     * @param reason 驳回原因
     * @throws BizException 状态不允许 {@code 80005}；原因缺失 {@code 10001}
     */
    public static void assertRejectable(String status, String reason) {
        if (!STATUS_PENDING.equals(status)) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING,
                    "该审批单当前状态为「" + status + "」，仅「" + STATUS_PENDING + "」可驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驳回必须填写原因（否则发起人不知道该改什么）");
        }
    }

    /**
     * 校验可撤回（仅发起人本人、且仍待复核）。
     *
     * @param status   当前状态
     * @param applyBy  发起人
     * @param operator 操作人
     * @throws BizException 状态不允许 {@code 80005}；非本人 {@code 40300}
     */
    public static void assertWithdrawable(String status, Long applyBy, Long operator) {
        if (!STATUS_PENDING.equals(status)) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING,
                    "该审批单当前状态为「" + status + "」，仅「" + STATUS_PENDING + "」可撤回");
        }
        if (operator == null || !operator.equals(applyBy)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有发起人可以撤回自己的审批单");
        }
    }
}
