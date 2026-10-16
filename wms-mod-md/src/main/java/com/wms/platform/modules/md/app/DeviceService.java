package com.wms.platform.modules.md.app;

import com.wms.platform.modules.md.api.ro.DeviceEditRo;
import com.wms.platform.modules.md.api.ro.DeviceEnableRo;
import com.wms.platform.modules.md.api.ro.DeviceRegisterRo;
import com.wms.platform.modules.md.api.vo.DeviceVo;

import java.util.List;

/**
 * 设备档案应用服务（03 API-MD-05 / 02 M2-03）。
 *
 * <p>它同时承担**设备绑定校验**（{@link #assertEnabled}）：03 §1.1 要求 PDA 请求必须来自
 * 已登记且启用的设备。校验放在应用服务而不是拦截器里，是因为拦截器属于基础设施层，
 * 不应知道"设备台账怎么查"（装配见 {@code PlatformInfrastructureConfig}）。
 */
public interface DeviceService {

    /**
     * 登记设备。
     *
     * @param ro 登记入参
     * @return 设备视图
     */
    DeviceVo register(DeviceRegisterRo ro);

    /**
     * 编辑设备（设备编号不可改）。
     *
     * @param ro 编辑入参
     * @return 设备视图
     */
    DeviceVo edit(DeviceEditRo ro);

    /**
     * 启用/停用设备。
     *
     * @param ro 启停入参
     * @return 设备视图
     */
    DeviceVo enableOpt(DeviceEnableRo ro);

    /**
     * 按设备编号查询。
     *
     * @param deviceCode 设备编号
     * @return 设备视图
     */
    DeviceVo findByCode(String deviceCode);

    /**
     * 按仓库/类型列出设备（台账页）。
     *
     * @param warehouseId 仓库 ID（可空）
     * @param deviceType  设备类型（可空）
     * @return 设备视图列表
     */
    List<DeviceVo> list(Long warehouseId, String deviceType);

    /**
     * 设备绑定校验（03 §1.1）：未登记或已停用时抛异常。
     *
     * @param deviceCode 设备编号（来自请求头 {@code X-Device-Code}）
     */
    void assertEnabled(String deviceCode);
}
