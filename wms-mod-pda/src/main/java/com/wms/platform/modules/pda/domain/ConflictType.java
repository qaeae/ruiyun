package com.wms.platform.modules.pda.domain;

/**
 * 补传冲突类型（{@code 01} §6 的冲突分级，落到 {@code pf_pda_conflict.conflict_type}）。
 */
public enum ConflictType {

    /** 单据状态已流转：服务端已被别的路径推进，**不得覆盖**（{@code 01} §6 第 2 行）。 */
    STATUS_CHANGED("状态已流转"),

    /** 库存已被其他任务占用：条件更新影响行数为 0（{@code 01} §6 第 3 行）。 */
    STOCK_OCCUPIED("库存被占用"),

    /** 号码不可识别（错扫/单据不存在）：拒绝 + 记误扫，用于校准现场（{@code 01} §6 第 4 行）。 */
    CODE_UNKNOWN("号码不可识别");

    /** 落库值。 */
    private final String label;

    ConflictType(String label) {
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
}
