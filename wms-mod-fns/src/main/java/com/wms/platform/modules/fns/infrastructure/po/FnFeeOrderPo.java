package com.wms.platform.modules.fns.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用单（表 {@code fn_fee_order}，{@code 01} §3.8）。
 *
 * <p>唯一键 {@code uk_fee_biz(tenant_id, fee_type, biz_type, biz_id)}：**同一业务对象同类型费用只计一次**，
 * 这是计费幂等的核心（重复请求不会重复计费）。字段口径以 DDL 注释为准。
 */
@Getter
@Setter
@TableName(value = "fn_fee_order", autoResultMap = true)
public class FnFeeOrderPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 费用单号。 */
    private String feeNo;

    /** 商家。 */
    private Long merchantId;

    /** 仓库。 */
    private Long warehouseId;

    /** 费用类型：STORAGE/OPERATION/LOGISTICS/OVER_QUOTA/PENALTY。 */
    private String feeType;

    /** 业务类型。 */
    private String bizType;

    /** 业务对象 ID。 */
    private Long bizId;

    /** 业务单号（物流运费时为运单号）。 */
    private String bizNo;

    /** 金额。 */
    private BigDecimal amount;

    /** 数量。 */
    private BigDecimal quantity;

    /** 单价。 */
    private BigDecimal unitPrice;

    /** 计费快照（价目表版本，账单不回溯）。 */
    private String priceSnapshot;

    /** 关联冻结单。 */
    private Long freezeId;

    /** 关联资金流水。 */
    private Long ledgerId;

    /** 账期。 */
    private String billingPeriod;

    /** 状态。 */
    private String status;

    /** 红冲的原费用单（只红冲不冲正，见 `04`）。 */
    private Long reverseOfId;

    /** 计费时间。 */
    private LocalDateTime billedAt;

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
}
