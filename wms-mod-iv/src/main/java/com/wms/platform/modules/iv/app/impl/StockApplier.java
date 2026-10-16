package com.wms.platform.modules.iv.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.iv.app.MoveCommand;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.iv.domain.MovementType;
import com.wms.platform.modules.iv.infrastructure.mapper.IvLocationMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvMovementMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvSkuMapper;
import com.wms.platform.modules.iv.infrastructure.po.IvMovementPo;
import com.wms.platform.modules.iv.infrastructure.po.IvSkuPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 库存变更执行器：**每个动作 = 一次条件更新 + 一条流水，同一个事务**。
 *
 * <p><b>为什么单独成类</b>：{@code @Transactional} 在同类内部自调用时不走代理、事务静默失效——
 * 这正是"流水写了、库存没扣"的典型成因。拆成独立 Bean 后事务边界落在调用链上，一眼可见。
 *
 * <p><b>为什么影响行数 = 0 要抛异常而不是返回 false</b>：库存动作是"要么成、要么不成"的
 * 强语义操作；返回 boolean 极容易被调用方忽略，造成"以为扣了其实没扣"。
 * 抛带码异常（{@code 50001}/{@code 50002}）能让上层按业务语义处理（PDA 转冲突、下单拦截）。
 */
@Service
@RequiredArgsConstructor
public class StockApplier {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(StockApplier.class);

    /** SKU 级账。 */
    private final IvSkuMapper skuMapper;

    /** 库位级账。 */
    private final IvLocationMapper locationMapper;

    /** 流水。 */
    private final IvMovementMapper movementMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 预占（可用 → 预占）。
     *
     * @param cmd 命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void reserve(StockOpCommand cmd) {
        // 1. 条件更新：可用量足够才预占
        int affected = skuMapper.reserve(tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (affected == 0) {
            throw new BizException(ErrorCode.AVAILABLE_STOCK_INSUFFICIENT,
                    "可用库存不足，无法预占 " + cmd.qty() + "（skuId=" + cmd.skuId() + "）");
        }
        // 2. 流水（同一事务）
        writeMovement(cmd, MovementType.RESERVE, cmd.qty(), null);
    }

    /**
     * 释放预占（预占 → 可用）。
     *
     * @param cmd 命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void release(StockOpCommand cmd) {
        // 1. 条件更新：预占量足够才释放
        int affected = skuMapper.release(tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (affected == 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "预占不足，无法释放 " + cmd.qty() + "（skuId=" + cmd.skuId() + "）");
        }
        // 2. 流水
        writeMovement(cmd, MovementType.UNRESERVE, cmd.qty(), null);
    }

    /**
     * 出库（SKU 三态 −q、库位实物 −q）。
     *
     * @param cmd 命令（库位必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void ship(StockOpCommand cmd) {
        // 1. 库位必填：否则库位账与 SKU 账会不平（01 §4 不变量 2）
        Long locationId = requireLocation(cmd, "出库");
        // 2. SKU 级：实物与预占同减
        int skuAffected = skuMapper.ship(tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (skuAffected == 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "库存不足，无法出库 " + cmd.qty() + "（skuId=" + cmd.skuId() + "）");
        }
        // 3. 库位级：实物同减（该库位实物不足 → 整笔回滚，避免"SKU 减了、库位没减"）
        int locAffected = locationMapper.shipFromLocation(tenant(), cmd.warehouseId(), locationId, cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (locAffected == 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "库位实物不足，无法出库 " + cmd.qty() + "（locationId=" + locationId + "）");
        }
        // 4. 流水
        writeMovement(cmd, MovementType.SHIP, cmd.qty(), locationId);
    }

    /**
     * 收货（SKU 三态 +q、库位实物 +q）。
     *
     * @param cmd 命令（库位必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void receive(StockOpCommand cmd) {
        // 1. 库位必填
        Long locationId = requireLocation(cmd, "收货入库");
        // 2. SKU 级：实物与可用同增（首次自动建账）
        skuMapper.receive(idGenerator.nextId(), tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId(), cmd.qty());
        // 3. 库位级：实物同增（首次自动建账）
        locationMapper.receiveToLocation(idGenerator.nextId(), tenant(), cmd.warehouseId(), locationId, cmd.merchantId(),
                cmd.skuId(), cmd.qty());
        // 4. 流水
        writeMovement(cmd, MovementType.RECEIVE, cmd.qty(), locationId);
    }

    /**
     * 盘点调整（{@code qty} 为带符号差异）。
     *
     * @param cmd 命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void adjust(StockOpCommand cmd) {
        // 1. 条件更新：调整后不得为负
        int affected = skuMapper.adjust(tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (affected == 0) {
            throw new BizException(ErrorCode.INVARIANT_VIOLATED,
                    "调整后库存会为负，已拒绝（skuId=" + cmd.skuId() + "，差异=" + cmd.qty() + "）");
        }
        // 2. 库位同步调整（保证不变量 2）：调整必须带库位，否则库位账不平
        Long locationId = cmd.locationId();
        if (locationId != null && cmd.qty().signum() > 0) {
            locationMapper.moveIn(idGenerator.nextId(), tenant(), cmd.warehouseId(), locationId, cmd.merchantId(),
                    cmd.skuId(), cmd.qty());
        } else if (locationId != null) {
            locationMapper.moveOut(tenant(), cmd.warehouseId(), locationId, cmd.merchantId(),
                    cmd.skuId(), cmd.qty().abs());
        }
        // 3. 流水（ADJUST 的符号由调用方决定，故直接传带符号值）
        writeMovement(cmd, MovementType.ADJUST, cmd.qty(), locationId);
    }

    /**
     * 派发分配（库位 allocated +q，SKU 三态不变）。
     *
     * @param cmd 命令（库位必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void allocate(StockOpCommand cmd) {
        // 1. 库位必填
        Long locationId = requireLocation(cmd, "分配");
        // 2. 条件更新：库位实物足够才可分配
        int affected = locationMapper.allocate(idGenerator.nextId(), tenant(), cmd.warehouseId(), locationId,
                cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (affected == 0) {
            throw new BizException(ErrorCode.LOCATION_NOT_PICKABLE,
                    "库位实物不足，无法分配 " + cmd.qty() + "（locationId=" + locationId + "）");
        }
        // 3. 流水（ALLOCATE 不影响 SKU 三态，after 传 null）
        writeMovement(cmd, MovementType.ALLOCATE, cmd.qty(), locationId);
    }

    /**
     * 拣货（库位 allocated −q，SKU 三态不变）。
     *
     * @param cmd 命令（库位必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void pick(StockOpCommand cmd) {
        // 1. 库位必填
        Long locationId = requireLocation(cmd, "拣货");
        // 2. 条件更新：该库位可拣量足够
        int affected = locationMapper.pick(tenant(), cmd.warehouseId(), locationId, cmd.merchantId(),
                cmd.skuId(), cmd.qty());
        if (affected == 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "该库位可拣量不足，无法拣货 " + cmd.qty() + "（locationId=" + locationId + "）");
        }
        // 3. 流水
        writeMovement(cmd, MovementType.PICK, cmd.qty(), locationId);
    }

    /**
     * 移库（源库位 −q、目标库位 +q，SKU 三态不变）。
     *
     * @param cmd 移库命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void move(MoveCommand cmd) {
        // 1. 源库位减少（条件更新：实物足够）
        int out = locationMapper.moveOut(tenant(), cmd.warehouseId(), cmd.fromLocationId(),
                cmd.merchantId(), cmd.skuId(), cmd.qty());
        if (out == 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "源库位实物不足，无法移库 " + cmd.qty() + "（locationId=" + cmd.fromLocationId() + "）");
        }
        // 2. 目标库位增加（首次自动建账）
        locationMapper.moveIn(idGenerator.nextId(), tenant(), cmd.warehouseId(), cmd.toLocationId(), cmd.merchantId(),
                cmd.skuId(), cmd.qty());
        // 3. 两条流水：目标流水的幂等键加 :IN 后缀，避免撞源流水的唯一键
        writeMoveMovement(cmd, cmd.idemKey(), false);
        writeMoveMovement(cmd, cmd.idemKey() + ":IN", true);
    }

    /**
     * 写库存流水（并读取变动后三态）。
     *
     * @param cmd        命令
     * @param type       流水类型
     * @param qty        非负数量（类型决定符号）
     * @param locationId 库位（可空）
     */
    private void writeMovement(StockOpCommand cmd, MovementType type, BigDecimal qty, Long locationId) {
        // 1. 读变动后三态（写入 *_after，供账实核对与排障）
        IvSkuPo after = skuMapper.selectByKey(tenant(), cmd.warehouseId(), cmd.merchantId(), cmd.skuId());
        // 2. 插入流水（唯一键 uk_movement_idem 由数据库兜底：重复动作会在此抛 DuplicateKeyException）
        long movementId = idGenerator.nextId();
        movementMapper.insert(IvMovementPo.of(movementId, tenant(), cmd.warehouseId(), cmd.merchantId(),
                cmd.skuId(), locationId, type, qty,
                after == null ? null : IvMovementPo.StockStateView.of(after.toState()),
                cmd.bizType(), cmd.bizId(), cmd.bizNo(), cmd.idemKey(), cmd.operatorId(), cmd.deviceCode()));
        log.debug("库存动作完成 type={} idemKey={} qty={}", type, cmd.idemKey(), qty);
    }

    /**
     * 写移库流水（源 −q / 目标 +q）。
     *
     * @param cmd     移库命令
     * @param idemKey 幂等键
     * @param inbound 是否为目标库位（+q）
     */
    private void writeMoveMovement(MoveCommand cmd, String idemKey, boolean inbound) {
        long movementId = idGenerator.nextId();
        BigDecimal qty = inbound ? cmd.qty() : cmd.qty().negate();
        movementMapper.insert(IvMovementPo.of(movementId, tenant(), cmd.warehouseId(), cmd.merchantId(),
                cmd.skuId(), inbound ? cmd.toLocationId() : cmd.fromLocationId(), MovementType.MOVE, qty,
                null, "MOVE", cmd.toLocationId(), cmd.bizNo(), idemKey, null, null));
    }

    /**
     * 取租户（每次调用都取：不能做成静态常量，否则多租户下会串数据）。
     *
     * @return 租户 ID
     */
    private long tenant() {
        return com.wms.platform.common.context.TenantContext.tenantId();
    }

    /**
     * 库位必填校验。
     *
     * @param cmd     命令
     * @param opName  动作名（错误信息用）
     * @return 库位 ID
     */
    private Long requireLocation(StockOpCommand cmd, String opName) {
        if (cmd.locationId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    opName + "必须指定库位（否则 SKU 账与库位账会不平，见 01 §4 不变量 2）");
        }
        return cmd.locationId();
    }
}
