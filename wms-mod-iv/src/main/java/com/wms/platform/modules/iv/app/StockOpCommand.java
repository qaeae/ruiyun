package com.wms.platform.modules.iv.app;

import java.math.BigDecimal;

/**
 * 库存操作命令（预占/释放/出库/收货/调整共用）。
 *
 * <p>用同一个命令对象承载多种动作，是为了让"每个动作都必须带幂等键与业务单号"成为**编译期要求**——
 * 库存流水缺少 {@code bizNo} 就追不了单，缺少 {@code idemKey} 就可能重复扣减，
 * 这两样都不能靠事后补。
 *
 * @param warehouseId 仓库
 * @param merchantId  商家
 * @param skuId       商品 ID（内部，由主数据解析得到）
 * @param qty         数量（**非负**；调整动作传带符号差异）
 * @param bizType     业务类型：ASN/DO/STOCKTAKE/RETURN/MOVE
 * @param bizId       业务单据 ID
 * @param bizNo       业务单号
 * @param idemKey     幂等键（{@code iv_movement.uk_movement_idem} 的最后防线）
 * @param locationId  库位 ID（预占/释放可为空；出库/收货/移库必填）
 * @param operatorId  操作人（可空）
 * @param deviceCode  设备编号（可空）
 */
public record StockOpCommand(long warehouseId, long merchantId, long skuId, BigDecimal qty, String bizType,
                             long bizId, String bizNo, String idemKey, Long locationId, Long operatorId,
                             String deviceCode) {

    /**
     * 简化构造（内部调用，无操作人/设备）。
     *
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param qty         数量
     * @param bizType     业务类型
     * @param bizId       业务单据 ID
     * @param bizNo       业务单号
     * @param idemKey     幂等键
     * @param locationId  库位（可空）
     * @return 命令
     */
    public static StockOpCommand of(long warehouseId, long merchantId, long skuId, BigDecimal qty, String bizType,
                                    long bizId, String bizNo, String idemKey, Long locationId) {
        return new StockOpCommand(warehouseId, merchantId, skuId, qty, bizType, bizId, bizNo, idemKey, locationId,
                null, null);
    }
}
