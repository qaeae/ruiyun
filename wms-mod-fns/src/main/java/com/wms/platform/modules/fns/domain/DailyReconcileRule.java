package com.wms.platform.modules.fns.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.math.BigDecimal;

/**
 * 日终勾稽判定规则（{@code 04} §5.2）。
 *
 * <p><b>四种状态的区分（这个区分是本功能的关键）</b>：
 * <table border="1">
 *   <caption>状态含义</caption>
 *   <tr><th>状态</th><th>含义</th><th>处置</th></tr>
 *   <tr><td>一致</td><td>差额为 0</td><td>无需动作</td></tr>
 *   <tr><td>有差异</td><td>差额非 0</td><td>T+1 告警，T+3 内处理完毕</td></tr>
 *   <tr><td>待接入</td><td>**通道/承运商侧数据未提供**，根本没法对</td><td>不计入差异告警，但要显示出来</td></tr>
 *   <tr><td>已处理</td><td>人工确认差异已处理</td><td>留痕</td></tr>
 * </table>
 *
 * <p><b>为什么必须有"待接入"而不是把缺数据当成差异</b>：K2（持牌机构）未接入、承运商账单未上传时，
 * 每天都必然"有差异"。**每天都报假的差异，人就再也不看差异清单了**——等到真的差异出现，
 * 它会淹没在噪音里。把"没法对账"单独表达出来，差异清单才保得住信号价值。
 */
public final class DailyReconcileRule {

    /** 一致。 */
    public static final String STATUS_MATCH = "一致";

    /** 有差异。 */
    public static final String STATUS_DIFF = "有差异";

    /** 待接入（缺通道/承运商侧数据，无法对账）。 */
    public static final String STATUS_WAITING_CHANNEL = "待接入";

    /** 已处理。 */
    public static final String STATUS_HANDLED = "已处理";

    private DailyReconcileRule() {
    }

    /**
     * 判定勾稽状态。
     *
     * @param layer               层
     * @param hasCounterpartyData 通道/承运商侧数据是否可用
     * @param diffAmount          差额（平账条件为 0 的层使用）
     * @param diffCount           差异笔数（阈值类层使用，如三方层的超阈值单数）
     * @return 状态
     */
    public static String judge(DailyReconcileLayer layer, boolean hasCounterpartyData, BigDecimal diffAmount,
                               long diffCount) {
        // 1. 缺对方侧数据 → 待接入（**不是差异**）
        if (layer.isNeedsCounterpartyData() && !hasCounterpartyData) {
            return STATUS_WAITING_CHANNEL;
        }
        // 2. 有差异笔数 或 差额非 0 → 有差异
        BigDecimal diff = diffAmount == null ? BigDecimal.ZERO : diffAmount;
        if (diffCount > 0 || diff.signum() != 0) {
            return STATUS_DIFF;
        }
        // 3. 平账
        return STATUS_MATCH;
    }

    /**
     * 校验可否标记差异已处理。
     *
     * @param status 当前状态
     * @param remark 处理说明
     * @throws BizException 状态不允许 {@code 70001}；说明为空 {@code 10001}
     */
    public static void assertHandleable(String status, String remark) {
        if (STATUS_MATCH.equals(status)) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM, "该层已一致，无需处理");
        }
        if (STATUS_HANDLED.equals(status)) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM, "该层差异已处理过，不可重复处理");
        }
        if (remark == null || remark.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "标记差异已处理必须填写说明（否则无法追溯当时为什么认为没问题）");
        }
    }
}
