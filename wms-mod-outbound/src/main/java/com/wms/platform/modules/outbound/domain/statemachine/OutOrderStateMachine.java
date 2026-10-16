package com.wms.platform.modules.outbound.domain.statemachine;

import com.wms.platform.common.api.BizException;
import com.wms.platform.modules.outbound.domain.OutOrderEvent;
import com.wms.platform.modules.outbound.domain.OutOrderStatus;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderPo;

import java.time.LocalDateTime;

/**
 * 出库单状态机（{@code 01} §2.1 转移表；{@code 01} §1.2「状态字段与状态流水分离」）。
 *
 * <p><b>为什么必须放在这个包</b>：ArchUnit 规则「状态字段只能由状态机修改」把允许调用
 * {@code Po.setStatus} 的包**硬编码为 {@code ..modules..domain.statemachine..}**。换到别的包
 * （哪怕叫 {@code OutOrderDomainService}）都会让 CI 直接红——这是刻意的：状态只能有一个入口。
 *
 * <p><b>两张表分开的理由</b>：{@link #next} 是纯函数（可在不构造 Po 的情况下单测全部转移），
 * {@link #apply} 只负责把结果写到实体上。业务代码用 {@code next} 做判定、用 Mapper 的条件更新
 * （{@code WHERE status = 原状态}）落库，这样并发下"两个人同时装车"只有一个能成功。
 */
public final class OutOrderStateMachine {

    private OutOrderStateMachine() {
    }

    /**
     * 计算目标状态（纯函数，不触碰实体）。
     *
     * @param from  当前状态（建单时为 {@code null}）
     * @param event 事件
     * @return 目标状态
     * @throws BizException 非法流转时抛 {@code 20002}（HTTP 409）
     */
    public static OutOrderStatus next(OutOrderStatus from, OutOrderEvent event) {
        // 1. 建单：只能从"无状态"进入待接单
        if (event == OutOrderEvent.CREATE) {
            if (from != null) {
                throw BizException.illegalTransition("出库单已存在（当前状态 " + from.code() + "），不能重复建单");
            }
            return OutOrderStatus.PENDING;
        }
        // 2. 非建单事件：必须有当前状态
        if (from == null) {
            throw BizException.illegalTransition("出库单尚无状态，无法执行事件 " + event.getLabel());
        }
        // 3. 终态一律拒绝（已出库=不可逆；已拦截/已取消=流程已结束）
        if (from.isTerminal()) {
            throw BizException.illegalTransition("出库单处于终态「" + from.code() + "」，不能再执行 "
                    + event.getLabel() + "（01 §2.1：装车出库后不可逆）");
        }
        // 4. 逐条实现 01 §2.1 转移表
        return switch (event) {
            case DISPATCH -> require(from, OutOrderStatus.PENDING, event, OutOrderStatus.PICKING);
            case PICK_COMPLETE -> require(from, OutOrderStatus.PICKING, event, OutOrderStatus.CHECKED);
            case SHORTAGE -> require(from, OutOrderStatus.PICKING, event, OutOrderStatus.SHORTAGE);
            case RESUME -> require(from, OutOrderStatus.SHORTAGE, event, OutOrderStatus.PICKING);
            // 称重量方：01 表第 6 行的"当前状态"是"拣货中/已复核"（允许在复核前先称重、也允许复核后重称覆盖）
            case MEASURE -> switch (from) {
                case PICKING, CHECKED -> OutOrderStatus.CHECKED;
                default -> throw BizException.illegalTransition(
                        "状态「" + from.code() + "」不能执行称重量方（需为拣货中或已复核）");
            };
            case PACK -> require(from, OutOrderStatus.CHECKED, event, OutOrderStatus.PACKED);
            case HANDOVER -> require(from, OutOrderStatus.PACKED, event, OutOrderStatus.AWAITING_LOAD);
            case LOAD -> require(from, OutOrderStatus.AWAITING_LOAD, event, OutOrderStatus.SHIPPED);
            // 取消：01 §2.6 覆盖待接单/拣货中/已复核/已打包/待装车五个时点（缺货待处理也在表内）
            case CANCEL -> switch (from) {
                case PENDING, PICKING, CHECKED, PACKED, AWAITING_LOAD, SHORTAGE -> OutOrderStatus.CANCELLED;
                default -> throw BizException.illegalTransition("状态「" + from.code() + "」不可取消");
            };
            // 异常拦截：任意非终态（终态已在第 3 步拦掉）
            case HOLD -> OutOrderStatus.HELD;
            // 部分发货：余量生成新单，原单转已出库（01 §2.1 的"部分发货"分支）
            case PARTIAL_SHIP -> switch (from) {
                case CHECKED, PACKED, AWAITING_LOAD -> OutOrderStatus.SHIPPED;
                default -> throw BizException.illegalTransition(
                        "状态「" + from.code() + "」不能部分发货（需为已复核及之后）");
            };
            case CREATE -> throw BizException.illegalTransition("建单事件不能重复执行");
        };
    }

    /**
     * 把事件应用到实体（**唯一允许修改 Po 状态的入口**）。
     *
     * @param po    出库单
     * @param event 事件
     * @return 目标状态
     */
    public static OutOrderStatus apply(OutOrderPo po, OutOrderEvent event) {
        OutOrderStatus from = po.getStatus() == null ? null : OutOrderStatus.of(po.getStatus());
        OutOrderStatus to = next(from, event);
        // 状态迁移（本包是 ArchUnit 唯一豁免修改 Po.status 的包）
        po.setStatus(to.code());
        // 终态时间戳与取消时点一并留痕：取消矩阵要靠 cancel_stage 决定回滚动作
        if (to == OutOrderStatus.SHIPPED) {
            po.setFinishedAt(LocalDateTime.now());
        }
        if (to == OutOrderStatus.CANCELLED) {
            po.setCancelStage(from == null ? null : from.code());
            po.setCancelledAt(LocalDateTime.now());
        }
        return to;
    }

    /**
     * 单条转移校验（把"当前状态必须是 X"写成一处，避免每条分支各写一遍 if）。
     *
     * @param from  当前状态
     * @param expect 期望的当前状态
     * @param event 事件（错误消息用）
     * @param to    目标状态
     * @return 目标状态
     */
    private static OutOrderStatus require(OutOrderStatus from, OutOrderStatus expect, OutOrderEvent event,
                                          OutOrderStatus to) {
        if (from != expect) {
            throw BizException.illegalTransition("出库单当前状态「" + from.code() + "」，不能执行 "
                    + event.getLabel() + "（需为「" + expect.code() + "」）");
        }
        return to;
    }
}
