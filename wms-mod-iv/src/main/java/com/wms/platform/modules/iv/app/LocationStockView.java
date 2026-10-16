package com.wms.platform.modules.iv.app;

import java.math.BigDecimal;

/**
 * 库位明细视图（应用层）。
 *
 * <p><b>为什么应用层要单独定义视图、而不让接口层直接用 Po</b>：架构规则规定
 * {@code modules..api..} 不得依赖 {@code modules..infrastructure..}——
 * 这条规则不是形式主义：一旦接口层拿到 Po，就会出现"某个接口顺手改了 Po 的一个字段"，
 * 而 Po 是表映射，改动会影响所有读写路径（本仓库的 ArchUnit 已实测抓过一次）。
 * 因此 Po → 视图的转换发生在应用层，接口层只看到视图。
 *
 * @param locationId   库位 ID
 * @param skuId        商品 ID
 * @param physicalQty  库位实物
 * @param allocatedQty 已分配待拣
 * @param pickableQty  可拣量（实物 − 已分配）
 */
public record LocationStockView(long locationId, long skuId, BigDecimal physicalQty, BigDecimal allocatedQty,
                                BigDecimal pickableQty) {

    /**
     * 由库位账构造。
     *
     * @param locationId   库位 ID
     * @param skuId        商品 ID
     * @param physicalQty  实物
     * @param allocatedQty 已分配
     * @return 视图
     */
    public static LocationStockView of(long locationId, long skuId, BigDecimal physicalQty,
                                       BigDecimal allocatedQty) {
        return new LocationStockView(locationId, skuId, physicalQty, allocatedQty,
                physicalQty.subtract(allocatedQty));
    }
}
