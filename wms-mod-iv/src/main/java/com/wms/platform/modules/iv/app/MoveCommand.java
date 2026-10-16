package com.wms.platform.modules.iv.app;

import java.math.BigDecimal;

/**
 * 移库命令（库位 → 库位）。
 *
 * <p>移库产生**两条**流水（源库位 −q、目标库位 +q），SKU 级三态不变：
 * 货在仓内换了位置，总量不该动。若误改 SKU 三态，会造成"移一次库少一件"的诡异差异。
 *
 * @param warehouseId    仓库
 * @param merchantId     商家
 * @param skuId          商品
 * @param qty            数量
 * @param fromLocationId 源库位
 * @param toLocationId   目标库位
 * @param bizNo          业务单号
 * @param idemKey        幂等键（源流水用；目标流水自动加后缀 {@code :IN}）
 */
public record MoveCommand(long warehouseId, long merchantId, long skuId, BigDecimal qty, long fromLocationId,
                          long toLocationId, String bizNo, String idemKey) {
}
