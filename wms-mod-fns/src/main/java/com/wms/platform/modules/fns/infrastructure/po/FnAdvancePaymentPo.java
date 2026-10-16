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
 * 垫付台账（表 {@code fn_advance_payment}，{@code 01} §3.9）。
 *
 * <p>唯一键 {@code uk_advance(tenant_id, waybill_no)}：**同一运单只垫付一次**。
 * 运单号天然是幂等键——重放不会让商家"被垫付两次"，也就不会被多收利息/被多扣。
 */
@Getter
@Setter
@TableName(value = "fn_advance_payment", autoResultMap = true)
public class FnAdvancePaymentPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 商家。 */
    private Long merchantId;

    /** 运单号（幂等键）。 */
    private String waybillNo;

    /** 关联冻结单。 */
    private Long freezeId;

    /** 垫付金额。 */
    private BigDecimal amount;

    /** 状态：待核销/已核销。 */
    private String status;

    /** 账期。 */
    private String billingPeriod;

    /** 发生时间（限额扫描按它取近期垫付）。 */
    private LocalDateTime occurredAt;

    /** 核销时间。 */
    private LocalDateTime clearedAt;

    /** 备注（超限时记录拦截原因）。 */
    private String remark;

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
     * @param id            雪花 ID
     * @param tenantId      租户
     * @param merchantId    商家
     * @param waybillNo     运单号
     * @param freezeId      冻结单 ID
     * @param amount        垫付金额
     * @param billingPeriod 账期
     * @param creatorId     创建人
     * @return 垫付记录
     */
    public static FnAdvancePaymentPo of(long id, long tenantId, long merchantId, String waybillNo, Long freezeId,
                                       BigDecimal amount, String billingPeriod, Long creatorId) {
        FnAdvancePaymentPo po = new FnAdvancePaymentPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setMerchantId(merchantId);
        po.setWaybillNo(waybillNo);
        po.setFreezeId(freezeId);
        po.setAmount(amount);
        po.setStatus(STATUS_PENDING_CLEAR);
        po.setBillingPeriod(billingPeriod);
        po.setOccurredAt(LocalDateTime.now());
        po.setCreatedBy(creatorId);
        po.setDeleted(0);
        return po;
    }

    /** 待核销。 */
    public static final String STATUS_PENDING_CLEAR = "待核销";

    /** 已核销。 */
    public static final String STATUS_CLEARED = "已核销";
}
