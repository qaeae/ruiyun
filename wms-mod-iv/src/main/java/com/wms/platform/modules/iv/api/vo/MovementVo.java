package com.wms.platform.modules.iv.api.vo;

import com.wms.platform.modules.iv.app.MovementView;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水视图（03 API-IV-02）。
 *
 * <p>带 `*_after` 三个值：排查"库存什么时候变的不对"时，**光看变动量不够，必须看变动后的账**
 * ——否则要顺着几十条流水自己累加，而累加过程本身就是出错来源。
 */
@Getter
@Setter
public class MovementVo {

    /** 流水号。 */
    private String movementNo;

    /** 类型。 */
    private String movementType;

    /** 数量（带符号）。 */
    private BigDecimal qty;

    /** 库位。 */
    private Long locationId;

    /** 商品 ID。 */
    private Long skuId;

    /** 业务类型。 */
    private String bizType;

    /** 业务单号。 */
    private String bizNo;

    /** 设备编号。 */
    private String deviceCode;

    /** 发生时间。 */
    private LocalDateTime occurredAt;

    /** 变动后实物。 */
    private BigDecimal physicalAfter;

    /** 变动后可用。 */
    private BigDecimal availableAfter;

    /** 变动后预占。 */
    private BigDecimal reservedAfter;

    /**
     * 静态工厂（B4）。
     *
     * @param view 流水视图（应用层）
     * @return Vo
     */
    public static MovementVo of(MovementView view) {
        MovementVo vo = new MovementVo();
        vo.setMovementNo(view.movementNo());
        vo.setMovementType(view.movementType());
        vo.setQty(view.qty());
        vo.setLocationId(view.locationId());
        vo.setSkuId(view.skuId());
        vo.setBizType(view.bizType());
        vo.setBizNo(view.bizNo());
        vo.setDeviceCode(view.deviceCode());
        vo.setOccurredAt(view.occurredAt());
        vo.setPhysicalAfter(view.physicalAfter());
        vo.setAvailableAfter(view.availableAfter());
        vo.setReservedAfter(view.reservedAfter());
        return vo;
    }
}
