package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wms.platform.modules.au.domain.ApprovalRule;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 敏感操作双人复核单（表 {@code au_approval}，{@code 01} §3.16）。
 *
 * <p>两个唯一键各有明确用途：
 * <ul>
 *   <li>{@code uk_approval_no}：单号唯一（对外标识）；</li>
 *   <li>{@code uk_approval_biz}：**同一业务对象只允许一张审批单**——防止"同一件事提交两份，
 *       复核人点错一份"（两份内容可能不同，点错的那份就是错批）。</li>
 * </ul>
 */
@Getter
@Setter
@TableName(value = "au_approval", autoResultMap = true)
public class AuApprovalPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 审批单号。 */
    private String approvalNo;

    /** 业务类型。 */
    private String bizType;

    /** 业务对象 ID（可空）。 */
    private Long bizId;

    /** 业务单号。 */
    private String bizNo;

    /** 仓库。 */
    private Long warehouseId;

    /** 商家。 */
    private Long merchantId;

    /** 涉及金额（可空）。 */
    private BigDecimal amount;

    /** 审批内容快照（JSON）。 */
    private String payloadJson;

    /** 内容指纹（SHA-256）。 */
    private String payloadHash;

    /** 申请事由。 */
    private String reason;

    /** 状态：待复核/已通过/已驳回/已撤回。 */
    private String status;

    /** 发起人。 */
    private Long applyBy;

    /** 发起时间。 */
    private LocalDateTime applyAt;

    /** 复核人。 */
    private Long approveBy;

    /** 复核时间。 */
    private LocalDateTime approveAt;

    /** 驳回原因。 */
    private String rejectReason;

    /** 是否已执行副作用。 */
    private Integer executed;

    /** 执行时间。 */
    private LocalDateTime executedAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param approvalNo  单号
     * @param bizType     业务类型
     * @param bizId       业务对象 ID（可空）
     * @param bizNo       业务单号
     * @param warehouseId 仓库（可空）
     * @param merchantId  商家（可空）
     * @param amount      金额（可空）
     * @param payloadJson 内容快照
     * @param payloadHash 内容指纹
     * @param reason      事由
     * @param applyBy     发起人
     * @return 审批单
     */
    public static AuApprovalPo of(long id, long tenantId, String approvalNo, String bizType, Long bizId, String bizNo,
                                  Long warehouseId, Long merchantId, BigDecimal amount, String payloadJson,
                                  String payloadHash, String reason, Long applyBy) {
        AuApprovalPo po = new AuApprovalPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setApprovalNo(approvalNo);
        po.setBizType(bizType);
        po.setBizId(bizId);
        po.setBizNo(bizNo);
        po.setWarehouseId(warehouseId);
        po.setMerchantId(merchantId);
        po.setAmount(amount);
        po.setPayloadJson(payloadJson);
        po.setPayloadHash(payloadHash);
        po.setReason(reason);
        // 状态由本类内置为「待复核」：Service 不得直接 setStatus（01 §1.2）
        po.setStatus(ApprovalRule.STATUS_PENDING);
        po.setApplyBy(applyBy);
        po.setExecuted(0);
        po.setCreatedBy(applyBy);
        po.setUpdatedBy(applyBy);
        po.setDeleted(0);
        return po;
    }

    /**
     * 标记执行完成（副作用已落地的唯一凭据）。
     *
     * @param operatorId 操作人
     */
    public void markExecuted(Long operatorId) {
        this.executed = 1;
        this.executedAt = LocalDateTime.now();
        this.updatedBy = operatorId;
    }
}
