package com.wms.platform.modules.iv.api.vo;

import java.util.List;

/**
 * 盘点单视图（03 API-IV-03）。
 *
 * @param stocktakeNo 单号
 * @param warehouseId 仓库
 * @param zoneId      库区
 * @param scopeType   范围类型
 * @param status      状态
 * @param itemCount   明细条数
 * @param diffCount   有差异条数
 * @param createdBy   创建人
 * @param approvedBy  审批人
 * @param reason      驳回原因
 * @param remark      备注
 * @param items       明细（列表接口为 null，详情接口才有）
 */
public record StocktakeVo(String stocktakeNo, Long warehouseId, Long zoneId, String scopeType, String status,
                          int itemCount, int diffCount, Long createdBy, Long approvedBy, String reason,
                          String remark, List<StocktakeItemVo> items) {
}
