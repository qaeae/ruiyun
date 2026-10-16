package com.wms.platform.modules.iv.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.modules.iv.api.ro.AllocateRo;
import com.wms.platform.modules.iv.api.ro.LocatedStockOpRo;
import com.wms.platform.modules.iv.api.ro.MoveRo;
import com.wms.platform.modules.iv.api.ro.StockOpRo;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.au.app.ApprovalService;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.modules.iv.api.vo.InvariantCheckVo;
import com.wms.platform.modules.iv.api.vo.LocationStockVo;
import com.wms.platform.modules.iv.api.vo.MovementVo;
import com.wms.platform.modules.iv.api.vo.StockVo;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.MoveCommand;
import com.wms.platform.modules.iv.app.StockOpCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 库存接口（03 API-IV-01/02/04/05/06）。
 *
 * <p><b>幂等键怎么来</b>：本类所有写接口都在 {@code /api/**} 下，因此已被
 * {@code IdempotencyInterceptor} 要求必须带 {@code Idempotency-Key} 头（03 §1.3）。
 * 这里把**同一个头值按动作加前缀**（如 {@code RESERVE:xxx}）再作为库存流水的
 * {@code idem_key}：
 * <ul>
 *   <li>加前缀是防御性的：若调用方把同一个键同时用在"预占"与"释放"上，
 *       不加前缀会被 {@code uk_movement_idem} 判成重复，**动作会被静默丢弃**——
 *       现场表现为"点了释放但库存没变"；</li>
 *   <li>同一动作重复提交（真正需要防的场景）仍会被正确判为幂等。</li>
 * </ul>
 *
 * <p><b>未暴露 HTTP 的动作</b>：收货（上架/退货）、出库扣减、派发分配、拣货属于
 * 入库/出库流程与 PDA 补传的**内部调用**（03 也未为其定义对外接口），
 * 由 {@link InventoryService} 直接提供服务，避免出现"绕过流程直接改库存"的后门接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    /** 库存服务。 */
    private final InventoryService inventoryService;

    /** 双人复核服务（库存调整为复核矩阵项，入口层强制）。 */
    private final ApprovalService approvalService;

    /** JSON（生成审批内容快照）。 */
    private final ObjectMapper objectMapper;

    /**
     * 预占可用库存（03 API-IV-05）。
     *
     * @param idemKeyHeader 幂等键请求头
     * @param ro            入参
     * @return 变更后三态
     */
    @RequiresPermission(PermissionCodes.STOCK_RESERVE)
    @PostMapping("/reservations")
    public ApiResponse<StockVo> reserve(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                        @Valid @RequestBody StockOpRo ro) {
        StockOpCommand command = StockOpCommand.of(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(),
                ro.bizType(), ro.bizId() == null ? 0L : ro.bizId(), ro.bizNo(), "RESERVE:" + idemKeyHeader, null);
        return ApiResponse.ok(StockVo.of(inventoryService.reserve(command)));
    }

    /**
     * 释放预占（03 API-IV-05）。
     *
     * @param idemKeyHeader 幂等键请求头
     * @param ro            入参
     * @return 变更后三态
     */
    @RequiresPermission(PermissionCodes.STOCK_RESERVE)
    @PostMapping("/releases")
    public ApiResponse<StockVo> release(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                        @Valid @RequestBody StockOpRo ro) {
        StockOpCommand command = StockOpCommand.of(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(),
                ro.bizType(), ro.bizId() == null ? 0L : ro.bizId(), ro.bizNo(), "RELEASE:" + idemKeyHeader, null);
        return ApiResponse.ok(StockVo.of(inventoryService.release(command)));
    }

    /**
     * 移库（03 API-IV-04）。
     *
     * @param idemKeyHeader 幂等键请求头
     * @param ro            入参
     * @return 操作结果（无数据体，仅 code=0）
     */
    @RequiresPermission(PermissionCodes.STOCK_MOVE)
    @PostMapping("/moves")
    public ApiResponse<Void> move(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                  @Valid @RequestBody MoveRo ro) {
        inventoryService.move(new MoveCommand(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(),
                ro.fromLocationId(), ro.toLocationId(), ro.bizNo(), "MOVE:" + idemKeyHeader));
        return ApiResponse.ok();
    }

    /**
     * 派发分配（库位 `allocated_qty` 增加）。
     *
     * @param idemKeyHeader 幂等键请求头
     * @param ro            入参
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.STOCK_MOVE)
    @PostMapping("/allocations")
    public ApiResponse<Void> allocate(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                      @Valid @RequestBody AllocateRo ro) {
        inventoryService.allocate(StockOpCommand.of(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(),
                "DO", 0L, ro.bizNo(), "ALLOCATE:" + idemKeyHeader, ro.locationId()));
        return ApiResponse.ok();
    }

    /**
     * 拣货完成（库位 `allocated_qty` 减少）。
     *
     * @param idemKeyHeader 幂等键请求头
     * @param ro            入参
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.STOCK_MOVE)
    @PostMapping("/picks")
    public ApiResponse<Void> pick(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                  @Valid @RequestBody AllocateRo ro) {
        inventoryService.pick(StockOpCommand.of(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(),
                "DO", 0L, ro.bizNo(), "PICK:" + idemKeyHeader, ro.locationId()));
        return ApiResponse.ok();
    }

    /**
     * 查 SKU 三态（03 API-IV-01）。
     *
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @return 三态视图
     */
    @RequiresPermission(PermissionCodes.STOCK_QUERY)
    @GetMapping("/sku")
    public ApiResponse<StockVo> querySku(@RequestParam long warehouseId, @RequestParam long merchantId,
                                         @RequestParam long skuId) {
        return ApiResponse.ok(StockVo.of(inventoryService.querySku(warehouseId, merchantId, skuId)));
    }

    /**
     * 查库位明细（03 API-IV-01 {@code /inventory/locations}）。
     *
     * @param warehouseId 仓库
     * @param skuId       商品
     * @return 库位明细列表
     */
    @RequiresPermission(PermissionCodes.STOCK_QUERY)
    @GetMapping("/locations")
    public ApiResponse<List<LocationStockVo>> listLocations(@RequestParam long warehouseId,
                                                            @RequestParam long skuId) {
        return ApiResponse.ok(inventoryService.listLocations(warehouseId, skuId).stream()
                .map(LocationStockVo::of).toList());
    }

    /**
     * 查库存流水（03 API-IV-02）。
     *
     * @param warehouseId 仓库（可为 0）
     * @param skuId       商品（可为 0）
     * @param bizType     业务类型（可空）
     * @param bizNo       业务单号（可空）
     * @param limit       最大条数
     * @return 流水列表
     */
    @RequiresPermission(PermissionCodes.STOCK_QUERY)
    @GetMapping("/movements")
    public ApiResponse<List<MovementVo>> listMovements(@RequestParam(defaultValue = "0") long warehouseId,
                                                       @RequestParam(defaultValue = "0") long skuId,
                                                       @RequestParam(required = false) String bizType,
                                                       @RequestParam(required = false) String bizNo,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(inventoryService.listMovements(warehouseId, skuId, bizType, bizNo, limit).stream()
                .map(MovementVo::of).toList());
    }

    /**
     * 不变量校验（03 API-IV-06）。
     *
     * @return 逐条校验结果
     */
    @RequiresPermission(PermissionCodes.STOCK_INVARIANT_CHECK)
    @PostMapping("/invariant-check")
    public ApiResponse<InvariantCheckVo> checkInvariants() {
        return ApiResponse.ok(InvariantCheckVo.of(inventoryService.checkInvariants().stream()
                .map(InvariantCheckVo.Item::of).toList()));
    }

    /**
     * 收货入库 / 出库扣减 / 盘点调整（内部动作，仅供同进程内的入库/出库流程与 PDA 补传调用）。
     *
     * @param ro 入参
     * @return 变更后三态
     */
    @RequiresPermission(PermissionCodes.STOCK_MOVE)
    @PostMapping("/internal/receipts")
    public ApiResponse<StockVo> receive(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                        @Valid @RequestBody LocatedStockOpRo ro) {
        return ApiResponse.ok(StockVo.of(inventoryService.receive(toCommand(ro, "RECEIVE", idemKeyHeader))));
    }

    /**
     * 出库扣减（内部动作）。
     *
     * @param ro 入参（必须带库位）
     * @param idemKeyHeader 幂等键请求头
     * @return 变更后三态
     */
    @RequiresPermission(PermissionCodes.STOCK_MOVE)
    @PostMapping("/internal/shipments")
    public ApiResponse<StockVo> ship(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                     @Valid @RequestBody LocatedStockOpRo ro) {
        return ApiResponse.ok(StockVo.of(inventoryService.ship(toCommand(ro, "SHIP", idemKeyHeader))));
    }

    /**
     * 盘点调整（内部动作；{@code qty} 为带符号差异）。
     *
     * @param ro 入参
     * @param idemKeyHeader 幂等键请求头
     * @return 变更后三态
     */
    @RequiresPermission(PermissionCodes.STOCK_ADJUST)
    @PostMapping("/internal/adjustments")
    public ApiResponse<Map<String, Object>> adjust(@RequestHeader(HeaderConst.IDEMPOTENCY_KEY) String idemKeyHeader,
                                       @Valid @RequestBody LocatedStockOpRo ro) {
        // ★ 入口层强制双人复核（02 M2-05 复核矩阵项「库存调整」）：
        //   不再直接执行，而是登记复核单；复核通过后由 StockAdjustApprovalHandler 按**内容快照**执行。
        //   内容快照在这里生成——它就是"复核人将要批准的那一份内容"，执行时不再读实时参数。
        String payload = adjustPayload(ro, idemKeyHeader);
        String approvalNo = approvalService.submit(ApprovalBizType.STOCK_ADJUST, null,
                "ADJUST-" + ro.skuId() + "-" + ro.locationId(), ro.warehouseId(), ro.merchantId(),
                ro.qty() == null ? null : ro.qty().abs(), payload,
                "库存调整 " + ro.qty() + "（skuId=" + ro.skuId() + " locationId=" + ro.locationId() + "）");
        return ApiResponse.ok(Map.of("approvalNo", approvalNo,
                "message", "已提交双人复核，复核通过后执行调整"));
    }

    /**
     * 生成审批内容快照（库存调整的执行参数，执行时以它为准）。
     *
     * @param ro      入参
     * @param idemKey 幂等键（一并进快照：复核后执行时复用同一个业务幂等语义）
     * @return JSON 快照
     */
    private String adjustPayload(LocatedStockOpRo ro, String idemKey) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("warehouseId", ro.warehouseId());
        snapshot.put("merchantId", ro.merchantId());
        snapshot.put("skuId", ro.skuId());
        snapshot.put("locationId", ro.locationId());
        snapshot.put("qty", ro.qty());
        snapshot.put("bizType", ro.bizType());
        snapshot.put("idemKeyHeader", idemKey);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "审批内容快照序列化失败", ex);
        }
    }

    /**
     * 入参 → 命令。
     *
     * @param ro   入参
     * @param op   动作前缀（幂等键用）
     * @param idem 幂等键头值
     * @return 命令
     */
    private StockOpCommand toCommand(LocatedStockOpRo ro, String op, String idem) {
        return StockOpCommand.of(ro.warehouseId(), ro.merchantId(), ro.skuId(), ro.qty(), ro.bizType(),
                ro.bizId() == null ? 0L : ro.bizId(), ro.bizNo(), op + ":" + idem, ro.locationId());
    }
}
