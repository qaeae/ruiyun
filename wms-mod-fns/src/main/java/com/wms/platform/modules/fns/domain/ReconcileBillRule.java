package com.wms.platform.modules.fns.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 对账链路规则（{@code 01} §3.6/§3.7，03 API-FNS-*）。
 *
 * <p><b>数据来源口径（一期）</b>：对账的本质是"两方账单比对"——
 * <ul>
 *   <li><b>平台侧</b>：`fn_fee_order` 里我们自己的物流运费费用单（`fee_type=LOGISTICS`，`biz_no` = 运单号）；</li>
 *   <li><b>承运商侧**：承运商月度对账文件（一期由运营导入 / 后续接承运商接口）。</li>
 * </ul>
 * 之所以不让两边都从系统里推：**对账的意义就是发现"对方算的"和"我们算的"不一致**，
 * 若两边都取自同一份数据，对账会永远"平"，也就永远发现不了问题。
 *
 * <p><b>阈值口径（2026-10-14 张三确认，照 PoC-5 默认）</b>：
 * 差异 ≤1 元 **或** ≤1% → 自动平账；>1% 且 ≤20% → 挂账待处理；>20% → 强制人工。
 * 分母取**平台费用**（0 或负 → 强制人工）。口径常量在 {@link ReconcileDiffRule}，
 * 本类只负责"账单级"的状态与汇总判定。
 */
public final class ReconcileBillRule {

    /** 待确认（无差异或全部自动平账）。 */
    public static final String STATUS_PENDING_CONFIRM = "待确认";

    /** 差异待处理（存在挂账/人工条目）。 */
    public static final String STATUS_DIFF_OPEN = "差异待处理";

    /** 已确认。 */
    public static final String STATUS_CONFIRMED = "已确认";

    /** 差异类型：无差异。 */
    public static final String DIFF_TYPE_NONE = "无差异";

    /** 差异类型：平台多计（平台费用 > 承运商费用）。 */
    public static final String DIFF_TYPE_PLATFORM_HIGHER = "平台多计";

    /** 差异类型：承运商多计（承运商费用 > 平台费用）。 */
    public static final String DIFF_TYPE_CARRIER_HIGHER = "承运商多计";

    /** 差异类型：平台无对应费用（运费没进我们自己的账）。 */
    public static final String DIFF_TYPE_PLATFORM_MISSING = "平台无对应费用";

    private ReconcileBillRule() {
    }

    /**
     * 分类差异类型（仅用于展示与筛选，不参与阈值判定）。
     *
     * @param platformFee 平台费用（可空 = 平台无该运单费用）
     * @param carrierFee  承运商费用
     * @return 差异类型
     */
    public static String diffType(java.math.BigDecimal platformFee, java.math.BigDecimal carrierFee) {
        // 1. 平台侧缺失：**必须挂人工**，不能当成"差异为 0"（那笔钱根本没进我们账）
        if (platformFee == null) {
            return DIFF_TYPE_PLATFORM_MISSING;
        }
        // 2. 比对
        int cmp = platformFee.compareTo(carrierFee == null ? java.math.BigDecimal.ZERO : carrierFee);
        if (cmp == 0) {
            return DIFF_TYPE_NONE;
        }
        return cmp > 0 ? DIFF_TYPE_PLATFORM_HIGHER : DIFF_TYPE_CARRIER_HIGHER;
    }

    /**
     * 生成对账单号。
     *
     * @param carrierCode 承运商编码
     * @param period      账期（yyyyMM）
     * @return 对账单号
     */
    public static String billNo(String carrierCode, String period) {
        return "RCL" + period + "-" + carrierCode;
    }

    /**
     * 根据差异条目统计结果推导账单状态。
     *
     * @param manualCount 需人工处理的条数
     * @return 账单状态
     */
    public static String statusOf(long manualCount) {
        return manualCount > 0 ? STATUS_DIFF_OPEN : STATUS_PENDING_CONFIRM;
    }

    /**
     * 校验可否确认对账单。
     *
     * <p><b>为什么存在未处理的差异条目时不允许确认</b>：确认意味着"这份账我认了"，
     * 之后会据此生成结算批次并把钱划给承运商。若强行放行，差异条目会留在库里没人处理，
     * 而钱已经付出去了——**这正是"对账走过场"的典型成因**。
     *
     * @param status      账单状态
     * @param manualCount 未处理的人工条目数
     * @throws BizException 状态不允许 {@code 70001}；存在未处理差异 {@code 70002}
     */
    public static void assertConfirmable(String status, long manualCount) {
        // 1. 先报"还有多少差异没处理"：这条信息比"状态不对"更有用——
        //    调用方据此知道下一步做什么（逐条判定责任方），而不是去猜状态机
        if (manualCount > 0) {
            throw new BizException(ErrorCode.DIFF_EXCEED_THRESHOLD_NEED_MANUAL,
                    "仍有 " + manualCount + " 条差异未处理，需逐条判定责任方后才能确认");
        }
        // 2. 状态：已确认不许重复确认
        if (STATUS_CONFIRMED.equals(status)) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM, "该对账单已确认，不可重复确认");
        }
        // 3. 其它状态（理论上不会到：无差异时状态应为「待确认」）
        if (!STATUS_PENDING_CONFIRM.equals(status)) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM,
                    "对账单当前状态为「" + status + "」，不可确认");
        }
    }

    /**
     * 校验可否人工处理某条差异。
     *
     * @param handleResult 当前处理结果
     * @param liability    责任方
     * @throws BizException 参数非法 {@code 10001}
     */
    public static void assertHandleable(String handleResult, Liability liability, String remark) {
        // 1. 已按责任方承担处理过的条目不允许再改（避免"改到能过为止"）
        if (HandleResult.LIABILITY.getLabel().equals(handleResult)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该差异已判定责任方，不可重复处理");
        }
        // 2. 自动平账的条目无需人工介入
        if (HandleResult.AUTO.getLabel().equals(handleResult)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该差异在阈值内已自动平账，无需人工处理");
        }
        // 3. 必须给出责任方与说明
        if (liability == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须判定责任方（平台/商家/物流）");
        }
        if (remark == null || remark.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "人工处理差异必须填写说明（后续追责与审计依据）");
        }
    }
}
