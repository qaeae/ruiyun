package com.wms.platform.modules.iv.api.vo;

import java.math.BigDecimal;

/**
 * 盘点明细视图（03 API-IV-03）。
 *
 * <p>同时返回 {@code bookQty}（快照）与 {@code diffQty}：审批人需要看到
 * "账面多少、实物多少、差多少"三个数才能判断是否合理，只给差异等于让他凭空相信。
 *
 * @param skuId       商品 ID
 * @param locationId  库位 ID
 * @param bookQty     账面快照
 * @param actualQty   实盘（未录入为 null）
 * @param diffQty     差异（盘盈正、盘亏负；未录入为 null）
 * @param itemStatus  明细状态
 * @param remark      备注
 */
public record StocktakeItemVo(Long skuId, Long locationId, BigDecimal bookQty, BigDecimal actualQty,
                              BigDecimal diffQty, String itemStatus, String remark) {
}
