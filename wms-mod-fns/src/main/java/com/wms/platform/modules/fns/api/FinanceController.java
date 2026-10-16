package com.wms.platform.modules.fns.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.fns.api.ro.ReconcileCreateRo;
import com.wms.platform.modules.fns.api.ro.ReconcileHandleRo;
import com.wms.platform.modules.fns.api.vo.ReconcileVo;
import com.wms.platform.modules.fns.app.ReconcileService;
import com.wms.platform.modules.fns.domain.Liability;
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
 * 财务对账接口（03 API-FNS-01/02）。
 *
 * <p><b>权限口径</b>：财务相关操作一律要求 {@code finance:*} 权限，与库存/主数据权限**分开**。
 * 理由：对账确认会直接触发后续付款，且差异判定决定"谁承担这笔钱"（平台/商家/物流），
 * 这是典型的**不相容职责**——不应由管库存的人顺手承担。
 */
@Validated
@RestController
@RequestMapping("/api/v1/finance/reconcile-bills")
@RequiredArgsConstructor
public class FinanceController {

    /** 对账服务。 */
    private final ReconcileService reconcileService;

    /**
     * 生成（或返回已有）对账单。
     *
     * @param ro 入参（承运商侧明细）
     * @return 对账单号
     */
    @RequiresPermission(PermissionCodes.FINANCE_RECONCILE_MANAGE)
    @PostMapping
    public ApiResponse<Map<String, Object>> createBill(@Valid @RequestBody ReconcileCreateRo ro) {
        return ApiResponse.ok(Map.of("rclNo", reconcileService.createBill(ro)));
    }

    /**
     * 对账单列表。
     *
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping
    public ApiResponse<List<ReconcileVo>> list(@RequestParam(required = false) String carrierCode,
                                              @RequestParam(required = false) String period) {
        return ApiResponse.ok(reconcileService.list(carrierCode, period));
    }

    /**
     * 对账单详情（含逐单差异）。
     *
     * @param rclNo 对账单号
     * @return 详情
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/{rclNo}")
    public ApiResponse<ReconcileVo> detail(@PathVariable String rclNo) {
        return ApiResponse.ok(reconcileService.detail(rclNo));
    }

    /**
     * 人工处理差异条目（判定责任方）。
     *
     * @param rclNo  对账单号
     * @param itemId 明细 ID
     * @param ro     入参
     * @return 处理后剩余未处理条数
     */
    @RequiresPermission(PermissionCodes.FINANCE_RECONCILE_MANAGE)
    @PostMapping("/{rclNo}/items/{itemId}/handle")
    public ApiResponse<Map<String, Object>> handleItem(@PathVariable String rclNo, @PathVariable long itemId,
                                                       @Valid @RequestBody ReconcileHandleRo ro) {
        long open = reconcileService.handleItem(rclNo, itemId, Liability.of(ro.liability()), ro.remark());
        return ApiResponse.ok(Map.of("openCount", open));
    }

    /**
     * 确认对账单（要求差异已全部处理完）。
     *
     * @param rclNo 对账单号
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_RECONCILE_MANAGE)
    @PostMapping("/{rclNo}/confirm")
    public ApiResponse<Void> confirm(@PathVariable String rclNo) {
        reconcileService.confirm(rclNo, TenantContext.userId());
        return ApiResponse.ok();
    }
}
