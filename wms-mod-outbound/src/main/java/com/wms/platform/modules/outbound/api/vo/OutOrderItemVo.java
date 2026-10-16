package com.wms.platform.modules.outbound.api.vo;

import java.math.BigDecimal;

/**
 * 出库单明细视图（{@code out_order_item} 的对外投影）。
 *
 * <p>不暴露 {@code tenantId}/{@code doId}/{@code deleted} 等内部字段；数量口径为**字符串 decimal、
 * 4 位小数**（03 §1：避免 JS 双精度误差，由统一 JSON 配置保证序列化形态）。
 *
 * @param lineNo      行号
 * @param skuId       商品 ID（雪花 ID，序列化为字符串）
 * @param skuCode     商品编码
 * @param skuName     商品名称
 * @param qty         应发数量
 * @param pickedQty   实拣数量
 * @param locationId  拣货库位 ID（未派发为 {@code null}）
 * @param unit        单位
 */
public record OutOrderItemVo(Integer lineNo, Long skuId, String skuCode, String skuName, BigDecimal qty,
                             BigDecimal pickedQty, Long locationId, String unit) {
}
