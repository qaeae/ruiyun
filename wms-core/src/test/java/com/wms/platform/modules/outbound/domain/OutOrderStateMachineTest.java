package com.wms.platform.modules.outbound.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.modules.outbound.domain.statemachine.OutOrderStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出库单状态机单元测试（{@code 01} §2.1 转移表）。
 *
 * <p>为什么值得把"每条转移"都测一遍：状态机是出库单的唯一真相来源，**漏一条转移的后果是现场卡死**
 * （例如"缺货待处理"忘了支持恢复拣货，货到齐了也拣不了），而**多一条非法转移的后果是错发**
 * （例如允许从"已复核"直接装车，就绕过了交接卡点）。两类问题都不容易在评审里看出来。
 */
@DisplayName("出库单状态机")
class OutOrderStateMachineTest {

    @Test
    @DisplayName("建单：无状态 → 待接单；已存在状态时不允许重复建单")
    void createOnlyFromEmpty() {
        assertThat(OutOrderStateMachine.next(null, OutOrderEvent.CREATE)).isEqualTo(OutOrderStatus.PENDING);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.PENDING, OutOrderEvent.CREATE))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("正向全链路：待接单→拣货中→已复核→已打包→待装车→已出库")
    void happyPath() {
        assertThat(OutOrderStateMachine.next(OutOrderStatus.PENDING, OutOrderEvent.DISPATCH))
                .isEqualTo(OutOrderStatus.PICKING);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.PICKING, OutOrderEvent.PICK_COMPLETE))
                .isEqualTo(OutOrderStatus.CHECKED);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.CHECKED, OutOrderEvent.PACK))
                .isEqualTo(OutOrderStatus.PACKED);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.PACKED, OutOrderEvent.HANDOVER))
                .isEqualTo(OutOrderStatus.AWAITING_LOAD);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.AWAITING_LOAD, OutOrderEvent.LOAD))
                .isEqualTo(OutOrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("缺货分支：拣货中→缺货待处理→拣货中")
    void shortageBranch() {
        assertThat(OutOrderStateMachine.next(OutOrderStatus.PICKING, OutOrderEvent.SHORTAGE))
                .isEqualTo(OutOrderStatus.SHORTAGE);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.SHORTAGE, OutOrderEvent.RESUME))
                .isEqualTo(OutOrderStatus.PICKING);
    }

    @Test
    @DisplayName("称重量方：拣货中与已复核都可（复核后可重称覆盖）")
    void measureAllowedFromPickingAndChecked() {
        assertThat(OutOrderStateMachine.next(OutOrderStatus.PICKING, OutOrderEvent.MEASURE))
                .isEqualTo(OutOrderStatus.CHECKED);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.CHECKED, OutOrderEvent.MEASURE))
                .isEqualTo(OutOrderStatus.CHECKED);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.PENDING, OutOrderEvent.MEASURE))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("不可逆点：已出库之后任何事件都被拒（含装车、取消、拦截）")
    void shippedIsIrreversible() {
        for (OutOrderEvent event : new OutOrderEvent[]{OutOrderEvent.LOAD, OutOrderEvent.CANCEL,
                OutOrderEvent.HOLD, OutOrderEvent.PARTIAL_SHIP, OutOrderEvent.DISPATCH}) {
            assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.SHIPPED, event))
                    .as("已出库后 %s 必须被拒", event)
                    .isInstanceOf(BizException.class);
        }
        assertThat(OutOrderStatus.SHIPPED.isTerminal()).isTrue();
        assertThat(OutOrderStatus.HELD.isTerminal()).isTrue();
        assertThat(OutOrderStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("取消：5 个作业中状态都可取消；已拦截/已取消不可重复")
    void cancelMatrixStates() {
        for (OutOrderStatus from : new OutOrderStatus[]{OutOrderStatus.PENDING, OutOrderStatus.PICKING,
                OutOrderStatus.CHECKED, OutOrderStatus.PACKED, OutOrderStatus.AWAITING_LOAD,
                OutOrderStatus.SHORTAGE}) {
            assertThat(OutOrderStateMachine.next(from, OutOrderEvent.CANCEL))
                    .as("%s 可取消", from.code())
                    .isEqualTo(OutOrderStatus.CANCELLED);
        }
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.CANCELLED, OutOrderEvent.CANCEL))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.HELD, OutOrderEvent.CANCEL))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("跳步被拒：必须依次流转（防绕过复核/交接两道卡点）")
    void skipStepsRejected() {
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.PENDING, OutOrderEvent.LOAD))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.CHECKED, OutOrderEvent.HANDOVER))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.PICKING, OutOrderEvent.PACK))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("部分发货：已复核及之后允许；原单转已出库")
    void partialShipRequiresCheckedOrLater() {
        assertThat(OutOrderStateMachine.next(OutOrderStatus.CHECKED, OutOrderEvent.PARTIAL_SHIP))
                .isEqualTo(OutOrderStatus.SHIPPED);
        assertThat(OutOrderStateMachine.next(OutOrderStatus.AWAITING_LOAD, OutOrderEvent.PARTIAL_SHIP))
                .isEqualTo(OutOrderStatus.SHIPPED);
        assertThatThrownBy(() -> OutOrderStateMachine.next(OutOrderStatus.PENDING, OutOrderEvent.PARTIAL_SHIP))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("异常拦截：任意非终态都可拦截")
    void holdFromAnyNonTerminal() {
        for (OutOrderStatus from : new OutOrderStatus[]{OutOrderStatus.PENDING, OutOrderStatus.PICKING,
                OutOrderStatus.CHECKED, OutOrderStatus.PACKED, OutOrderStatus.AWAITING_LOAD,
                OutOrderStatus.SHORTAGE}) {
            assertThat(OutOrderStateMachine.next(from, OutOrderEvent.HOLD))
                    .as("%s 可拦截", from.code())
                    .isEqualTo(OutOrderStatus.HELD);
        }
    }
}
