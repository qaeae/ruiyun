package com.wms.platform.modules.iv.app;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水视图（应用层，避免接口层依赖 Po）。
 *
 * @param movementNo      流水号
 * @param movementType    类型
 * @param qty             带符号数量
 * @param locationId      库位
 * @param skuId           商品 ID
 * @param bizType         业务类型
 * @param bizNo           业务单号
 * @param deviceCode      设备编号
 * @param occurredAt      发生时间
 * @param physicalAfter   变动后实物
 * @param availableAfter  变动后可用
 * @param reservedAfter   变动后预占
 */
public record MovementView(String movementNo, String movementType, BigDecimal qty, Long locationId, Long skuId,
                           String bizType, String bizNo, String deviceCode, LocalDateTime occurredAt,
                           BigDecimal physicalAfter, BigDecimal availableAfter, BigDecimal reservedAfter) {
}
