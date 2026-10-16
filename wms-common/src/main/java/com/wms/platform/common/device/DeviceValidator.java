package com.wms.platform.common.device;

/**
 * 设备绑定校验器（03 §1.1）。
 *
 * <p>为什么定义成接口而不是直接在 {@code common} 里查库：设备档案（{@code md_device}）属于
 * {@code md} 业务域，而 {@code common} 是基础设施层——**基础设施不得反向依赖业务模块**
 * （由 {@code ModuleBoundaryTest} 阻断）。因此这里只留一个校验契约，由 {@code wms-core}
 * 装配时把 {@code md} 域的 {@code DeviceService} 注入进来。
 *
 * <p>实现方必须遵守：校验不通过时抛 {@code BizException(DEVICE_NOT_REGISTERED)}（带状态码），
 * 不得返回 boolean —— 只用返回值容易被调用方忽略，而"设备未登记"是安全边界。
 */
@FunctionalInterface
public interface DeviceValidator {

    /**
     * 校验设备是否已登记且启用（不通过时抛异常）。
     *
     * @param deviceCode 设备编号
     * @throws com.wms.platform.common.api.BizException 设备未登记或已停用时抛 {@code 10006}
     */
    void validate(String deviceCode);
}
