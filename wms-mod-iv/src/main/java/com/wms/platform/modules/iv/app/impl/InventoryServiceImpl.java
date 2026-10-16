package com.wms.platform.modules.iv.app.impl;

import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.LocationStockView;
import com.wms.platform.modules.iv.app.MovementView;
import com.wms.platform.modules.iv.app.MoveCommand;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.iv.app.StockView;
import com.wms.platform.modules.iv.domain.InventoryInvariant;
import com.wms.platform.modules.iv.infrastructure.mapper.IvLocationMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvMovementMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvSkuMapper;
import com.wms.platform.modules.iv.infrastructure.po.IvLocationPo;
import com.wms.platform.modules.iv.infrastructure.po.IvMovementPo;
import com.wms.platform.modules.iv.infrastructure.po.IvSkuPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存应用服务实现。
 *
 * <p><b>核心职责：把"幂等"这件事在应用层做干净</b>。
 * 库存动作的幂等有两层（{@code 01} §5）：
 * <ol>
 *   <li>接口层 {@code Idempotency-Key}（由 {@code IdempotencyInterceptor} + {@code pf_idempotent} 负责）；</li>
 *   <li>{@code iv_movement.uk_movement_idem}（**库层最后防线**，本类负责把它翻译成"幂等成功"）。</li>
 * </ol>
 * 第 2 层很关键：它比幂等表更可靠（幂等表有 7 天 TTL，离线 7 天以上补传时只剩它）。
 * 所以本类对每个动作都捕获 {@code DuplicateKeyException}：说明该动作此前已落库，
 * 事务已回滚（**库存不会二次扣减**），直接返回当前状态即可。
 */
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    /** SKU 级账。 */
    private final IvSkuMapper skuMapper;

    /** 库位级账。 */
    private final IvLocationMapper locationMapper;

    /** 流水。 */
    private final IvMovementMapper movementMapper;

    /** 变更执行器（独立事务）。 */
    private final StockApplier applier;

    @Override
    public StockView reserve(StockOpCommand command) {
        // 1. 执行（幂等：撞流水唯一键即视为已执行过）
        runIdempotently("预占", command.idemKey(), () -> applier.reserve(command));
        // 2. 返回最新状态
        return querySku(command.warehouseId(), command.merchantId(), command.skuId());
    }

    @Override
    public StockView release(StockOpCommand command) {
        runIdempotently("释放", command.idemKey(), () -> applier.release(command));
        return querySku(command.warehouseId(), command.merchantId(), command.skuId());
    }

    @Override
    public StockView ship(StockOpCommand command) {
        runIdempotently("出库", command.idemKey(), () -> applier.ship(command));
        return querySku(command.warehouseId(), command.merchantId(), command.skuId());
    }

    @Override
    public StockView receive(StockOpCommand command) {
        runIdempotently("收货", command.idemKey(), () -> applier.receive(command));
        return querySku(command.warehouseId(), command.merchantId(), command.skuId());
    }

    @Override
    public StockView adjust(StockOpCommand command) {
        runIdempotently("调整", command.idemKey(), () -> applier.adjust(command));
        return querySku(command.warehouseId(), command.merchantId(), command.skuId());
    }

    @Override
    public void move(MoveCommand command) {
        runIdempotently("移库", command.idemKey(), () -> applier.move(command));
    }

    @Override
    public void allocate(StockOpCommand command) {
        runIdempotently("分配", command.idemKey(), () -> applier.allocate(command));
    }

    @Override
    public void pick(StockOpCommand command) {
        runIdempotently("拣货", command.idemKey(), () -> applier.pick(command));
    }

    @Override
    public StockView querySku(long warehouseId, long merchantId, long skuId) {
        // 1. 查三态账（无账返回全 0：零库存是正常状态，不该报错）
        IvSkuPo po = skuMapper.selectByKey(TenantContext.tenantId(), warehouseId, merchantId, skuId);
        BigDecimal physical = po == null ? BigDecimal.ZERO : po.getPhysicalQty();
        BigDecimal available = po == null ? BigDecimal.ZERO : po.getAvailableQty();
        BigDecimal reserved = po == null ? BigDecimal.ZERO : po.getReservedQty();
        // 2. 带出库位实物合计（运营一眼看出两级账是否平）
        BigDecimal locationSum = locationMapper.sumPhysicalBySku(TenantContext.tenantId(), warehouseId, skuId);
        // 3. 组装
        return new StockView(warehouseId, merchantId, skuId, physical, available, reserved,
                locationSum == null ? BigDecimal.ZERO : locationSum);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationStockView> listLocations(long warehouseId, long skuId) {
        // 1. 查库位明细（按库位排序，结果稳定）
        List<IvLocationPo> list = locationMapper.selectBySku(TenantContext.tenantId(), warehouseId, skuId);
        // 2. 转应用层视图（接口层不得见 Po，见 LocationStockView 类注释）
        List<LocationStockView> views = new ArrayList<>(list.size());
        for (IvLocationPo po : list) {
            views.add(LocationStockView.of(po.getLocationId(), po.getSkuId(), po.getPhysicalQty(),
                    po.getAllocatedQty()));
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovementView> listMovements(long warehouseId, long skuId, String bizType, String bizNo, int limit) {
        // 1. 有业务单号 → 按单追流水（对账与排障主路径）
        if (bizNo != null && !bizNo.isBlank()) {
            return toMovementViews(movementMapper.selectByBiz(TenantContext.tenantId(), bizType, bizNo));
        }
        // 2. 否则按 SKU 查最近流水
        if (warehouseId > 0 && skuId > 0) {
            return toMovementViews(movementMapper.selectBySku(TenantContext.tenantId(), warehouseId, skuId,
                    limit > 0 ? limit : 100));
        }
        // 3. 两者都不给：拒绝（避免全表扫描把生产库拖慢）
        throw new com.wms.platform.common.api.BizException(
                com.wms.platform.common.api.ErrorCode.PARAM_ERROR,
                "查询流水需提供 bizNo 或 (warehouseId + skuId)");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasApplied(String idemKey) {
        // 1. 空键视为未执行（调用方应保证非空；这里不抛异常，避免打断批量补传的其它条目）
        if (idemKey == null || idemKey.isBlank()) {
            return false;
        }
        // 2. 查流水唯一键（uk_movement_idem）
        return movementMapper.countByIdemKey(TenantContext.tenantId(), idemKey) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> describeInvariantViolations() {
        // 1. 复用同一套校验，只把"不成立"的项翻译成人话（避免两处各写一遍校验逻辑）
        List<String> violations = new ArrayList<>();
        for (InventoryInvariant.InvariantResult result : checkInvariants()) {
            if (!result.passed()) {
                violations.add(result.name() + "：违反 " + result.violation() + " 行");
            }
        }
        return violations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryInvariant.InvariantResult> checkInvariants() {
        // 1. 逐条执行不变量校验（{@code 01} §4 的口径，SQL 收敛在 Mapper）
        List<InventoryInvariant.InvariantResult> results = new ArrayList<>(4);
        results.add(new InventoryInvariant.InvariantResult(InventoryInvariant.NAME_SKU_BALANCE,
                skuMapper.countBalanceViolation()));
        results.add(new InventoryInvariant.InvariantResult(InventoryInvariant.NAME_SKU_NONNEG,
                skuMapper.countNegativeViolation()));
        results.add(new InventoryInvariant.InvariantResult(InventoryInvariant.NAME_LOCATION_SUM_MATCH,
                locationMapper.countLocationSumMismatch()));
        results.add(new InventoryInvariant.InvariantResult(InventoryInvariant.NAME_LOCATION_NONNEG,
                locationMapper.countNegativeViolation()));
        // 2. 有差异时打 warn：这是需要库管/财务介入的信号
        for (InventoryInvariant.InvariantResult result : results) {
            if (!result.passed()) {
                log.warn("库存不变量校验未通过：{} 违反 {} 行", result.name(), result.violation());
            }
        }
        return results;
    }

    /**
     * 流水 Po → 应用层视图。
     *
     * @param list 流水列表
     * @return 视图列表
     */
    private List<MovementView> toMovementViews(List<IvMovementPo> list) {
        List<MovementView> views = new ArrayList<>(list.size());
        for (IvMovementPo po : list) {
            views.add(new MovementView(po.getMovementNo(), po.getMovementType(), po.getQty(), po.getLocationId(),
                    po.getSkuId(), po.getBizType(), po.getBizNo(), po.getDeviceCode(), po.getOccurredAt(),
                    po.getPhysicalAfter(), po.getAvailableAfter(), po.getReservedAfter()));
        }
        return views;
    }

    /**
     * 执行库存动作并把"流水唯一键冲突"翻译成幂等成功。
     *
     * @param opName   动作名（日志用）
     * @param idemKey  幂等键
     * @param action   动作
     */
    private void runIdempotently(String opName, String idemKey, Runnable action) {
        try {
            action.run();
        } catch (DuplicateKeyException ex) {
            // uk_movement_idem 命中：该动作此前已执行成功（幂等表可能已过期）。
            // 事务已回滚 → 库存没有被二次扣减，这里按"幂等成功"处理并返回当前状态
            log.info("{}动作命中流水幂等键，判定为重复请求：idemKey={}", opName, idemKey);
        }
    }
}
