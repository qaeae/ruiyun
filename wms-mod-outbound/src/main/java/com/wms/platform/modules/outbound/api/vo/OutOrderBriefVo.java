package com.wms.platform.modules.outbound.api.vo;

import java.time.LocalDateTime;

/**
 * 出库单列表行视图（API-OUT-13 分页）。
 *
 * <p>刻意**不含收件人 PII**：列表是批量展示场景，PII 泄露面最大；需要查看收件人时应走详情
 * 并按权限与留痕（03 §1.1、B16）。
 *
 * @param id            出库单 ID（雪花 ID，序列化为字符串）
 * @param doNo          出库单号
 * @param status        状态
 * @param warehouseId   仓库 ID
 * @param merchantId    商家 ID
 * @param carrierCode   承运商编码
 * @param isSameDay     是否当日单
 * @param packageCount  件数
 * @param createdAt     建单时间
 * @param finishedAt    出库时间（未出库为 {@code null}）
 */
public record OutOrderBriefVo(Long id, String doNo, String status, Long warehouseId, Long merchantId,
                              String carrierCode, Boolean isSameDay, Integer packageCount,
                              LocalDateTime createdAt, LocalDateTime finishedAt) {
}
