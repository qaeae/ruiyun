package com.wms.platform.modules.iv.domain;

/**
 * 库存不变量清单（{@code 01} §4）。
 *
 * <p>这里只放**不变量名与口径说明**，SQL 一律收敛在 Mapper（B1）：
 * 两处都写 SQL 的话，日终任务与人工排障用的口径迟早不一致，而"任务说没事、人工查出来有差异"
 * 是最消耗信任的一类分歧。
 *
 * <p>一期覆盖第 1、2 条（库存域）；第 3、4 条属资金域（{@code ac_*}）由 W4 实现；
 * 第 5 条靠唯一键保证；第 6 条靠 CHECK 约束 + 条件更新保证。
 */
public final class InventoryInvariant {

    /** 不变量 1：SKU 聚合账三态自洽（{@code physical = available + reserved}）。 */
    public static final String NAME_SKU_BALANCE = "不变量1：SKU三态自洽";

    /** 不变量 1b：SKU 三态非负。 */
    public static final String NAME_SKU_NONNEG = "不变量1b：SKU三态非负";

    /** 不变量 2：库位明细合计 = SKU 聚合实物。 */
    public static final String NAME_LOCATION_SUM_MATCH = "不变量2：库位合计=SKU实物";

    /** 不变量 2b：库位实物与分配量非负。 */
    public static final String NAME_LOCATION_NONNEG = "不变量2b：库位非负";

    private InventoryInvariant() {
    }

    /**
     * 校验结果。
     *
     * @param name      不变量名
     * @param violation 违反行数（0 = 通过）
     */
    public record InvariantResult(String name, long violation) {

        /**
         * 是否通过。
         *
         * @return 通过返回 {@code true}
         */
        public boolean passed() {
            return violation == 0;
        }
    }
}
