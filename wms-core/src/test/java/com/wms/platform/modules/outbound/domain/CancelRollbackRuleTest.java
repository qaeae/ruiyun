package com.wms.platform.modules.outbound.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 取消/回滚矩阵单元测试（{@code 01} §2.6）。
 *
 * <p>这张表决定"取消时要回滚什么"。测它的价值在于每个时点的差异都对应真实损失：
 * 待接单取消却去动库位分配（库位账被改乱）、已打包取消却不作废面单（废面单仍可发货）、
 * 待装车取消却不计操作费（作业白干且无人结算）。
 */
@DisplayName("取消/回滚矩阵")
class CancelRollbackRuleTest {

    @Test
    @DisplayName("待接单：释放预占，不动库位分配，面单作废，不计操作费")
    void pendingStage() {
        CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(OutOrderStatus.PENDING);
        assertThat(plan.releaseReserved()).isTrue();
        assertThat(plan.releaseAllocation()).as("尚未派发，没有分配可归位").isFalse();
        assertThat(plan.voidLabel()).isTrue();
        assertThat(plan.chargeOperationFee()).isFalse();
    }

    @Test
    @DisplayName("拣货中：额外要把库位 allocated 归位")
    void pickingStage() {
        CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(OutOrderStatus.PICKING);
        assertThat(plan.releaseReserved()).isTrue();
        assertThat(plan.releaseAllocation()).as("已派发，必须把 allocated 归位").isTrue();
        assertThat(plan.chargeOperationFee()).isFalse();
    }

    @Test
    @DisplayName("已复核/已打包：已拣货回库位（不再动 allocated，pick 时已释放）")
    void checkedAndPackedStage() {
        for (OutOrderStatus stage : new OutOrderStatus[]{OutOrderStatus.CHECKED, OutOrderStatus.PACKED}) {
            CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(stage);
            assertThat(plan.releaseAllocation()).as("%s：分配已在拣货时释放", stage.code()).isFalse();
            assertThat(plan.pickedBackToLocation()).isTrue();
            assertThat(plan.chargeOperationFee()).isFalse();
        }
    }

    @Test
    @DisplayName("待装车：面单必须作废 + 操作费按实际作业量计")
    void awaitingLoadStageChargesOperationFee() {
        CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(OutOrderStatus.AWAITING_LOAD);
        assertThat(plan.voidLabel()).isTrue();
        assertThat(plan.chargeOperationFee()).as("待装车已产生作业量，操作费要计").isTrue();
    }

    @Test
    @DisplayName("终态取消被拒：已出库 20002/409，已拦截与已取消同样拒绝")
    void terminalStagesRejected() {
        for (OutOrderStatus stage : new OutOrderStatus[]{OutOrderStatus.SHIPPED, OutOrderStatus.HELD,
                OutOrderStatus.CANCELLED}) {
            assertThatThrownBy(() -> CancelRollbackRule.plan(stage))
                    .as("%s 不可取消", stage.code())
                    .isInstanceOfSatisfying(BizException.class, ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION);
                        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
                    });
        }
    }

    @Test
    @DisplayName("缺货待处理：按「已派发未拣货」处理（释放分配 + 释放剩余预占）")
    void shortageStage() {
        CancelRollbackRule.RollbackPlan plan = CancelRollbackRule.plan(OutOrderStatus.SHORTAGE);
        assertThat(plan.releaseAllocation()).isTrue();
        assertThat(plan.releaseReserved()).isTrue();
    }
}
