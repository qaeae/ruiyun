package com.wms.platform.modules.md.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.modules.md.api.ro.LocationCreateRo;
import com.wms.platform.modules.md.api.ro.MerchantCreateRo;
import com.wms.platform.modules.md.api.ro.SkuCreateRo;
import com.wms.platform.modules.md.api.ro.UnitConvCreateRo;
import com.wms.platform.modules.md.api.ro.WarehouseCreateRo;
import com.wms.platform.modules.md.api.ro.ZoneCreateRo;
import com.wms.platform.modules.md.api.vo.IdVo;
import com.wms.platform.modules.md.api.vo.LocationVo;
import com.wms.platform.modules.md.api.vo.MerchantVo;
import com.wms.platform.modules.md.api.vo.QtyConvertVo;
import com.wms.platform.modules.md.api.vo.SkuVo;
import com.wms.platform.modules.md.api.vo.UnitConvVo;
import com.wms.platform.modules.md.api.vo.WarehouseVo;
import com.wms.platform.common.security.PublicApi;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.md.api.vo.ZoneVo;
import com.wms.platform.modules.md.app.MasterDataAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 主数据接口（03 API-MD-01 ~ API-MD-07，设备档案见 {@link DeviceController}）。
 *
 * <p><b>路径与 03 一致</b>：{@code /api/v1/master/&lt;entity&gt;}。写接口都在 {@code /api/**} 下，
 * 因此已被 {@code IdempotencyInterceptor} 要求带 {@code Idempotency-Key}（主数据重复提交
 * 由唯一键 + 本层的存在性校验共同兜住，不依赖幂等表缓存响应）。
 *
 * <p><b>启停用为什么值得单独做接口</b>：主数据只增不改的后果是"历史脏数据永远在用"——
 * 停用的库位/商品必须能被识别出来并拒绝现场作业（PDA 扫码会因解析失败而拒绝），
 * 而不是靠运维去数据库里改状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1/master")
@RequiredArgsConstructor
public class MasterDataController {

    /** 主数据服务。 */
    private final MasterDataAdminService masterDataAdminService;

    /**
     * 新增商家（API-MD-01）。
     *
     * @param ro 入参
     * @return 商家 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_MERCHANT_CREATE)
    @PostMapping("/merchants")
    public ApiResponse<IdVo> createMerchant(@Valid @RequestBody MerchantCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.createMerchant(ro)));
    }

    /**
     * 新增仓库（API-MD-02）。
     *
     * @param ro 入参
     * @return 仓库 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_WAREHOUSE_CREATE)
    @PostMapping("/warehouses")
    public ApiResponse<IdVo> createWarehouse(@Valid @RequestBody WarehouseCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.createWarehouse(ro)));
    }

    /**
     * 新增库区（API-MD-02）。
     *
     * @param ro 入参
     * @return 库区 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_WAREHOUSE_CREATE)
    @PostMapping("/zones")
    public ApiResponse<IdVo> createZone(@Valid @RequestBody ZoneCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.createZone(ro)));
    }

    /**
     * 新增库位（API-MD-02）。
     *
     * @param ro 入参
     * @return 库位 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_WAREHOUSE_CREATE)
    @PostMapping("/locations")
    public ApiResponse<IdVo> createLocation(@Valid @RequestBody LocationCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.createLocation(ro)));
    }

    /**
     * 新增 SKU（API-MD-03）。
     *
     * @param ro 入参
     * @return 商品 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_SKU_CREATE)
    @PostMapping("/skus")
    public ApiResponse<IdVo> createSku(@Valid @RequestBody SkuCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.createSku(ro)));
    }

    /**
     * 维护箱规换算（API-MD-04）。
     *
     * @param skuId 商品 ID
     * @param ro    入参
     * @return 换算配置 ID
     */
    @RequiresPermission(PermissionCodes.MASTER_SKU_UNIT_CONV)
    @PostMapping("/skus/{skuId}/unit-conversions")
    public ApiResponse<IdVo> addUnitConversion(@PathVariable long skuId,
                                               @Valid @RequestBody UnitConvCreateRo ro) {
        return ApiResponse.ok(new IdVo(masterDataAdminService.addUnitConversion(skuId, ro)));
    }

    /**
     * 数量换算（计费与库存上架共用；无换算路径时返回业务失败码而非近似值）。
     *
     * @param skuId    商品 ID
     * @param qty      数量
     * @param fromUnit 源单位
     * @param toUnit   目标单位
     * @return 换算结果
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/skus/{skuId}/convert")
    public ApiResponse<QtyConvertVo> convertQty(@PathVariable long skuId, @RequestParam BigDecimal qty,
                                                @RequestParam String fromUnit, @RequestParam String toUnit) {
        BigDecimal result = masterDataAdminService.convertQty(skuId, qty, fromUnit, toUnit);
        return ApiResponse.ok(new QtyConvertVo(skuId, fromUnit, toUnit, qty, result));
    }

    /**
     * 商家列表（API-MD-07）。
     *
     * @param status 状态（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/merchants")
    public ApiResponse<List<MerchantVo>> listMerchants(@RequestParam(required = false) String status) {
        return ApiResponse.ok(masterDataAdminService.listMerchants(status));
    }

    /**
     * 仓库列表（API-MD-07）。
     *
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseVo>> listWarehouses() {
        return ApiResponse.ok(masterDataAdminService.listWarehouses());
    }

    /**
     * 库区列表（API-MD-07）。
     *
     * @param warehouseId 仓库
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/zones")
    public ApiResponse<List<ZoneVo>> listZones(@RequestParam long warehouseId) {
        return ApiResponse.ok(masterDataAdminService.listZones(warehouseId));
    }

    /**
     * 库位列表（API-MD-07）。
     *
     * @param warehouseId 仓库
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/locations")
    public ApiResponse<List<LocationVo>> listLocations(@RequestParam long warehouseId) {
        return ApiResponse.ok(masterDataAdminService.listLocations(warehouseId));
    }

    /**
     * SKU 列表（API-MD-07）。
     *
     * @param merchantId 商家
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/skus")
    public ApiResponse<List<SkuVo>> listSkus(@RequestParam long merchantId) {
        return ApiResponse.ok(masterDataAdminService.listSkus(merchantId));
    }

    /**
     * 箱规换算列表（API-MD-07）。
     *
     * @param skuId 商品 ID
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.MASTER_QUERY)
    @GetMapping("/skus/{skuId}/unit-conversions")
    public ApiResponse<List<UnitConvVo>> listUnitConversions(@PathVariable long skuId) {
        return ApiResponse.ok(masterDataAdminService.listUnitConversions(skuId));
    }

    /**
     * 商家启停用。
     *
     * @param merchantId 商家 ID
     * @param enabled    是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.MASTER_STATUS_CHANGE)
    @PostMapping("/merchants/{merchantId}/status")
    public ApiResponse<Void> changeMerchantStatus(@PathVariable long merchantId, @RequestParam boolean enabled) {
        masterDataAdminService.changeMerchantStatus(merchantId, enabled);
        return ApiResponse.ok();
    }

    /**
     * 仓库启停用。
     *
     * @param warehouseId 仓库 ID
     * @param enabled     是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.MASTER_STATUS_CHANGE)
    @PostMapping("/warehouses/{warehouseId}/status")
    public ApiResponse<Void> changeWarehouseStatus(@PathVariable long warehouseId, @RequestParam boolean enabled) {
        masterDataAdminService.changeWarehouseStatus(warehouseId, enabled);
        return ApiResponse.ok();
    }

    /**
     * 库位启停用（停用后 PDA 扫码会判定不可用）。
     *
     * @param locationId 库位 ID
     * @param enabled    是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.MASTER_STATUS_CHANGE)
    @PostMapping("/locations/{locationId}/status")
    public ApiResponse<Void> changeLocationStatus(@PathVariable long locationId, @RequestParam boolean enabled) {
        masterDataAdminService.changeLocationStatus(locationId, enabled);
        return ApiResponse.ok();
    }

    /**
     * SKU 启停用（停用后扫码解析不出，等同不可用）。
     *
     * @param skuId   商品 ID
     * @param enabled 是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.MASTER_STATUS_CHANGE)
    @PostMapping("/skus/{skuId}/status")
    public ApiResponse<Void> changeSkuStatus(@PathVariable long skuId, @RequestParam boolean enabled) {
        masterDataAdminService.changeSkuStatus(skuId, enabled);
        return ApiResponse.ok();
    }
}
