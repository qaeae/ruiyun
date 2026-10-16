package com.wms.platform.modules.ops.domain;

/**
 * 任务执行结果（`01` §3.17）。
 *
 * <p>{@link #SKIPPED} 是刻意独立的状态：**"没抢到租约"不是失败**。
 * 如果把它记成失败，多实例部署下每隔一个周期就会产生一半的失败记录，
 * 运维会去"修"一个根本不存在的故障（这是告警疲劳最常见的成因之一）。
 */
public enum JobRunStatus {

    /** 成功。 */
    SUCCESS("成功"),

    /** 失败（任务是幂等的，可手工补跑）。 */
    FAILED("失败"),

    /** 跳过（没抢到租约、任务被禁用、或前置条件不满足）。 */
    SKIPPED("跳过");

    /** 中文落库值。 */
    private final String label;

    JobRunStatus(String label) {
        this.label = label;
    }

    /**
     * 中文落库值。
     *
     * @return 标签
     */
    public String getLabel() {
        return label;
    }
}
