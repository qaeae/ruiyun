package com.wms.platform.modules.iv.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 盘点单状态与规则（{@code 01} §3.15、03 API-IV-03）。
 *
 * <p><b>两条不可妥协的规则</b>：
 * <ol>
 *   <li><b>审批人必须 ≠ 创建人</b>（`80002`）：盘点是"账实差异合法化"的唯一入口，
 *       一个人既能建单又能审批，等于允许他一个人把任何差异写成"盘亏"并让财务认账；</li>
 *   <li><b>差异基准是建单时的账面快照</b>，不是审批时的账面：录入期间发生的出库
 *       若被算进基准，会变成"盘盈"——金额对得上、真相全错。因此建单即固定快照，
 *       并在盘点期间按 `01` §6 冻结该库区作业（一期用告警 + 人工确认，不硬拦）。</li>
 * </ol>
 *
 * <p>状态机（与 `01` §1.2 "状态只能由状态机改"一致）：
 * <pre>
 *   待录入 --提交--> 待审批 --审批通过--> 已审批（生成 ADJUST 流水）
 *                        \--驳回--> 已驳回（终态：需重新建单，避免"驳回单被反复提交"）
 * </pre>
 * 已审批/已驳回均不可再改明细。已驳回是终态是刻意的：允许"驳回后直接改数再提交"
 * 会让审批轨迹失去意义（同一张单被改了几次、改了什么，从单上看不出来）。
 */
public final class StocktakeRule {

    /** 待录入。 */
    public static final String STATUS_DRAFT = "待录入";

    /** 待审批。 */
    public static final String STATUS_PENDING = "待审批";

    /** 已审批。 */
    public static final String STATUS_APPROVED = "已审批";

    /** 已驳回。 */
    public static final String STATUS_REJECTED = "已驳回";

    /** 明细：待录入。 */
    public static final String ITEM_PENDING = "待录入";

    /** 明细：已录入。 */
    public static final String ITEM_RECORDED = "已录入";

    /** 明细：已调整（已生成 ADJUST 流水）。 */
    public static final String ITEM_ADJUSTED = "已调整";

    private StocktakeRule() {
    }

    /**
     * 生成盘点单号。
     *
     * @param sequence 当日序号
     * @return 单号（PT+yyyyMMdd+4 位序号）
     */
    public static String stocktakeNo(long sequence) {
        return "PT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%04d", sequence % 10000);
    }

    /**
     * 计算差异（实盘 − 账面）。
     *
     * @param bookQty   账面快照
     * @param actualQty 实盘数
     * @return 差异（盘盈正、盘亏负）
     * @throws BizException 实盘为负时抛 {@code 10001}
     */
    public static BigDecimal diff(BigDecimal bookQty, BigDecimal actualQty) {
        // 1. 实盘必须非负（负数不是"盘亏"，而是录错了）
        if (actualQty == null || actualQty.signum() < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "实盘数不能为空且不能为负，实际：" + actualQty);
        }
        // 2. 差异 = 实盘 − 账面快照
        BigDecimal book = bookQty == null ? BigDecimal.ZERO : bookQty;
        return actualQty.subtract(book);
    }

    /**
     * 校验可录入明细。
     *
     * @param status 单头状态
     * @throws BizException 状态不允许时抛 {@code 20002}
     */
    public static void assertRecordable(String status) {
        if (!STATUS_DRAFT.equals(status)) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "盘点单当前状态为「" + status + "」，仅「" + STATUS_DRAFT + "」可录入明细"
                            + "（已提交的单不许改数，否则审批看到的内容与审批后执行的不一致）");
        }
    }

    /**
     * 校验可提交审批。
     *
     * @param status    单头状态
     * @param itemCount 明细条数
     * @throws BizException 状态或明细不满足时抛 {@code 20002}/{@code 10001}
     */
    public static void assertSubmittable(String status, long itemCount) {
        if (!STATUS_DRAFT.equals(status)) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "盘点单当前状态为「" + status + "」，不可重复提交");
        }
        if (itemCount <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "盘点单没有明细，不能提交审批");
        }
    }

    /**
     * 校验可审批，并校验**双人复核**。
     *
     * @param status    单头状态
     * @param createdBy 创建人
     * @param approver  审批人
     * @throws BizException 状态不允许 {@code 20002}；创建人=审批人 {@code 80002}
     */
    public static void assertApprovable(String status, Long createdBy, Long approver) {
        // 1. 状态：只有"待审批"能被审批（防止跨状态跳转）
        if (!STATUS_PENDING.equals(status)) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "盘点单当前状态为「" + status + "」，仅「" + STATUS_PENDING + "」可审批");
        }
        // 2. 双人复核：创建人不得自审（盘点是"账实差异合法化"的唯一入口）
        if (approver == null || createdBy == null || approver.equals(createdBy)) {
            throw new BizException(ErrorCode.APPROVER_MUST_DIFFER,
                    "审批人不能是创建人（双人复核：盘亏一旦自审通过，账实差异就被合法化了）");
        }
    }

    /**
     * 校验可否驳回。
     *
     * @param status 单头状态
     * @throws BizException 状态不允许时抛 {@code 20002}
     */
    public static void assertRejectable(String status) {
        if (!STATUS_PENDING.equals(status)) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "盘点单当前状态为「" + status + "」，仅「" + STATUS_PENDING + "」可驳回");
        }
    }

    /**
     * 明细是否需要生成调整流水（有差异且未调整过）。
     *
     * @param diffQty    差异
     * @param itemStatus 明细状态
     * @return 需要返回 {@code true}
     */
    public static boolean needsAdjust(BigDecimal diffQty, String itemStatus) {
        // 1. 已调整过的不重复生成（幂等，避免重复提交造成二次调整）
        if (ITEM_ADJUSTED.equals(itemStatus)) {
            return false;
        }
        // 2. 无差异不动库存（账面 10、实盘 10 却写一条 0 变动流水，只会污染流水台账）
        return diffQty != null && diffQty.signum() != 0;
    }
}
