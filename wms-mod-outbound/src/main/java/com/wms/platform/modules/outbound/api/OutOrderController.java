package com.wms.platform.modules.outbound.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.modules.outbound.api.ro.CancelOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.CreateOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.DispatchOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HandoverOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HoldOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.LoadOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.MeasureOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PackOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PageOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PartialShipOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PickCompleteOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.ShortageOutOrderRo;
import com.wms.platform.modules.outbound.api.vo.OutOrderDetailVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderPageVo;
import com.wms.platform.modules.outbound.api.vo.OutStatusLogVo;
import com.wms.platform.modules.outbound.api.vo.PartialShipVo;
import com.wms.platform.modules.outbound.app.OutboundOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 出库接口（{@code 03} API-OUT-01 ~ API-OUT-13）。
 *
 * <p><b>权限按"作业动作"拆开</b>（不是按角色）：分拣能拣货、能称重、能打包，但**不能装车出账**；
 * 装卸工只能装车；安全员只能拦截。这样"复核 → 交接 → 装车"三道卡点才真的卡得住——
 * 若按角色粗放授权，同一个人可以一路点到出库，卡点形同虚设。
 *
 * <p><b>幂等键</b>：所有写接口都在 {@code /api/**} 下，{@code IdempotencyInterceptor} 强制要求
 * {@code Idempotency-Key}；服务层会把该头值与动作名拼成幂等键（如 {@code LOAD:<头值>}）落
 * {@code pf_idempotent}——加动作前缀是防御性的：同一个头值若被跨动作复用（先取消后装车），
 * 不加前缀会让第二次动作被判成"重复请求"而**静默不执行**。
 *
 * <p>查询接口（{@code GET}）不需要幂等键（天然幂等）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/outbound/orders")
@RequiredArgsConstructor
public class OutOrderController {

    /** 出库应用服务。 */
    private final OutboundOrderService outboundOrderService;

    /**
     * 创建出库单（API-OUT-01）。
     *
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_CREATE)
    @PostMapping
    public ApiResponse<OutOrderDetailVo> create(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                @Valid @RequestBody CreateOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.create(ro, idemKeyHeader));
    }

    /**
     * 派发拣货任务（API-OUT-02）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_DISPATCH)
    @PostMapping("/{doNo}/dispatch")
    public ApiResponse<OutOrderDetailVo> dispatch(@PathVariable String doNo,
                                                  @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                  @Valid @RequestBody DispatchOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.dispatch(doNo, ro, idemKeyHeader));
    }

    /**
     * 拣货完成上报（API-OUT-03）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_PICK)
    @PostMapping("/{doNo}/pick-complete")
    public ApiResponse<OutOrderDetailVo> pickComplete(
            @PathVariable String doNo,
            @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
            @Valid @RequestBody PickCompleteOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.pickComplete(doNo, ro, idemKeyHeader));
    }

    /**
     * 缺货上报（API-OUT-04）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_PICK)
    @PostMapping("/{doNo}/shortage")
    public ApiResponse<OutOrderDetailVo> shortage(@PathVariable String doNo,
                                                  @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                  @Valid @RequestBody ShortageOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.shortage(doNo, ro, idemKeyHeader));
    }

    /**
     * 缺货恢复拣货（API-OUT-05）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_PICK)
    @PostMapping("/{doNo}/resume-picking")
    public ApiResponse<OutOrderDetailVo> resumePicking(
            @PathVariable String doNo,
            @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader) {
        return ApiResponse.ok(outboundOrderService.resumePicking(doNo, idemKeyHeader));
    }

    /**
     * 称重/量方录入（API-OUT-06）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_MEASURE)
    @PostMapping("/{doNo}/measure")
    public ApiResponse<OutOrderDetailVo> measure(@PathVariable String doNo,
                                                 @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                 @Valid @RequestBody MeasureOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.measure(doNo, ro, idemKeyHeader));
    }

    /**
     * 打包 + 面单绑定（API-OUT-07）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_PACK)
    @PostMapping("/{doNo}/pack")
    public ApiResponse<OutOrderDetailVo> pack(@PathVariable String doNo,
                                              @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                              @Valid @RequestBody PackOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.pack(doNo, ro, idemKeyHeader));
    }

    /**
     * 扫码交接给装卸工（API-OUT-08）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_HANDOVER)
    @PostMapping("/{doNo}/handover")
    public ApiResponse<OutOrderDetailVo> handover(@PathVariable String doNo,
                                                  @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                  @Valid @RequestBody HandoverOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.handover(doNo, ro, idemKeyHeader));
    }

    /**
     * 装车确认（API-OUT-09，库存出账）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_LOAD)
    @PostMapping("/{doNo}/load")
    public ApiResponse<OutOrderDetailVo> load(@PathVariable String doNo,
                                              @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                              @Valid @RequestBody LoadOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.load(doNo, ro, idemKeyHeader));
    }

    /**
     * 取消出库单（API-OUT-10）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_CANCEL)
    @PostMapping("/{doNo}/cancel")
    public ApiResponse<OutOrderDetailVo> cancel(@PathVariable String doNo,
                                                @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                                @Valid @RequestBody CancelOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.cancel(doNo, ro, idemKeyHeader));
    }

    /**
     * 异常拦截（API-OUT-11）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_HOLD)
    @PostMapping("/{doNo}/hold")
    public ApiResponse<OutOrderDetailVo> hold(@PathVariable String doNo,
                                              @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                              @Valid @RequestBody HoldOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.hold(doNo, ro, idemKeyHeader));
    }

    /**
     * 部分发货（API-OUT-12）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键
     * @param ro            入参
     * @return 拆单结果
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_PARTIAL)
    @PostMapping("/{doNo}/partial-ship")
    public ApiResponse<PartialShipVo> partialShip(
            @PathVariable String doNo,
            @RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
            @Valid @RequestBody PartialShipOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.partialShip(doNo, ro, idemKeyHeader));
    }

    /**
     * 出库单分页查询（API-OUT-13）。
     *
     * @param ro 查询条件（分页 + 可选过滤）
     * @return 分页结果
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_QUERY)
    @GetMapping
    public ApiResponse<OutOrderPageVo> page(@Valid PageOutOrderRo ro) {
        return ApiResponse.ok(outboundOrderService.page(ro));
    }

    /**
     * 出库单详情（API-OUT-13）。
     *
     * @param doNo 出库单号
     * @return 出库单详情
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_QUERY)
    @GetMapping("/{doNo}")
    public ApiResponse<OutOrderDetailVo> detail(@PathVariable String doNo) {
        return ApiResponse.ok(outboundOrderService.detail(doNo));
    }

    /**
     * 状态轨迹（API-OUT-13）。
     *
     * @param doNo 出库单号
     * @return 轨迹列表
     */
    @RequiresPermission(PermissionCodes.OUTBOUND_ORDER_QUERY)
    @GetMapping("/{doNo}/status-logs")
    public ApiResponse<List<OutStatusLogVo>> statusLogs(@PathVariable String doNo) {
        return ApiResponse.ok(outboundOrderService.statusLogs(doNo));
    }
}
