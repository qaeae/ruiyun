package com.wms.platform.modules.iv.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.math.BigDecimal;

/**
 * 库存三态（实物 / 可用 / 预占）——W3 库存域的核心模型。
 *
 * <p><b>不变量：{@code physical = available + reserved} 且三者非负</b>（{@code 01} §4 第 1 条）。
 * 这个不变量值得写成不可绕过的值对象，而不是散落在 Service 里做加减：库存算错是
 * "越查越乱、最后只能盘库"的典型问题，而**唯一可靠的防线是让任何一次状态变更都必须
 * 经过这里**（构造即校验），再由数据库 CHECK 约束兜底。
 *
 * <p>各动作的三态变化（与 {@code 01} 状态机表逐行对应）：
 * <table border="1">
 *   <caption>三态变更口径</caption>
 *   <tr><th>动作</th><th>physical</th><th>available</th><th>reserved</th><th>说明</th></tr>
 *   <tr><td>预占 reserve</td><td>—</td><td>−q</td><td>+q</td><td>下单冻结可用量</td></tr>
 *   <tr><td>释放 release</td><td>—</td><td>+q</td><td>−q</td><td>取消/缺货释放</td></tr>
 *   <tr><td>出库 ship</td><td>−q</td><td>—</td><td>−q</td><td>实物与预占同减，不变量保持</td></tr>
 *   <tr><td>收货 receive</td><td>+q</td><td>+q</td><td>—</td><td>上架/退货入库</td></tr>
 *   <tr><td>盘点调整 adjust</td><td>±d</td><td>±d</td><td>—</td><td>盘盈正、盘亏负</td></tr>
 * </table>
 *
 * <p>为什么"出库"减的是 reserved 而不是 available：出库的货在下单时就已经从 available
 * 转到 reserved，出库是"把预占的货真正发走"，所以减 reserved + 减 physical。
 * 若误减 available，会在有预占的情况下把别人已经预定的量再挪走（超卖）。
 *
 * @param physicalQty  实物库存
 * @param availableQty 可用库存
 * @param reservedQty  预占库存
 */
public record StockState(BigDecimal physicalQty, BigDecimal availableQty, BigDecimal reservedQty) {

    /** 零库存。 */
    public static final StockState ZERO = new StockState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /**
     * 构造并校验（构造即校验，杜绝"先建后校"漏校验）。
     *
     * @param physicalQty  实物库存（{@code null} 视为 0）
     * @param availableQty 可用库存（{@code null} 视为 0）
     * @param reservedQty  预占库存（{@code null} 视为 0）
     * @throws BizException 非负或不变量不成立时抛 {@code 50004}
     */
    public StockState {
        physicalQty = nullSafe(physicalQty);
        availableQty = nullSafe(availableQty);
        reservedQty = nullSafe(reservedQty);
        if (physicalQty.signum() < 0 || availableQty.signum() < 0 || reservedQty.signum() < 0) {
            throw new BizException(ErrorCode.INVARIANT_VIOLATED,
                    "库存不得为负：physical=" + physicalQty + " available=" + availableQty
                            + " reserved=" + reservedQty);
        }
        if (physicalQty.compareTo(availableQty.add(reservedQty)) != 0) {
            throw new BizException(ErrorCode.INVARIANT_VIOLATED,
                    "三态不变量不成立：physical(" + physicalQty + ") ≠ available(" + availableQty
                            + ") + reserved(" + reservedQty + ")");
        }
    }

    /**
     * 预占：可用 → 预占。
     *
     * @param qty 数量
     * @return 变更后状态
     * @throws BizException 可用不足时抛 {@code 50001}
     */
    public StockState reserve(BigDecimal qty) {
        requirePositive(qty);
        if (availableQty.compareTo(qty) < 0) {
            throw new BizException(ErrorCode.AVAILABLE_STOCK_INSUFFICIENT,
                    "可用库存不足：可用 " + availableQty + "，需要 " + qty);
        }
        return new StockState(physicalQty, availableQty.subtract(qty), reservedQty.add(qty));
    }

    /**
     * 释放预占：预占 → 可用。
     *
     * @param qty 数量
     * @return 变更后状态
     * @throws BizException 预占不足时抛 {@code 50002}
     */
    public StockState release(BigDecimal qty) {
        requirePositive(qty);
        if (reservedQty.compareTo(qty) < 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "预占库存不足，无法释放：预占 " + reservedQty + "，需要 " + qty);
        }
        return new StockState(physicalQty, availableQty.add(qty), reservedQty.subtract(qty));
    }

    /**
     * 出库：实物与预占同减（不变量保持）。
     *
     * @param qty 数量
     * @return 变更后状态
     * @throws BizException 实物或预占不足时抛 {@code 50002}
     */
    public StockState ship(BigDecimal qty) {
        requirePositive(qty);
        if (physicalQty.compareTo(qty) < 0 || reservedQty.compareTo(qty) < 0) {
            throw new BizException(ErrorCode.STOCK_OCCUPIED,
                    "库存不足，无法出库：实物 " + physicalQty + "、预占 " + reservedQty + "，需要 " + qty);
        }
        return new StockState(physicalQty.subtract(qty), availableQty, reservedQty.subtract(qty));
    }

    /**
     * 收货（上架/退货入库）：实物与可用同增。
     *
     * @param qty 数量
     * @return 变更后状态
     */
    public StockState receive(BigDecimal qty) {
        requirePositive(qty);
        return new StockState(physicalQty.add(qty), availableQty.add(qty), reservedQty);
    }

    /**
     * 盘点调整：实物与可用同加减（盘盈正、盘亏负）。
     *
     * @param deltaQty 带符号差异
     * @return 变更后状态
     * @throws BizException 调整后为负时抛 {@code 50004}（由构造校验兜底）
     */
    public StockState adjust(BigDecimal deltaQty) {
        if (deltaQty == null || deltaQty.signum() == 0) {
            return this;
        }
        return new StockState(physicalQty.add(deltaQty), availableQty.add(deltaQty), reservedQty);
    }

    /**
     * 是否为零库存。
     *
     * @return 三态全 0 返回 {@code true}
     */
    public boolean isZero() {
        return physicalQty.signum() == 0 && availableQty.signum() == 0 && reservedQty.signum() == 0;
    }

    /**
     * 数量校验：必须为正（0 与负数通常是调用方算错，静默放过会掩盖 bug）。
     *
     * @param qty 数量
     */
    private void requirePositive(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数量必须为正数，实际：" + qty);
        }
    }

    /**
     * 空值转 0。
     *
     * @param value 值
     * @return 值或 0
     */
    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
