package com.wms.platform.modules.md.infrastructure.mapstruct;

import com.wms.platform.common.mapstruct.MapStructExt;
import com.wms.platform.common.mapstruct.MapperCentralConfig;
import com.wms.platform.modules.md.api.vo.DeviceVo;
import com.wms.platform.modules.md.infrastructure.po.DevicePo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 设备档案映射器（规范条目 B8）。
 *
 * <p>三处刻意的约定：
 * <ol>
 *   <li><b>放 infrastructure 而不是 api</b>：它依赖 {@code Po}（infrastructure），
 *       而 {@code ModuleBoundaryTest} 禁止 {@code api} 依赖 {@code infrastructure}——
 *       放 api 会直接违反分层（这也是"映射器放哪"最容易踩的一坑）；</li>
 *   <li>{@code DeviceVo} <b>不继承 {@code DevicePo}</b>：本项目的 Po 在 infrastructure 层，
 *       api 不能依赖它（外部规范的"Vo 继承 Po"在我们的包结构下不可用）。
 *       代价是给 Po 加字段时要同步 Vo——已登记在 {@code STANDARDS-MAPPING.md}；</li>
 *   <li><b>不声明集合方法</b>：批量转换统一走 {@code MapStructExt.toPojoList(list, INSTANCE::toVo)}。</li>
 * </ol>
 */
@Mapper(config = MapperCentralConfig.class)
public interface DeviceMS extends MapStructExt {

    /** 单例（不依赖 Spring 注入，便于单测与静态调用）。 */
    DeviceMS INSTANCE = Mappers.getMapper(DeviceMS.class);

    /**
     * Po → Vo。
     *
     * @param po 设备记录
     * @return 设备视图对象
     */
    @Mapping(target = "deviceTypeLabel", ignore = true)
    DeviceVo toVo(DevicePo po);
}
