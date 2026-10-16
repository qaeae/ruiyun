package com.wms.platform.common.mapstruct;

import cn.hutool.core.collection.CollUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * MapStruct 映射器公共扩展（规范条目 B8）。
 *
 * <p>所有映射器必须 {@code extends MapStructExt}，并遵守以下约定：
 * <ol>
 *   <li>映射器类名以 {@code MS} 结尾，且 {@code @Mapper(config = MapperCentralConfig.class)}；</li>
 *   <li>单例通过 {@code INSTANCE = Mappers.getMapper(XxxMS.class)} 暴露；</li>
 *   <li><b>批量转换必须调用本接口的 {@link #toPojoList}</b>，
 *       禁止在调用方写 for 循环逐条 {@code add}，也禁止自己写 {@code stream().map().collect()}；</li>
 *   <li><b>禁止</b>在映射器里声明 {@code List<Xxx> toXxxList(List<Yyy>)} 这类集合方法
 *       （由 {@code wms-archtest} 的规则阻断）。</li>
 * </ol>
 *
 * <p>这样收敛的收益：集合映射只有一处实现，空值/可变性/性能策略不会被 20 个映射器各写一遍。
 */
public interface MapStructExt {

    /**
     * 批量映射：{@code MapStructExt.toPojoList(list, INSTANCE::toXxxVo)}。
     *
     * @param source 源集合，可为 {@code null}
     * @param mapper 单条映射函数（通常为映射器的 {@code INSTANCE::toXxx} 方法引用）
     * @param <S>    源类型
     * @param <T>    目标类型
     * @return 可变结果列表（调用方可继续排序/过滤）；源为 {@code null} 或空时返回空列表，**不返回 null**
     */
    static <S, T> List<T> toPojoList(List<S> source, Function<S, T> mapper) {
        // 1. 空入参保护：统一返回空集合，避免调用方到处判 null（规范要求"不返回 null"）
        if (CollUtil.isEmpty(source)) {
            return CollUtil.newArrayList();
        }
        // 2. 结果集按已知批次大小预分配（需要可变集合，B10：不用不可变工厂）
        List<T> target = new ArrayList<>(source.size());
        // 3. 逐条映射（唯一允许写循环的地方）
        for (S item : source) {
            target.add(mapper.apply(item));
        }
        // 4. 返回可变列表，便于调用方二次加工
        return target;
    }
}
