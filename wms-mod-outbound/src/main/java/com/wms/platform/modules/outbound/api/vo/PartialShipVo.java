package com.wms.platform.modules.outbound.api.vo;

import java.math.BigDecimal;

/**
 * 部分发货结果（API-OUT-12）。
 *
 * <p>一次部分发货会**同时改变两张单**，因此结果必须把两张单都回给调用方——只回新单号会让
 * 前端无法说明"原单为什么变成已出库"，现场也难核对余量去向。
 *
 * @param originDoNo     原出库单号（转「已出库」）
 * @param shippedQty     原单本次实际出库数量合计
 * @param newDoNo        余量新单号（转「待接单」）
 * @param newDoId        余量新单 ID
 * @param remainderQty   余量数量合计
 * @param message        说明（中文，便于前端提示）
 */
public record PartialShipVo(String originDoNo, BigDecimal shippedQty, String newDoNo, Long newDoId,
                            BigDecimal remainderQty, String message) {
}
