package com.wms.platform.modules.md.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.md.api.ro.DeviceEditRo;
import com.wms.platform.modules.md.api.ro.DeviceEnableRo;
import com.wms.platform.modules.md.api.ro.DeviceRegisterRo;
import com.wms.platform.modules.md.api.vo.DeviceVo;
import com.wms.platform.modules.md.app.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备档案接口（03 API-MD-05 / 02 M2-03）。
 *
 * <p>规范落地（B2/B3）：类上 {@code @Validated}、请求体 {@code @Valid @RequestBody}、
 * 入参 {@code Ro}、出参 {@code Vo}，不使用 {@code Map} 收发。
 *
 * <p>为什么这里不做分页：一期设备总量约 30 台 PDA + 少量打印机/秤（02 §一 规模），
 * 分页收益为负（前端要多写一套分页逻辑）。设备量级上千时再改为分页接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/master/devices")
@RequiredArgsConstructor
public class DeviceController {

    /** 设备档案应用服务。 */
    private final DeviceService deviceService;

    /**
     * 登记设备。
     *
     * @param ro 登记入参
     * @return 设备视图
     */
    @RequiresPermission(PermissionCodes.DEVICE_MANAGE)
    @PostMapping
    public ApiResponse<DeviceVo> register(@Valid @RequestBody DeviceRegisterRo ro) {
        return ApiResponse.ok(deviceService.register(ro));
    }

    /**
     * 编辑设备（设备编号不可改）。
     *
     * @param ro 编辑入参
     * @return 设备视图
     */
    @RequiresPermission(PermissionCodes.DEVICE_MANAGE)
    @PutMapping
    public ApiResponse<DeviceVo> edit(@Valid @RequestBody DeviceEditRo ro) {
        return ApiResponse.ok(deviceService.edit(ro));
    }

    /**
     * 启用/停用设备。
     *
     * @param ro 启停入参
     * @return 设备视图
     */
    @RequiresPermission(PermissionCodes.DEVICE_MANAGE)
    @PostMapping("/enable-opt")
    public ApiResponse<DeviceVo> enableOpt(@Valid @RequestBody DeviceEnableRo ro) {
        return ApiResponse.ok(deviceService.enableOpt(ro));
    }

    /**
     * 按设备编号查询。
     *
     * @param deviceCode 设备编号
     * @return 设备视图
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/{deviceCode}")
    public ApiResponse<DeviceVo> findByCode(@PathVariable String deviceCode) {
        return ApiResponse.ok(deviceService.findByCode(deviceCode));
    }

    /**
     * 设备列表（台账页；不做分页，见类注释）。
     *
     * @param warehouseId 仓库 ID（可空）
     * @param deviceType  设备类型（可空）
     * @return 设备视图列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping
    public ApiResponse<List<DeviceVo>> list(@RequestParam(required = false) Long warehouseId,
                                            @RequestParam(required = false) String deviceType) {
        return ApiResponse.ok(deviceService.list(warehouseId, deviceType));
    }
}
