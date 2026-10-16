package com.wms.platform.modules.iv.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三态代数测试（{@code 01} §3.4 / §4）。
 *
 * <p>这里每一条都对应一种**真实的库存事故**：
 * 出库误减可用（有预占时超卖）、预占超量（超卖）、构造出负数（账实不符）、
 * 移库改三态（"移一次少一件"）。
 */
@DisplayName("库存三态（StockState）")
class StockStateTest {

    /** 100 实物 / 80 可用 / 20 预占（不变量成立）。 */
    private static final StockState BASE = new StockState(new BigDecimal("100"), new BigDecimal("80"),
            new BigDecimal("20"));

    @Test
    @DisplayName("构造即校验：三态为负或不等式不成立时拒绝")
    void constructorShouldValidate() {
        assertThatThrownBy(() -> new StockState(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得为负");
        assertThatThrownBy(() -> new StockState(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("4")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVARIANT_VIOLATED))
                .hasMessageContaining("三态不变量不成立");
    }

    @Test
    @DisplayName("预占：可用 → 预占，总量不变")
    void reserveShouldMoveAvailableToReserved() {
        StockState after = BASE.reserve(new BigDecimal("30"));

        assertThat(after.availableQty()).isEqualByComparingTo("50");
        assertThat(after.reservedQty()).isEqualByComparingTo("50");
        assertThat(after.physicalQty()).as("预占不动实物").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("预占超过可用 → 50001（超卖的第一道防线）")
    void overReserveShouldFail() {
        assertThatThrownBy(() -> BASE.reserve(new BigDecimal("81")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AVAILABLE_STOCK_INSUFFICIENT));
    }

    @Test
    @DisplayName("释放：预占 → 可用；预占不足则拒绝")
    void releaseShouldMoveReservedBack() {
        StockState after = BASE.release(new BigDecimal("20"));

        assertThat(after.availableQty()).isEqualByComparingTo("100");
        assertThat(after.reservedQty()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> BASE.release(new BigDecimal("21")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.STOCK_OCCUPIED));
    }

    @Test
    @DisplayName("出库：实物与预占同减（减 available 会超卖，故必须减 reserved）")
    void shipShouldReducePhysicalAndReserved() {
        StockState after = BASE.ship(new BigDecimal("20"));

        assertThat(after.physicalQty()).isEqualByComparingTo("80");
        assertThat(after.reservedQty()).isEqualByComparingTo("0");
        assertThat(after.availableQty()).as("出库不动可用量（那部分早已转预占）").isEqualByComparingTo("80");
        assertThat(after.physicalQty()).as("不变量保持").isEqualByComparingTo(
                after.availableQty().add(after.reservedQty()));
    }

    @Test
    @DisplayName("出库超过预占 → 拒绝（有预占却不够出，说明先卖了别人的货）")
    void shipMoreThanReservedShouldFail() {
        StockState small = new StockState(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("5"));

        assertThatThrownBy(() -> small.ship(new BigDecimal("6")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.STOCK_OCCUPIED));
    }

    @Test
    @DisplayName("收货：实物与可用同增；盘点调整按带符号差异")
    void receiveAndAdjust() {
        StockState received = BASE.receive(new BigDecimal("50"));
        assertThat(received.physicalQty()).isEqualByComparingTo("150");
        assertThat(received.availableQty()).isEqualByComparingTo("130");

        StockState adjustedDown = BASE.adjust(new BigDecimal("-10"));
        assertThat(adjustedDown.physicalQty()).isEqualByComparingTo("90");
        assertThat(adjustedDown.availableQty()).isEqualByComparingTo("70");
        assertThat(adjustedDown.reservedQty()).as("盘亏不动预占").isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("盘点调整到负数 → 拒绝（由构造校验兜底）")
    void adjustToNegativeShouldFail() {
        assertThatThrownBy(() -> BASE.adjust(new BigDecimal("-81")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得为负");
    }

    @Test
    @DisplayName("非正数量拒绝：静默放过 0 会掩盖调用方算错")
    void nonPositiveQtyShouldFail() {
        assertThatThrownBy(() -> BASE.reserve(BigDecimal.ZERO)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BASE.ship(new BigDecimal("-1"))).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("流水类型符号规则：入库正、出库负、动作类由调用方带符号")
    void movementSignRules() {
        assertThat(MovementType.RECEIVE.signedQty(new BigDecimal("5"))).isEqualByComparingTo("5");
        assertThat(MovementType.SHIP.signedQty(new BigDecimal("5"))).as("出库必须落成负数").isEqualByComparingTo("-5");
        assertThat(MovementType.SHIP.signedQty(new BigDecimal("-5"))).as("传入负数也应归一为 -5").isEqualByComparingTo("-5");
        assertThat(MovementType.RESERVE.signedQty(new BigDecimal("5"))).isEqualByComparingTo("5");
        assertThat(MovementType.ADJUST.signedQty(new BigDecimal("-3"))).isEqualByComparingTo("-3");
        assertThat(MovementType.PICK.isAffectsSkuState()).isFalse();
        assertThat(MovementType.SHIP.isAffectsSkuState()).isTrue();
    }
}
