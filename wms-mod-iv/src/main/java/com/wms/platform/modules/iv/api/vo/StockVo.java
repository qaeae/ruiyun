package com.wms.platform.modules.iv.api.vo;

import com.wms.platform.modules.iv.app.StockView;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 库存三态视图（03 API-IV-01）。
 *
 * <p>{@code locationQtySum} 与 {@code physicalQty} 同时返回：**库管排查差异时第一个想看的就是这两个数**
 * （{@code 01} §4 不变量 2）。若只返回三态，排查时还得去数据库 join 一次，
 * 现场往往就因此放弃核对、直接凭感觉调账。
 */
@Getter
@Setter
public class StockVo {

    /** 仓库。 */
    private Long warehouseId;

    /** 商家。 */
    private Long merchantId;

    /** 商品 ID。 */
    private Long skuId;

    /** 实物库存。 */
    private BigDecimal physicalQty;

    /** 可用库存。 */
    private BigDecimal availableQty;

    /** 预占库存。 */
    private BigDecimal reservedQty;

    /** 库位实物合计（应等于 physicalQty；不相等即有差异，需查不变量 2）。 */
    private BigDecimal locationQtySum;

    /** 两级账是否平（{@code locationQtySum == physicalQty}）。 */
    private boolean twoLevelBalanced;

    /**
     * 静态工厂（B4）。
     *
     * @param view 三态视图
     * @return 库存 Vo
     */
    public static StockVo of(StockView view) {
        StockVo vo = new StockVo();
        vo.setWarehouseId(view.warehouseId());
        vo.setMerchantId(view.merchantId());
        vo.setSkuId(view.skuId());
        vo.setPhysicalQty(view.physicalQty());
        vo.setAvailableQty(view.availableQty());
        vo.setReservedQty(view.reservedQty());
        vo.setLocationQtySum(view.locationQtySum());
        vo.setTwoLevelBalanced(view.physicalQty().compareTo(view.locationQtySum()) == 0);
        return vo;
    }
}
