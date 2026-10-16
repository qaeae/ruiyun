package com.wms.platform.modules.fns.domain;

/**
 * 对账差异处理结论（取值与 {@code fn_reconcile_item.handle_result} 一致）。
 *
 * <p>为什么用枚举 + 中文标签而不是散落中文字面量：这三个值要在 SQL、报表、财务口径中反复出现，
 * 一旦有人写成"自动平帐/自动通过/已平账"，对账报表就会多出几种"看起来一样"的取值，
 * 而财务只能人工合并——这正是对账系统最容易积累的脏数据。
 */
public enum HandleResult {

    /** 阈值内自动平账（≤1 元或 ≤1%）。 */
    AUTO("自动平账"),

    /** 超阈值挂账，等人工处理（>1% 且 ≤20%）。 */
    PENDING("挂账待处理"),

    /** 强制人工审核（>20%，不可自动通过）。 */
    MANUAL("人工处理"),

    /** 人工裁定后按责任方承担。 */
    LIABILITY("责任方承担");

    /** 落库值（{@code fn_reconcile_item.handle_result}）。 */
    private final String label;

    HandleResult(String label) {
        this.label = label;
    }

    /**
     * 落库值。
     *
     * @return 中文落库值
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否属于"差异已闭环、可进入结算"的结论。
     *
     * <p>{@link #PENDING} 与 {@link #MANUAL} 都不能进结算：差异还没定性，
     * 提前划拨等于把钱先付了再追——这正是对账流程存在的意义。
     *
     * @return 可结算返回 {@code true}
     */
    public boolean isSettleable() {
        return this == AUTO || this == LIABILITY;
    }
}
