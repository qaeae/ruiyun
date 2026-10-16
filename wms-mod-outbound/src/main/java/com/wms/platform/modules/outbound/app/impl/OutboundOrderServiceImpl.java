package com.wms.platform.modules.outbound.app.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.common.idempotent.RequestHasher;
import com.wms.platform.common.mapstruct.MapStructExt;
import com.wms.platform.common.security.PiiCipher;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.md.app.MasterDataQueryService;
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
import com.wms.platform.modules.outbound.api.vo.OutOrderBriefVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderDetailVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderItemVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderPageVo;
import com.wms.platform.modules.outbound.api.vo.OutStatusLogVo;
import com.wms.platform.modules.outbound.api.vo.PartialShipVo;
import com.wms.platform.modules.outbound.app.OutboundOrderService;
import com.wms.platform.modules.outbound.app.remote.FeeFreezePort;
import com.wms.platform.modules.outbound.app.remote.ShipmentPort;
import com.wms.platform.modules.outbound.domain.CancelRollbackRule;
import com.wms.platform.modules.outbound.domain.CutoffRule;
import com.wms.platform.modules.outbound.domain.OutOrderEvent;
import com.wms.platform.modules.outbound.domain.OutOrderNoRule;
import com.wms.platform.modules.outbound.domain.OutOrderStatus;
import com.wms.platform.modules.outbound.domain.WeightCheck;
import com.wms.platform.modules.outbound.domain.statemachine.OutOrderStateMachine;
import com.wms.platform.modules.outbound.infrastructure.mapper.BizStatusLogMapper;
import com.wms.platform.modules.outbound.infrastructure.mapper.OutOrderItemMapper;
import com.wms.platform.modules.outbound.infrastructure.mapper.OutOrderMapper;
import com.wms.platform.modules.outbound.infrastructure.mapstruct.OutOrderMS;
import com.wms.platform.modules.outbound.infrastructure.po.BizStatusLogPo;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderItemPo;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 出库单应用服务实现（{@code 03} API-OUT-01 ~ API-OUT-13，{@code 01} §2.1/§2.6）。
 *
 * <p><b>每个写方法的固定骨架</b>（顺序不可调换，每一步都对应一类真实事故）：
 * <ol>
 *   <li>幂等裁决（{@code pf_idempotent}）：同键同体重放直接返回首次结果，同键异体 409；</li>
 *   <li>查单 + 状态机判定（{@code OutOrderStateMachine.next}）：非法流转即 409，**先判后做**；</li>
 *   <li>库存/资金动作（跨域只走 app 接口与端口）；</li>
 *   <li>状态迁移用**条件更新**（{@code WHERE status = 迁移前}）：并发下只有一个成功；</li>
 *   <li>写状态流水（{@code uk_status_idem} 兜底），失败则整个事务回滚。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class OutboundOrderServiceImpl implements OutboundOrderService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(OutboundOrderServiceImpl.class);

    /** 幂等业务类型（写入 {@code pf_idempotent.biz_type}）。 */
    private static final String IDEM_BIZ_TYPE = "OUTBOUND_ORDER";

    /** 库存动作的业务类型（写入 {@code iv_movement.biz_type}）。 */
    private static final String STOCK_BIZ_TYPE = "DO";

    /** 出库单。 */
    private final OutOrderMapper outOrderMapper;

    /** 出库单明细。 */
    private final OutOrderItemMapper outOrderItemMapper;

    /** 状态流水。 */
    private final BizStatusLogMapper bizStatusLogMapper;

    /** 库存域服务（预占/释放/分配/拣货/出库扣减）。 */
    private final InventoryService inventoryService;

    /** 主数据查询（SKU 解析、禁入品类、库位可拣校验）。 */
    private final MasterDataQueryService masterDataQueryService;

    /** 资金域费用冻结（端口）。 */
    private final FeeFreezePort feeFreezePort;

    /** 物流域装运单（端口）。 */
    private final ShipmentPort shipmentPort;

    /** PII 加解密与掩码。 */
    private final PiiCipher piiCipher;

    /** 幂等裁决。 */
    private final IdempotentService idempotentService;

    /** 出库配置（截单时间、冻结缓冲、强制交接）。 */
    private final WmsProperties wmsProperties;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** JSON（幂等缓存）。 */
    private final ObjectMapper objectMapper;

    // ==========================================================================================
    // 写动作
    // ==========================================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo create(CreateOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "CREATE", ro, OutOrderDetailVo.class, () -> doCreate(ro));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo dispatch(String doNo, DispatchOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "DISPATCH:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStatus from = OutOrderStatus.of(po.getStatus());
            OutOrderStateMachine.next(from, OutOrderEvent.DISPATCH);
            // 1. 逐行解析并校验拣货库位（不可拣/不存在 → 50003）
            Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
            for (DispatchOutOrderRo.ItemRo item : ro.items()) {
                OutOrderItemPo detail = requireItem(items, item.lineNo(), doNo);
                masterDataQueryService.assertLocationPickable(po.getWarehouseId(), item.locationCode());
                Long locationId = masterDataQueryService.findLocationId(po.getWarehouseId(), item.locationCode());
                if (locationId == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "库位不存在或已停用：" + item.locationCode());
                }
                // 2. 回填库位（出库扣减必须带库位，否则 SKU 账与库位账不平）
                outOrderItemMapper.fillLocation(tenant(), detail.getId(), locationId, operator());
                // 3. 库位分配：allocated += 应发数量
                inventoryService.allocate(stockCommand(po, detail.getSkuId(), detail.getQty(), locationId,
                        "ALLOCATE:DO:" + doNo + ":" + detail.getLineNo()));
            }
            // 4. 状态迁移 + 流水
            transition(po, OutOrderEvent.DISPATCH, "按拣货库位派发拣货任务");
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo pickComplete(String doNo, PickCompleteOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "PICK:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.PICK_COMPLETE);
            Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
            for (PickCompleteOutOrderRo.ItemRo item : ro.items()) {
                OutOrderItemPo detail = requireItem(items, item.lineNo(), doNo);
                // 1. 实拣必须等于应发：差了就要走缺货上报，否则库存与订单会永久对不上（01 §2.1 第 3 行前置）
                if (detail.getQty().compareTo(item.pickedQty()) != 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "第 " + item.lineNo() + " 行实拣 " + item.pickedQty() + " 与应发 " + detail.getQty()
                                    + " 不一致：请使用缺货上报（API-OUT-04）或补足后再报完成");
                }
                if (detail.getLocationId() == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "第 " + item.lineNo() + " 行未派发拣货库位，不能报拣货完成");
                }
                // 2. 回填实拣数量
                outOrderItemMapper.fillPickedQty(tenant(), detail.getId(), item.pickedQty(), operator());
                // 3. 库位可拣量减少（SKU 三态不变，01 §2.7）
                inventoryService.pick(stockCommand(po, detail.getSkuId(), item.pickedQty(), detail.getLocationId(),
                        "PICK:DO:" + doNo + ":" + detail.getLineNo()));
            }
            transition(po, OutOrderEvent.PICK_COMPLETE, "实拣 = 应发，拣货完成");
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo shortage(String doNo, ShortageOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "SHORTAGE:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.SHORTAGE);
            Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
            for (ShortageOutOrderRo.ItemRo item : ro.items()) {
                OutOrderItemPo detail = requireItem(items, item.lineNo(), doNo);
                // 1. 缺货量不得超过尚未释放的预占（否则会把别的单据的库存放出去）
                BigDecimal remaining = inventoryService.reservedRemaining(doNo, detail.getSkuId());
                if (item.shortageQty().compareTo(remaining) > 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "第 " + item.lineNo() + " 行缺货量 " + item.shortageQty() + " 超过剩余预占 " + remaining);
                }
                // 2. 差额释放（reserved −= 缺货量）
                inventoryService.release(stockCommand(po, detail.getSkuId(), item.shortageQty(), null,
                        "SHORTAGE:DO:" + doNo + ":" + detail.getLineNo()));
            }
            transition(po, OutOrderEvent.SHORTAGE,
                    ro.reason() == null || ro.reason().isBlank() ? "缺货上报：差额已释放" : ro.reason());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo resumePicking(String doNo, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "RESUME:" + doNo, doNo, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.RESUME);
            // 1. 重新占用：把"缺货时释放掉的预占"再占回来（补货到位后的恢复动作）
            Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
            for (OutOrderItemPo detail : items.values()) {
                BigDecimal reserved = inventoryService.reservedRemaining(doNo, detail.getSkuId());
                BigDecimal missing = detail.getQty().subtract(reserved);
                if (missing.signum() > 0) {
                    inventoryService.reserve(stockCommand(po, detail.getSkuId(), missing, null,
                            "RESUME:DO:" + doNo + ":" + detail.getLineNo()));
                }
            }
            transition(po, OutOrderEvent.RESUME, "补货到位，恢复拣货");
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo measure(String doNo, MeasureOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "MEASURE:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.MEASURE);
            // 1. 偏差校验（> 20% 拦停；应收重量缺失则跳过，见 WeightCheck 口径）
            WeightCheck.Result check = WeightCheck.check(po.getExpectedWeight(), ro.actualWeight());
            if (!check.pass()) {
                throw new BizException(ErrorCode.WEIGHT_DEVIATION_EXCEEDED, check.message());
            }
            // 2. 回填实称/实量/件数
            outOrderMapper.updateMeasure(tenant(), po.getId(), ro.actualWeight(), ro.actualVolume(),
                    ro.packageCount(), true, operator());
            transition(po, OutOrderEvent.MEASURE, "称重量方：" + ro.actualWeight() + "kg（" + check.message() + "）");
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo pack(String doNo, PackOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "PACK:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.PACK);
            // 1. 未称重不能打包：没有实称重量，后续运费对账就失去比对基准
            if (!Boolean.TRUE.equals(po.getWeightChecked())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未完成称重/量方，不能打包（03 API-OUT-06）");
            }
            // 2. 面单与单据匹配校验（不一致 → 20004；未绑定的首次绑定在物流域内完成）
            shipmentPort.bindWaybill(doNo, ro.waybillNo(), operator());
            // 3. 件数与打印次数留痕
            outOrderMapper.updateMeasure(tenant(), po.getId(), null, null, ro.packageCount(), false, operator());
            outOrderMapper.increaseLabelPrintCount(tenant(), po.getId(), operator());
            transition(po, OutOrderEvent.PACK, "打包完成，面单已绑定：" + ro.waybillNo());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo handover(String doNo, HandoverOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "HANDOVER:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.HANDOVER);
            // 1. 交接留痕（强制动作：把"货在谁手里"钉住，是后续追责的唯一凭据）
            outOrderMapper.confirmHandover(tenant(), po.getId(), operator());
            transition(po, OutOrderEvent.HANDOVER,
                    ro.remark() == null || ro.remark().isBlank() ? "扫码交接给装卸工" : ro.remark());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo load(String doNo, LoadOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "LOAD:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.LOAD);
            // 1. 前置（01 §2.1 第 9 行）：必须先扫码交接（20005），且已称重
            if (wmsProperties.getOutbound().isRequireHandoverBeforeLoad()
                    && !Boolean.TRUE.equals(po.getHandoverConfirmed())) {
                throw new BizException(ErrorCode.HANDOVER_REQUIRED,
                        "未完成分拣→装卸扫码交接，不可装车（doNo=" + doNo + "）");
            }
            if (!Boolean.TRUE.equals(po.getWeightChecked())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未完成称重/量方，不能装车出库");
            }
            // 2. 库存出账：SKU 实物 −q、预占 −q、库位实物 −q（必须带库位）
            for (OutOrderItemPo detail : itemsByLineNo(po).values()) {
                if (detail.getLocationId() == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "第 " + detail.getLineNo() + " 行缺少拣货库位，不能出库扣减");
                }
                BigDecimal shipped = detail.getPickedQty() == null || detail.getPickedQty().signum() <= 0
                        ? detail.getQty() : detail.getPickedQty();
                inventoryService.ship(stockCommand(po, detail.getSkuId(), shipped, detail.getLocationId(),
                        "SHIP:DO:" + doNo + ":" + detail.getLineNo()));
            }
            // 3. 状态迁移（已出库 = 终态；过了这一步只能走逆向流程）
            transition(po, OutOrderEvent.LOAD,
                    ro.remark() == null || ro.remark().isBlank() ? "装车扫码确认，库存已出账" : ro.remark());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo cancel(String doNo, CancelOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "CANCEL:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStatus from = OutOrderStatus.of(po.getStatus());
            // 1. 回滚计划（终态取消在这里被拒：20002 / 409）
            CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(from);
            Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
            for (OutOrderItemPo detail : items.values()) {
                // 2. 库位分配归位（仅"已派发未拣货"需要；已拣货的分配在 pick 时已释放）
                if (plan.releaseAllocation() && detail.getLocationId() != null) {
                    BigDecimal picked = detail.getPickedQty() == null ? BigDecimal.ZERO : detail.getPickedQty();
                    BigDecimal allocated = detail.getQty().subtract(picked);
                    if (allocated.signum() > 0) {
                        inventoryService.unallocate(stockCommand(po, detail.getSkuId(), allocated,
                                detail.getLocationId(), "UNALLOCATE:DO:" + doNo + ":" + detail.getLineNo()));
                    }
                }
                // 3. 释放剩余预占（用流水算剩余量：缺货上报已释放的部分不能再释放一遍）
                if (plan.releaseReserved()) {
                    BigDecimal remaining = inventoryService.reservedRemaining(doNo, detail.getSkuId());
                    if (remaining.signum() > 0) {
                        inventoryService.release(stockCommand(po, detail.getSkuId(), remaining, null,
                                "CANCEL:DO:" + doNo + ":" + detail.getLineNo()));
                    }
                }
            }
            // 4. 面单作废（未取号也直接作废；已揽收会被物流域拒绝为 30003）
            if (plan.voidLabel()) {
                shipmentPort.cancelVoid(doNo, "出库取消：" + ro.reason(), operator());
            }
            // 5. 操作费：按实际作业量计（合同约定）。费用单属 M4 计费域，本轮只在流水写说明
            if (plan.chargeOperationFee()) {
                log.warn("待装车取消：操作费应按实际作业量计（合同约定），费用单待 M4 计费域落地（doNo={}）", doNo);
            }
            // 6. 释放冻结（全额）
            feeFreezePort.release(po.getMerchantId(), doNo, "出库取消：" + ro.reason(), operator());
            // 7. 状态迁移 + 流水（含回滚计划说明，便于事后解释"这笔取消做了什么"）
            transition(po, OutOrderEvent.CANCEL, plan.remark() + "｜原因：" + ro.reason());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutOrderDetailVo hold(String doNo, HoldOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "HOLD:" + doNo, ro, OutOrderDetailVo.class, () -> {
            OutOrderPo po = requireOrder(doNo);
            OutOrderStatus from = OutOrderStatus.of(po.getStatus());
            OutOrderStateMachine.next(from, OutOrderEvent.HOLD);
            for (OutOrderItemPo detail : itemsByLineNo(po).values()) {
                // 1. 库位分配归位（若已派发未拣货）
                if (from == OutOrderStatus.PICKING && detail.getLocationId() != null) {
                    BigDecimal picked = detail.getPickedQty() == null ? BigDecimal.ZERO : detail.getPickedQty();
                    BigDecimal allocated = detail.getQty().subtract(picked);
                    if (allocated.signum() > 0) {
                        inventoryService.unallocate(stockCommand(po, detail.getSkuId(), allocated,
                                detail.getLocationId(), "HOLD-UNALLOCATE:DO:" + doNo + ":" + detail.getLineNo()));
                    }
                }
                // 2. 回滚预占
                BigDecimal remaining = inventoryService.reservedRemaining(doNo, detail.getSkuId());
                if (remaining.signum() > 0) {
                    inventoryService.release(stockCommand(po, detail.getSkuId(), remaining, null,
                            "HOLD:DO:" + doNo + ":" + detail.getLineNo()));
                }
            }
            // 3. 释放冻结 + 作废面单 + 备注（"开异常单"以状态流水 + 备注承载，见 HoldOutOrderRo 说明）
            feeFreezePort.release(po.getMerchantId(), doNo, "异常拦截：" + ro.reason(), operator());
            shipmentPort.cancelVoid(doNo, "异常拦截：" + ro.reason(), operator());
            outOrderMapper.updateRemark(tenant(), po.getId(), "已拦截：" + ro.reason(), operator());
            transition(po, OutOrderEvent.HOLD, "异常拦截：" + ro.reason());
            return detail(doNo);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PartialShipVo partialShip(String doNo, PartialShipOutOrderRo ro, String idemKeyHeader) {
        return runIdempotent(idemKeyHeader, "PARTIAL:" + doNo, ro, PartialShipVo.class,
                () -> doPartialShip(doNo, ro));
    }

    // ==========================================================================================
    // 查询
    // ==========================================================================================

    @Override
    @Transactional(readOnly = true)
    public OutOrderPageVo page(PageOutOrderRo ro) {
        // 1. 生效分页（pageNum/pageSize 为空时按 03 §1 默认 1/20）
        int pageNum = ro.effectivePageNum();
        int pageSize = ro.effectivePageSize();
        // 2. 数据权限：租户强制注入；仓库范围由上层鉴权保证（越权时 40300）
        long tenantId = tenant();
        String status = ro.status();
        if (status != null && !status.isBlank()) {
            // 状态取值先做一次合法性校验：传了错别字应当报参数错误，而不是"查不到数据"
            OutOrderStatus.of(status);
        }
        List<OutOrderPo> list = outOrderMapper.selectPageList(tenantId, ro.warehouseId(), ro.merchantId(), status,
                ro.doNo(), (pageNum - 1) * pageSize, pageSize);
        long total = outOrderMapper.countPage(tenantId, ro.warehouseId(), ro.merchantId(), status, ro.doNo());
        // 3. Po → Vo（1:1 映射走 MapStruct；列表不含 PII）
        List<OutOrderBriefVo> rows = MapStructExt.toPojoList(list, OutOrderMS.INSTANCE::toVo);
        return new OutOrderPageVo(total, pageNum, pageSize, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public OutOrderDetailVo detail(String doNo) {
        OutOrderPo po = requireOrder(doNo);
        List<OutOrderItemVo> items = MapStructExt.toPojoList(
                outOrderItemMapper.listByDo(tenant(), po.getId()), OutOrderMS.INSTANCE::toVo);
        // 1. 收件人只回掩码（明文与密文都不出接口，B16）
        OutOrderDetailVo.ReceiverVo receiver = new OutOrderDetailVo.ReceiverVo(po.getReceiverNameMasked(),
                po.getReceiverPhoneMasked(), po.getReceiverAddrMasked(), po.getReceiverProvince(),
                po.getReceiverCity(), po.getReceiverDistrict());
        // 2. 组装（多源：出库单 + 明细；不是 Bean 拷贝，故不套映射器）
        return new OutOrderDetailVo(po.getId(), po.getDoNo(), po.getStatus(), po.getWarehouseId(),
                po.getMerchantId(), po.getSourceType(), po.getSourceOrderNo(), po.getCarrierCode(), po.getIsSameDay(),
                po.getExpectedWeight(), po.getExpectedVolume(), po.getActualWeight(), po.getActualVolume(),
                po.getWeightChecked(), po.getPackageCount(), po.getLabelPrintCount(), po.getHandoverConfirmed(),
                receiver, items, po.getCancelStage(), po.getCancelledAt(), po.getFinishedAt(), po.getRemark(),
                po.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutStatusLogVo> statusLogs(String doNo) {
        OutOrderPo po = requireOrder(doNo);
        return MapStructExt.toPojoList(
                bizStatusLogMapper.listByBiz(tenant(), BizStatusLogPo.BIZ_TYPE_DO, po.getId()),
                OutOrderMS.INSTANCE::toVo);
    }

    // ==========================================================================================
    // 内部实现
    // ==========================================================================================

    /**
     * 建单主流程（单事务：明细 + 预占 + 冻结 + 装运单 + 状态流水）。
     *
     * @param ro 入参
     * @return 出库单详情
     */
    private OutOrderDetailVo doCreate(CreateOutOrderRo ro) {
        long tenantId = tenant();
        // 1. 来源类型校验（落库取值只有 API/EXCEL/MANUAL，写错会让后续按来源统计全乱）
        String sourceType = ro.sourceType() == null ? "" : ro.sourceType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of(OutOrderPo.SOURCE_API, OutOrderPo.SOURCE_EXCEL, OutOrderPo.SOURCE_MANUAL).contains(sourceType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法来源类型：" + ro.sourceType());
        }
        if (OutOrderPo.SOURCE_API.equals(sourceType) && (ro.sourceOrderNo() == null || ro.sourceOrderNo().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "来源类型 API 时必须提供上游订单号（sourceOrderNo）");
        }
        // 2. 防重复下单：同一上游单号已建过单 → 409（uk_source_order 是最后防线）
        OutOrderPo exist = outOrderMapper.selectBySourceOrder(tenantId, sourceType, ro.sourceOrderNo());
        if (exist != null) {
            throw BizException.conflict(ErrorCode.DUPLICATE_KEY,
                    "该上游订单号已建单：" + ro.sourceOrderNo() + "（已有单号 " + exist.getDoNo() + "）");
        }
        // 3. 解析 SKU 并校验禁入品类（60002）：商品信息以主数据为准，不信任调用方传来的编码与名称
        List<ItemWithSku> resolved = new ArrayList<>();
        int lineNo = 1;
        for (CreateOutOrderRo.ItemRo item : ro.items()) {
            MasterDataQueryService.SkuBrief sku = masterDataQueryService.findSku(ro.merchantId(), item.skuCode());
            if (sku == null || sku.id() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "商品不存在或已停用：" + item.skuCode());
            }
            if (sku.forbiddenCategory()) {
                throw new BizException(ErrorCode.FORBIDDEN_CATEGORY, "命中禁入品类，不可下单：" + item.skuCode());
            }
            resolved.add(new ItemWithSku(lineNo++, sku, item));
        }
        // 4. 建单：主单 + 明细（状态由状态机给出"待接单"，杜绝绕过状态机得到合法状态）
        long doId = idGenerator.nextId();
        String doNo = OutOrderNoRule.doNo(doId);
        OutOrderPo po = OutOrderPo.newOrder(doId, tenantId, doNo, ro.warehouseId(), ro.merchantId(), sourceType,
                ro.sourceOrderNo(), ro.carrierCode(), isSameDayNow(), ro.expectedWeight(), ro.expectedVolume(),
                ro.remark(), operator());
        OutOrderStateMachine.apply(po, OutOrderEvent.CREATE);
        fillReceiverPii(po, ro);
        outOrderMapper.insert(po);
        for (ItemWithSku one : resolved) {
            outOrderItemMapper.insert(OutOrderItemPo.of(idGenerator.nextId(), tenantId, doId, one.lineNo(),
                    one.sku().id(), one.sku().skuCode(), one.sku().name(), one.ro().qty(),
                    one.ro().unit() == null ? one.sku().unit() : one.ro().unit(), operator()));
        }
        // 5. 预占（available → reserved）：不足则 50001，整个建单事务回滚
        for (ItemWithSku one : resolved) {
            inventoryService.reserve(stockCommand(po, one.sku().id(), one.ro().qty(), null,
                    "RESERVE:DO:" + doNo + ":" + one.lineNo()));
        }
        // 6. 运费冻结（04 §4.3）：不足 → 20001 拦截下单
        FeeFreezePort.FreezeOutcome freeze = feeFreezePort.freeze(new FeeFreezePort.FeeFreezeRequest(
                ro.merchantId(), ro.warehouseId(), doNo, ro.estimatedFreight(),
                wmsProperties.getOutbound().getFreezeBufferRate(), doId, operator()));
        if (!freeze.success()) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE_BLOCK_ORDER, freeze.message());
        }
        // 7. 生成装运单（待取号）：面单绑定与取号的载体
        ShipmentPort.ShipmentRef shipment = shipmentPort.create(new ShipmentPort.CreateShipmentRequest(doId, doNo,
                ro.warehouseId(), ro.merchantId(), ro.carrierCode(), ro.carrierName(), ro.estimatedFreight(),
                operator()));
        // 8. 状态流水（建单无迁移前状态）
        writeStatusLog(po, null, OutOrderStatus.PENDING.code(), OutOrderEvent.CREATE,
                "建单：冻结 " + freeze.freezeNo() + "（" + freeze.frozenAmount() + "），装运单 "
                        + shipment.shpNo() + (isSameDayNow() ? "，当日单" : "，次日单"));
        log.info("出库建单成功 doNo={} 商家={} 明细行={} 冻结={} 装运单={}", doNo, ro.merchantId(),
                resolved.size(), freeze.freezeNo(), shipment.shpNo());
        return detail(doNo);
    }

    /**
     * 部分发货主流程：原单结清（库存出账）+ 余量生成新单（预占迁移）。
     *
     * @param doNo 原出库单号
     * @param ro   入参
     * @return 拆单结果
     */
    private PartialShipVo doPartialShip(String doNo, PartialShipOutOrderRo ro) {
        OutOrderPo po = requireOrder(doNo);
        OutOrderStateMachine.next(OutOrderStatus.of(po.getStatus()), OutOrderEvent.PARTIAL_SHIP);
        Map<Integer, OutOrderItemPo> items = itemsByLineNo(po);
        // 1. 逐行校验：发货量 >0、≤ 应发；并算出余量
        Map<Integer, BigDecimal> shipQtyByLine = new LinkedHashMap<>();
        Map<Integer, BigDecimal> remainderByLine = new LinkedHashMap<>();
        BigDecimal shippedTotal = BigDecimal.ZERO;
        BigDecimal remainderTotal = BigDecimal.ZERO;
        for (PartialShipOutOrderRo.ItemRo item : ro.items()) {
            OutOrderItemPo detail = requireItem(items, item.lineNo(), doNo);
            if (item.shipQty().compareTo(detail.getQty()) > 0) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "第 " + item.lineNo() + " 行发货量 " + item.shipQty() + " 超过应发 " + detail.getQty());
            }
            BigDecimal remainder = detail.getQty().subtract(item.shipQty());
            shipQtyByLine.put(item.lineNo(), item.shipQty());
            if (remainder.signum() > 0) {
                remainderByLine.put(item.lineNo(), remainder);
                remainderTotal = remainderTotal.add(remainder);
            }
            shippedTotal = shippedTotal.add(item.shipQty());
        }
        if (remainderTotal.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "本次发货量等于应发总量，无需部分发货：请直接走装车确认（API-OUT-09）");
        }
        // 2. 原单：库存出账（SKU 实物 −q、预占 −q、库位实物 −q）
        for (Map.Entry<Integer, BigDecimal> entry : shipQtyByLine.entrySet()) {
            OutOrderItemPo detail = items.get(entry.getKey());
            if (detail.getLocationId() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "第 " + detail.getLineNo() + " 行缺少拣货库位，不能出库扣减");
            }
            inventoryService.ship(stockCommand(po, detail.getSkuId(), entry.getValue(), detail.getLocationId(),
                    "PARTIAL-SHIP:DO:" + doNo + ":" + detail.getLineNo()));
        }
        // 3. 余量生成新单（来源标记 MANUAL + 单号加 #P 后缀，避开"防重复下单"唯一键）
        long newDoId = idGenerator.nextId();
        String newDoNo = OutOrderNoRule.doNo(newDoId);
        String newSourceNo = OutOrderNoRule.partialSourceOrderNo(po.getSourceOrderNo(), doNo, 1);
        String remark = "余量单，源自 " + doNo + (ro.reason() == null || ro.reason().isBlank()
                ? "" : "｜拆单原因：" + ro.reason());
        OutOrderPo newPo = OutOrderPo.newOrder(newDoId, po.getTenantId(), newDoNo, po.getWarehouseId(),
                po.getMerchantId(), OutOrderPo.SOURCE_MANUAL, newSourceNo, po.getCarrierCode(),
                Boolean.TRUE.equals(po.getIsSameDay()), po.getExpectedWeight(), po.getExpectedVolume(), remark,
                operator());
        OutOrderStateMachine.apply(newPo, OutOrderEvent.CREATE);
        copyReceiverPii(po, newPo);
        outOrderMapper.insert(newPo);
        for (Map.Entry<Integer, BigDecimal> entry : remainderByLine.entrySet()) {
            OutOrderItemPo detail = items.get(entry.getKey());
            outOrderItemMapper.insert(OutOrderItemPo.of(idGenerator.nextId(), po.getTenantId(), newDoId,
                    detail.getLineNo(), detail.getSkuId(), detail.getSkuCode(), detail.getSkuName(),
                    entry.getValue(), detail.getUnit(), operator()));
            // 4. 预占迁移：先释放原单该 SKU 的余量预占，再在新单重新预占
            //    （不迁移会让余量单取消时"释放不到预占"，库存被永久占住）
            inventoryService.release(stockCommand(po, detail.getSkuId(), entry.getValue(), null,
                    "PARTIAL-MOVE-OUT:DO:" + doNo + ":" + detail.getLineNo()));
            inventoryService.reserve(stockCommand(newPo, detail.getSkuId(), entry.getValue(), null,
                    "PARTIAL-MOVE-IN:DO:" + newDoNo + ":" + detail.getLineNo()));
        }
        writeStatusLog(newPo, null, OutOrderStatus.PENDING.code(), OutOrderEvent.CREATE,
                "余量建单：源自 " + doNo + "（余量 " + remainderTotal + "）");
        // 5. 原单结清：转「已出库」并写明余量去向
        outOrderMapper.updateRemark(tenant(), po.getId(),
                "部分发货：本次出库 " + shippedTotal + "，余量 " + remainderTotal + " 转新单 " + newDoNo
                        + (ro.reason() == null || ro.reason().isBlank() ? "" : "｜原因：" + ro.reason()),
                operator());
        transition(po, OutOrderEvent.PARTIAL_SHIP,
                "部分发货：余量 " + remainderTotal + " 转新单 " + newDoNo);
        log.info("部分发货完成 doNo={} 已出库={} 余量={} 新单={}", doNo, shippedTotal, remainderTotal, newDoNo);
        return new PartialShipVo(doNo, shippedTotal, newDoNo, newDoId, remainderTotal,
                "原单已出库 " + shippedTotal + " 件，余量 " + remainderTotal + " 件已生成新单 " + newDoNo);
    }

    /**
     * 幂等执行外壳：裁决 → 执行 → 缓存响应。
     *
     * @param idemKeyHeader 请求头幂等键
     * @param action        动作名（与幂等键拼成 pf_idempotent 的键，防止同一个头值跨动作串用）
     * @param request       请求体（用于规范化哈希）
     * @param voType        响应类型
     * @param supplier      业务动作
     * @param <T>           响应类型
     * @return 业务结果
     */
    private <T> T runIdempotent(String idemKeyHeader, String action, Object request, Class<T> voType,
                                Supplier<T> supplier) {
        String idemKey = action + ":" + idemKeyHeader;
        IdempotentService.Decision decision = idempotentService.begin(idemKey, IDEM_BIZ_TYPE,
                RequestHasher.hash(request));
        // 1. 重放：返回首次响应（不再执行任何业务动作）
        if (decision == IdempotentService.Decision.REPLAY) {
            return idempotentService.cachedResponse(idemKey)
                    .map(json -> readCached(json, voType))
                    .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "幂等缓存缺失：" + idemKey));
        }
        // 2. 同键异体：409（必须让调用方显式处理，不能"以第一次为准"）
        if (decision == IdempotentService.Decision.MISMATCH) {
            throw BizException.idempotentMismatch("同一幂等键提交了不同内容：" + idemKey);
        }
        // 3. 并发重入：429 让调用方稍后重试
        if (decision == IdempotentService.Decision.IN_PROGRESS) {
            throw BizException.conflict(ErrorCode.RATE_LIMITED, "该操作正在处理中，请稍后重试：" + idemKey);
        }
        // 4. 首次执行
        try {
            T vo = supplier.get();
            idempotentService.complete(idemKey, writeJson(vo));
            return vo;
        } catch (RuntimeException ex) {
            // 5. 失败也要留痕（便于排障），但不缓存响应体（下次可重试）
            idempotentService.fail(idemKey);
            throw ex;
        }
    }

    /**
     * 状态迁移（条件更新 + 状态流水）。
     *
     * @param po      出库单（方法内会刷新其状态用于后续 detail 查询）
     * @param event   事件
     * @param remark  流水备注
     */
    private void transition(OutOrderPo po, OutOrderEvent event, String remark) {
        OutOrderStatus from = OutOrderStatus.of(po.getStatus());
        OutOrderStatus to = OutOrderStateMachine.next(from, event);
        LocalDateTime now = LocalDateTime.now();
        int affected = outOrderMapper.transition(tenant(), po.getId(), from.code(), to.code(),
                to == OutOrderStatus.CANCELLED ? from.code() : null, now, operator());
        if (affected == 0) {
            // 并发下状态已被改动：宁可让调用方刷新重试，也不能覆盖别人的动作（丢失更新）
            throw BizException.illegalTransition("出库单状态已被并发修改（当前 " + from.code() + " → "
                    + to.code() + "），请刷新后重试：" + po.getDoNo());
        }
        // 说明：这里**刻意不回写内存对象的 status**——状态只能由 domain.statemachine 包修改
        // （ArchUnit 规则拦截，本次实现就曾被它抓出一次），而调用方后续都是重新查库
        // （detail() 走 requireOrder），因此不需要也不应该在这里"顺手改一下"。
        writeStatusLog(po, from.code(), to.code(), event, remark);
    }

    /**
     * 写状态流水（{@code uk_status_idem} 是状态迁移幂等的最后防线）。
     *
     * @param po        出库单
     * @param from      迁移前状态（建单为 {@code null}）
     * @param to        迁移后状态
     * @param event     事件
     * @param remark    备注
     */
    private void writeStatusLog(OutOrderPo po, String from, String to, OutOrderEvent event, String remark) {
        long id = idGenerator.nextId();
        bizStatusLogMapper.insert(BizStatusLogPo.of(id, tenant(), po.getId(), po.getDoNo(), from, to,
                event.name(), operator(), TenantContext.deviceCode(),
                po.getId() + "+" + event.name(), remark, traceId()));
    }

    /**
     * 查单（不存在即 10001；状态非法说明脏数据）。
     *
     * @param doNo 出库单号
     * @return 出库单
     */
    private OutOrderPo requireOrder(String doNo) {
        OutOrderPo po = outOrderMapper.selectByNo(tenant(), doNo);
        if (po == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "出库单不存在：" + doNo);
        }
        return po;
    }

    /**
     * 明细按行号索引。
     *
     * @param po 出库单
     * @return 行号 → 明细
     */
    private Map<Integer, OutOrderItemPo> itemsByLineNo(OutOrderPo po) {
        Map<Integer, OutOrderItemPo> map = new LinkedHashMap<>();
        for (OutOrderItemPo item : outOrderItemMapper.listByDo(tenant(), po.getId())) {
            map.put(item.getLineNo(), item);
        }
        if (map.isEmpty()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "出库单无明细（数据异常）：" + po.getDoNo());
        }
        return map;
    }

    /**
     * 取明细（不存在即 10001）。
     *
     * @param items  行号索引
     * @param lineNo 行号
     * @param doNo   出库单号（错误消息用）
     * @return 明细
     */
    private OutOrderItemPo requireItem(Map<Integer, OutOrderItemPo> items, int lineNo, String doNo) {
        OutOrderItemPo item = items.get(lineNo);
        if (item == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "出库单 " + doNo + " 不存在明细行号 " + lineNo);
        }
        return item;
    }

    /**
     * 组装库存命令。
     *
     * @param po         出库单
     * @param skuId      商品
     * @param qty        数量
     * @param locationId 库位（可空）
     * @param idemKey    库存幂等键（动作前缀 + 单号 + 行号）
     * @return 库存命令
     */
    private StockOpCommand stockCommand(OutOrderPo po, long skuId, BigDecimal qty, Long locationId, String idemKey) {
        return StockOpCommand.of(po.getWarehouseId(), po.getMerchantId(), skuId, qty, STOCK_BIZ_TYPE, po.getId(),
                po.getDoNo(), idemKey, locationId);
    }

    /**
     * 填充收件人 PII（密文 + 掩码；明文不落库、不入日志）。
     *
     * @param po 出库单
     * @param ro 入参
     */
    private void fillReceiverPii(OutOrderPo po, CreateOutOrderRo ro) {
        po.setReceiverNameEnc(piiCipher.encrypt(ro.receiverName().trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        po.setReceiverNameMasked(PiiCipher.maskName(ro.receiverName().trim()));
        po.setReceiverPhoneEnc(piiCipher.encrypt(ro.receiverPhone().trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        po.setReceiverPhoneMasked(PiiCipher.maskPhone(ro.receiverPhone().trim()));
        po.setReceiverAddrEnc(piiCipher.encrypt(ro.receiverAddress().trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        po.setReceiverAddrMasked(PiiCipher.maskAddress(ro.receiverAddress().trim()));
        po.setReceiverProvince(ro.receiverProvince());
        po.setReceiverCity(ro.receiverCity());
        po.setReceiverDistrict(ro.receiverDistrict());
    }

    /**
     * 余量单继承收件人 PII（直接复制密文与掩码，**不解密**：解密再加密既无必要，也扩大了明文暴露面）。
     *
     * @param from 原单
     * @param to   余量单
     */
    private void copyReceiverPii(OutOrderPo from, OutOrderPo to) {
        to.setReceiverNameEnc(from.getReceiverNameEnc());
        to.setReceiverNameMasked(from.getReceiverNameMasked());
        to.setReceiverPhoneEnc(from.getReceiverPhoneEnc());
        to.setReceiverPhoneMasked(from.getReceiverPhoneMasked());
        to.setReceiverAddrEnc(from.getReceiverAddrEnc());
        to.setReceiverAddrMasked(from.getReceiverAddrMasked());
        to.setReceiverProvince(from.getReceiverProvince());
        to.setReceiverCity(from.getReceiverCity());
        to.setReceiverDistrict(from.getReceiverDistrict());
    }

    /**
     * 是否当日单（按截单时间判定；不影响能否下单，只影响批次）。
     *
     * @return 当日单返回 {@code true}
     */
    private boolean isSameDayNow() {
        return CutoffRule.isSameDay(LocalDateTime.now(), parseCutoff());
    }

    /**
     * 解析截单时刻（配置非法时按"不截单"处理并告警，不阻断建单——配置错误不该让业务停摆）。
     *
     * @return 截单时刻；不可解析返回 {@code null}
     */
    private LocalTime parseCutoff() {
        String raw = wmsProperties.getOutbound().getCutoffTime();
        try {
            return raw == null || raw.isBlank() ? null : LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException ex) {
            log.warn("截单时间配置无法解析，本次按不截单处理：{}", raw);
            return null;
        }
    }

    /**
     * 租户（每次都取，不能缓存成静态值）。
     *
     * @return 租户 ID
     */
    private long tenant() {
        return TenantContext.tenantId();
    }

    /**
     * 操作人（未登录的运维/任务场景为 {@code null}）。
     *
     * @return 用户 ID
     */
    private Long operator() {
        return TenantContext.userId();
    }

    /**
     * 当前 traceId（写入状态流水，便于按链路排障）。
     *
     * @return traceId；缺失返回 {@code null}
     */
    private String traceId() {
        return MDC.get(TraceConst.MDC_TRACE_ID);
    }

    /**
     * 对象 → JSON（幂等缓存）。
     *
     * @param value 对象
     * @return JSON
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "幂等响应序列化失败", ex);
        }
    }

    /**
     * JSON → 对象（仅重放场景使用）。
     *
     * @param json JSON
     * @param type 目标类型
     * @param <T>  类型
     * @return 对象
     */
    private <T> T readCached(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "幂等缓存反序列化失败", ex);
        }
    }

    /**
     * 明细 + SKU 主数据（建单时一次性解析，避免后面重复查主数据）。
     *
     * @param lineNo 行号
     * @param sku    SKU 主数据
     * @param ro     入参
     */
    private record ItemWithSku(int lineNo, MasterDataQueryService.SkuBrief sku, CreateOutOrderRo.ItemRo ro) {
    }
}
