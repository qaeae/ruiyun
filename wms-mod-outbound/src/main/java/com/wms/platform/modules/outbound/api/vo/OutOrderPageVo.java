package com.wms.platform.modules.outbound.api.vo;

import java.util.List;

/**
 * 出库单分页结果（03 §1：响应含 {@code total} 与 {@code list}）。
 *
 * @param total    满足条件的总条数
 * @param pageNum  页码（回显生效值，便于前端与请求对账）
 * @param pageSize 每页条数（回显生效值）
 * @param list     当前页数据
 */
public record OutOrderPageVo(long total, int pageNum, int pageSize, List<OutOrderBriefVo> list) {
}
