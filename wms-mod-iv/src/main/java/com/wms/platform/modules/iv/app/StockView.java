package com.wms.platform.modules.iv.app;

import java.math.BigDecimal;

/**
 * 库存三态视图（对外返回）。
 *
 * @param warehouseId    仓库
 * @param merchantId     商家
 * @param skuId          商品 ID
 * @param physicalQty    实物
 * @param availableQty   可用
 * @param reservedQty    预占
 * @param locationQtySum 库位实物合计（不变量 2 的现场对照值，便于运营一眼看出是否平账）
 */
public record StockView(long warehouseId, long merchantId, long skuId, BigDecimal physicalQty,
                        BigDecimal availableQty, BigDecimal reservedQty, BigDecimal locationQtySum) {
}
