package com.wms.platform.modules.iv.app;

import com.wms.platform.modules.iv.domain.InventoryInvariant;
import java.util.List;

/**
 * 库存应用服务（03 API-IV-01 ~ API-IV-06 的领域实现）。
 *
 * <p><b>六个动作的口径差异（最容易搞错的地方，全部已在实现里写死）</b>：
 * <table border="1">
 *   <caption>动作与账务影响</caption>
 *   <tr><th>动作</th><th>SKU 三态</th><th>库位账</th><th>说明</th></tr>
 *   <tr><td>预占 reserve</td><td>可用 −q、预占 +q</td><td>不动</td><td>还没决定从哪个库位拿</td></tr>
 *   <tr><td>释放 release</td><td>可用 +q、预占 −q</td><td>不动</td><td>取消/缺货</td></tr>
 *   <tr><td>分配 allocate</td><td>不动</td><td>allocated +q</td><td>派发拣货任务</td></tr>
 *   <tr><td>拣货 pick</td><td>不动</td><td>allocated −q</td><td>{@code 01} 状态机第 3 行</td></tr>
 *   <tr><td>出库 ship</td><td>实物 −q、预占 −q</td><td>physical −q</td><td>**必须带库位**，否则库位账与 SKU 账会不平</td></tr>
 *   <tr><td>收货 receive</td><td>实物 +q、可用 +q</td><td>physical +q</td><td>上架/退货入库</td></tr>
 * </table>
 */
public interface InventoryService {

    /**
     * 预占可用库存（API-IV-05）。
     *
     * @param command 命令（{@code locationId} 可空）
     * @return 变更后三态
     */
    StockView reserve(StockOpCommand command);

    /**
     * 释放预占（API-IV-05）。
     *
     * @param command 命令
     * @return 变更后三态
     */
    StockView release(StockOpCommand command);

    /**
     * 出库扣减（实物与预占同减 + 库位实物减少）。
     *
     * @param command 命令（{@code locationId} 必填）
     * @return 变更后三态
     */
    StockView ship(StockOpCommand command);

    /**
     * 收货入库（上架/退货）。
     *
     * @param command 命令（{@code locationId} 必填）
     * @return 变更后三态
     */
    StockView receive(StockOpCommand command);

    /**
     * 盘点调整（{@code qty} 为带符号差异：盘盈正、盘亏负）。
     *
     * @param command 命令
     * @return 变更后三态
     */
    StockView adjust(StockOpCommand command);

    /**
     * 移库（双库位流水，SKU 三态不变）。
     *
     * @param command 移库命令
     */
    void move(MoveCommand command);

    /**
     * 派发分配（库位 allocated 增加）。
     *
     * @param command 命令（{@code locationId} 必填）
     */
    void allocate(StockOpCommand command);

    /**
     * 拣货完成（库位 allocated 减少，SKU 三态不变）。
     *
     * @param command 命令（{@code locationId} 必填）
     */
    void pick(StockOpCommand command);

    /**
     * 取消分配（库位 {@code allocated_qty} 归位，SKU 三态不变）。
     *
     * <p>用于"已派发拣货但还没拣货就取消"的场景（{@code 01} §2.6 取消矩阵：待接单/拣货中 →
     * {@code allocated} 归位）。与 {@link #pick} 的区别只在业务语义与流水类型：
     * 取消分配绝不会被误认为"货被拣走了"。
     *
     * @param command 命令（{@code locationId} 必填）
     */
    void unallocate(StockOpCommand command);

    /**
     * 查 SKU 三态（API-IV-01）。
     *
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @return 三态视图（无账时返回全 0，不抛异常——查询零库存是正常场景）
     */
    StockView querySku(long warehouseId, long merchantId, long skuId);

    /**
     * 查库位明细（API-IV-01 的 {@code /inventory/locations}）。
     *
     * @param warehouseId 仓库
     * @param skuId       商品
     * @return 库位明细视图列表
     */
    List<LocationStockView> listLocations(long warehouseId, long skuId);

    /**
     * 某业务单某 SKU 的剩余预占量（取消出库时"该释放多少"的唯一依据）。
     *
     * <p>为什么不让调用方自己记"已释放缺货量"：那是同一事实的第二份记录，迟早与流水不一致；
     * 而库存流水是不可删除的账，用它算出来的数天然与库存动作一致。
     *
     * @param bizNo 业务单号（出库单号）
     * @param skuId 商品
     * @return 剩余预占量（无记录返回 0）
     */
    java.math.BigDecimal reservedRemaining(String bizNo, long skuId);

    /**
     * 查库存流水（API-IV-02）。
     *
     * @param warehouseId 仓库（可为 0 = 不限）
     * @param skuId       商品（可为 0 = 不限）
     * @param bizType     业务类型（可空）
     * @param bizNo       业务单号（可空）
     * @param limit       最大条数
     * @return 流水视图列表
     */
    List<MovementView> listMovements(long warehouseId, long skuId, String bizType, String bizNo, int limit);

    /**
     * 该幂等键对应的库存动作是否已执行过。
     *
     * <p><b>为什么需要它</b>：{@code pf_idempotent} 有 7 天 TTL，而离线补传可能超过 7 天
     * （PDA 长假、设备损坏后才找回）。届时幂等表里已无记录、调用方 {@code begin} 会返回 PROCEED，
     * 但库存其实早就扣过了——只有 {@code iv_movement.uk_movement_idem} 知道真相。
     * 上层（PDA 补传、出库流程）据此把回执判成"幂等重复"而不是"受理成功"。
     *
     * @param idemKey 幂等键
     * @return 已执行返回 {@code true}
     */
    boolean hasApplied(String idemKey);

    /**
     * 执行全部库存不变量校验（API-IV-06，{@code 01} §4）。
     *
     * @return 逐条校验结果
     */
    List<InventoryInvariant.InvariantResult> checkInvariants();

    /**
     * 不变量违反项的可读描述（日终勾稽库存层用；空列表 = 全部成立）。
     *
     * <p>为什么不直接让日终勾稽拿 { checkInvariants()} 的结果自己判断：
     * 那会让 `fns` 域依赖 `iv` 域的**领域类型**（跨域依赖别人的内部模型），
     * 一旦 iv 域调整模型就会连带改 fns。这里收敛成一个能力：问一句
     * "有哪些不变量不成立"，只交换字符串。
     *
     * @return 违反项描述（空 = 全部成立）
     */
    List<String> describeInvariantViolations();
}
