package com.wms.platform.common.mapstruct;

import org.mapstruct.Builder;
import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * 全项目 MapStruct 统一配置（规范条目 B8）。
 *
 * <p>所有映射器统一写 {@code @Mapper(config = MapperCentralConfig.class)}，
 * <b>不要</b>各自重复声明策略——策略分散是"同类问题在不同映射器表现不一致"的根源。
 *
 * <ul>
 *   <li>{@code unmappedTargetPolicy = IGNORE}：目标对象多出未映射属性时不报警告。
 *       本项目 Po 常见多出审计字段（createdAt/updatedAt/deleted），逐个 {@code @Mapping(ignore=true)}
 *       会把映射器写成一堵墙；统一忽略 + 依赖编译期字段核对更可维护。</li>
 *   <li>{@code builder = @Builder(disableBuilder = true)}：禁用 Builder 模式，
 *       走 getter/setter（与 Lombok {@code @Getter/@Setter} + 静态工厂 B4 的落点一致）。</li>
 * </ul>
 */
@MapperConfig(
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        builder = @Builder(disableBuilder = true))
public interface MapperCentralConfig {
}
