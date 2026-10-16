package com.wms.platform.modules.iv.api.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 库存不变量校验结果（03 API-IV-06）。
 *
 * <p>返回**逐条结果**而不是一个布尔值：财务/库管需要知道"哪条不变量、差几行"，
 * 才知道下一步该查什么。只回 `passed=false` 等于把问题原样丢回去。
 */
@Getter
@Setter
public class InvariantCheckVo {

    /** 是否全部通过。 */
    private boolean allPassed;

    /** 未通过条数。 */
    private int failedCount;

    /** 逐条结果。 */
    private List<Item> items;

    /**
     * 静态工厂（B4）。
     *
     * @param items 逐条结果
     * @return Vo
     */
    public static InvariantCheckVo of(List<Item> items) {
        InvariantCheckVo vo = new InvariantCheckVo();
        vo.setItems(items);
        vo.setFailedCount((int) items.stream().filter(item -> !item.passed()).count());
        vo.setAllPassed(vo.getFailedCount() == 0);
        return vo;
    }

    /**
     * 单条不变量结果。
     *
     * @param name      不变量名
     * @param violation 违反行数
     * @param passed    是否通过
     */
    public record Item(String name, long violation, boolean passed) {

        /**
         * 由领域结果构造。
         *
         * @param result 领域结果
         * @return 条目
         */
        public static Item of(com.wms.platform.modules.iv.domain.InventoryInvariant.InvariantResult result) {
            return new Item(result.name(), result.violation(), result.passed());
        }
    }
}
