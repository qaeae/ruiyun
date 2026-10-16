package com.wms.platform.modules.outbound.domain;

import com.wms.platform.common.api.BizException;

/**
 * 取消/回滚矩阵（{@code 01} §2.6）。
 *
 * <p>这张表是"取消"这件事的全部规则来源：**取消不是把状态改成已取消就完了**，不同时点要回滚的
 * 东西完全不同（库存账、库位分配、冻结金额、面单、操作费）。把它写成领域规则而不是在 Service 里
 * 写 if-else 的价值在于：@code 服务层只负责"按计划执行动作"，评审时能一眼核对是否每个时点都覆盖。
 *
 * <p><b>与 {@code 01} §2.6 的一处实现口径说明（不是漏做）</b>：矩阵里"已复核/已打包"一行写着
 * "已拣库存回库位（{@code iv_movement} 反向）"。在本项目的库存口径下，
 * {@code pick} **只减库位 {@code allocated_qty}、不减库位 {@code physical_qty}**
 * （{@code 01} §2.7：拣货完成只动库位 allocated），实物是在**装车出库**时才从库位账扣掉的。
 * 因此"已拣货回库位"没有任何账务需要改（库位实物一直记着这些货），本实现把它落成
 * **状态流水里的一条说明 + 预占释放**，而不是伪造一条对冲流水——伪造会让库位账与实物对不上。
 */
public final class CancelRollbackRule {

    private CancelRollbackRule() {
    }

    /**
     * 生成取消回滚计划。
     *
     * @param stage 取消时点的状态
     * @return 回滚计划
     * @throws BizException 已出库/已拦截/已取消等终态取消时抛 {@code 20002}（HTTP 409）
     */
    public static RollbackPlan plan(OutOrderStatus stage) {
        return switch (stage) {
            case PENDING -> new RollbackPlan(true, false, true, false, false,
                    "待接单取消：释放预占与冻结；面单未取号，直接作废；不计操作费");
            case PICKING -> new RollbackPlan(true, true, true, false, false,
                    "拣货中取消：释放预占、库位分配归位；面单作废；不计操作费");
            case CHECKED, PACKED -> new RollbackPlan(true, false, true, false, true,
                    "已复核/已打包取消：释放预占、已拣货回拣货位；面单作废并标记；不计操作费");
            case AWAITING_LOAD -> new RollbackPlan(true, false, true, true, true,
                    "待装车取消：释放预占、已拣货回拣货位；已打印面单必须作废；操作费按实际作业量计（合同约定）");
            // 已出库：货已出账，只能走逆向流程（退货入库/拦截退回），不可取消
            case SHIPPED -> throw BizException.illegalTransition(
                    "出库单已装车出库（库存已出账），不可取消；如需退回请走退货入库流程（01 §2.1 不可逆点）");
            case HELD -> throw BizException.illegalTransition("出库单已被异常拦截（终态），不可取消");
            case CANCELLED -> throw BizException.illegalTransition("出库单已取消，无需重复取消");
            case SHORTAGE -> new RollbackPlan(true, true, true, false, false,
                    "缺货待处理取消：释放剩余预占、库位分配归位；面单作废；不计操作费");
        };
    }

    /**
     * 回滚计划（每个字段对应一个可执行动作）。
     *
     * @param releaseReserved     是否释放 SKU 预占（{@code reserved → available}）
     * @param releaseAllocation   是否把库位 {@code allocated_qty} 归位（仅"已派发未拣货"需要）
     * @param voidLabel           是否作废装运单/面单
     * @param chargeOperationFee  是否计操作费（按实际作业量，合同约定）
     * @param pickedBackToLocation 是否发生了"已拣货回拣货位"（仅用于流水说明，不产生账务动作）
     * @param remark              计划说明（写入状态流水，便于事后解释"这笔取消做了什么"）
     */
    public record RollbackPlan(boolean releaseReserved, boolean releaseAllocation, boolean voidLabel,
                               boolean chargeOperationFee, boolean pickedBackToLocation, String remark) {
    }
}
