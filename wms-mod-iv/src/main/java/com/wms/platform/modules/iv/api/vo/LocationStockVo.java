package com.wms.platform.modules.iv.api.vo;

import com.wms.platform.modules.iv.app.LocationStockView;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 库位明细视图（03 API-IV-01 {@code /inventory/locations}）。
 */
@Getter
@Setter
public class LocationStockVo {

    /** 库位 ID。 */
    private Long locationId;

    /** 商品 ID。 */
    private Long skuId;

    /** 库位实物。 */
    private BigDecimal physicalQty;

    /** 已分配待拣。 */
    private BigDecimal allocatedQty;

    /** 可拣量（实物 − 已分配；负数说明账有问题，需查不变量）。 */
    private BigDecimal pickableQty;

    /**
     * 静态工厂（B4）。
     *
     * @param view 库位明细视图（应用层）
     * @return Vo
     */
    public static LocationStockVo of(LocationStockView view) {
        LocationStockVo vo = new LocationStockVo();
        vo.setLocationId(view.locationId());
        vo.setSkuId(view.skuId());
        vo.setPhysicalQty(view.physicalQty());
        vo.setAllocatedQty(view.allocatedQty());
        vo.setPickableQty(view.pickableQty());
        return vo;
    }
}
