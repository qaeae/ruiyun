package com.wms.platform.modules.iv.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.iv.api.ro.StocktakeCreateRo;
import com.wms.platform.modules.iv.api.ro.StocktakeRecordRo;
import com.wms.platform.modules.iv.api.ro.StocktakeRejectRo;
import com.wms.platform.modules.iv.api.vo.StocktakeVo;
import com.wms.platform.modules.iv.app.StocktakeService;
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

import java.util.List;
import java.util.Map;

/**
 * 盘点接口（03 API-IV-03）。
 *
 * <p><b>权限口径</b>：整个控制器要求 {@link PermissionCodes#STOCK_ADJUST}——盘点审批会直接改库存
 * （生成 `ADJUST` 流水），把它与"看库存"（`stock:query`）分成两个权限是刻意的：
 * 能看库存的人不应该自动获得"改写库存"的能力。
 *
 * <p><b>双人复核在服务端强校验</b>（创建人 ≠ 审批人 → `80002`）：审批人取自**令牌身份**
 * （{@link TenantContext#userId()}），不接受请求体传入——否则任何人都能填别人的 ID 冒充审批人。
 */
@Validated
@RestController
@RequestMapping("/api/v1/stocktakes")
@RequiredArgsConstructor
public class StocktakeController {

    /** 盘点服务。 */
    private final StocktakeService stocktakeService;

    /**
     * 创建盘点单（建单即固定账面快照）。
     *
     * @param ro 入参
     * @return 盘点单号与明细条数
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody StocktakeCreateRo ro) {
        String stocktakeNo = stocktakeService.create(ro);
        return ApiResponse.ok(Map.of("stocktakeNo", stocktakeNo));
    }

    /**
     * 录入实盘数。
     *
     * @param stocktakeNo 盘点单号
     * @param ro          入参
     * @return 本次录入条数
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping("/{stocktakeNo}/items")
    public ApiResponse<Map<String, Object>> record(@PathVariable String stocktakeNo,
                                                   @Valid @RequestBody StocktakeRecordRo ro) {
        int recorded = stocktakeService.record(stocktakeNo, ro);
        return ApiResponse.ok(Map.of("recorded", recorded));
    }

    /**
     * 提交审批。
     *
     * @param stocktakeNo 盘点单号
     * @return 有差异条数（审批人据此判断风险）
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping("/{stocktakeNo}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable String stocktakeNo) {
        int diffCount = stocktakeService.submit(stocktakeNo);
        return ApiResponse.ok(Map.of("diffCount", diffCount));
    }

    /**
     * 审批通过（**审批人取自令牌身份；必须 ≠ 创建人，80002**）。
     *
     * @param stocktakeNo 盘点单号
     * @return 实际生成的调整流水条数
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping("/{stocktakeNo}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String stocktakeNo) {
        Long approverId = TenantContext.userId();
        int adjusted = stocktakeService.approve(stocktakeNo, approverId);
        return ApiResponse.ok(Map.of("adjusted", adjusted));
    }

    /**
     * 驳回（需填原因）。
     *
     * @param stocktakeNo 盘点单号
     * @param ro          入参
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping("/{stocktakeNo}/reject")
    public ApiResponse<Void> reject(@PathVariable String stocktakeNo,
                                    @Valid @RequestBody StocktakeRejectRo ro) {
        stocktakeService.reject(stocktakeNo, ro.reason(), TenantContext.userId());
        return ApiResponse.ok();
    }

    /**
     * 盘点单列表。
     *
     * @param warehouseId 仓库（可空）
     * @param status      状态（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.STOCK_QUERY)
    @GetMapping
    public ApiResponse<List<StocktakeVo>> list(@RequestParam(required = false) Long warehouseId,
                                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(stocktakeService.list(warehouseId, status));
    }

    /**
     * 盘点单详情（含明细与差异）。
     *
     * @param stocktakeNo 盘点单号
     * @return 详情
     */
    @RequiresPermission(PermissionCodes.STOCK_QUERY)
    @GetMapping("/{stocktakeNo}")
    public ApiResponse<StocktakeVo> detail(@PathVariable String stocktakeNo) {
        return ApiResponse.ok(stocktakeService.detail(stocktakeNo));
    }
}
