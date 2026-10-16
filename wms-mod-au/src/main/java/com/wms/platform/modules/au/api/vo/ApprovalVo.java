package com.wms.platform.modules.au.api.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 复核单视图（03 API-AUTH-04/05）。
 *
 * <p>带上 {@code payloadJson}（内容快照）是刻意的：**复核人必须看到"自己要批的到底是哪一份内容"**，
 * 而不是只看到一个金额和一个事由。
 *
 * @param id           记录 ID
 * @param approvalNo   审批单号
 * @param bizType      业务类型（枚举名）
 * @param bizTypeLabel 业务类型中文
 * @param bizId        业务对象 ID
 * @param bizNo        业务单号
 * @param amount       涉及金额
 * @param payloadJson  审批内容快照
 * @param reason       申请事由
 * @param status       状态
 * @param applyBy      发起人
 * @param applyAt      发起时间
 * @param approveBy    复核人
 * @param approveAt    复核时间
 * @param rejectReason 驳回原因
 * @param executed     副作用是否已执行
 */
public record ApprovalVo(Long id, String approvalNo, String bizType, String bizTypeLabel, Long bizId, String bizNo,
                         BigDecimal amount, String payloadJson, String reason, String status, Long applyBy,
                         LocalDateTime applyAt, Long approveBy, LocalDateTime approveAt, String rejectReason,
                         boolean executed) {
}
