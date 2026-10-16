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
 * 结算批次（表 {@code fn_settle_batch}，{@code 01} §3.7）。
 *
 * <p>唯一键 {@code uk_bill(tenant_id, bill_id)}：**一张对账单只生成一个结算批次**。
 * 这是"重复点确认就重复付款"的结构性防线——比在 Service 里 if 判断可靠得多。
 */
@Getter
@Setter
@TableName(value = "fn_settle_batch", autoResultMap = true)
public class FnSettleBatchPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 批次号。 */
    private String stbNo;

    /** 对账单 ID。 */
    private Long billId;

    /** 承运商。 */
    private String carrierCode;

    /** 账期。 */
    private String period;

    /** 运单数。 */
    private Integer waybillCount;

    /** 金额。 */
    private BigDecimal totalAmount;

    /** 状态：待下发/已下发/已结算/下发失败。 */
    private String status;

    /** 机构侧指令号。 */
    private String instructionNo;

    /** 下发时间。 */
    private LocalDateTime dispatchedAt;

    /** 结算完成时间。 */
    private LocalDateTime settledAt;

    /** 失败原因。 */
    private String failReason;

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
     * @param stbNo       批次号
     * @param billId      对账单 ID
     * @param carrierCode 承运商
     * @param period      账期
     * @param waybillCount 运单数
     * @param totalAmount 金额
     * @param creatorId   创建人
     * @return 批次
     */
    public static FnSettleBatchPo of(long id, long tenantId, String stbNo, long billId, String carrierCode,
                                     String period, int waybillCount, BigDecimal totalAmount, Long creatorId) {
        FnSettleBatchPo po = new FnSettleBatchPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setStbNo(stbNo);
        po.setBillId(billId);
        po.setCarrierCode(carrierCode);
        po.setPeriod(period);
        po.setWaybillCount(waybillCount);
        po.setTotalAmount(totalAmount);
        po.setStatus(STATUS_PENDING);
        po.setCreatedBy(creatorId);
        po.setDeleted(0);
        return po;
    }

    /** 待下发。 */
    public static final String STATUS_PENDING = "待下发";

    /** 已下发。 */
    public static final String STATUS_DISPATCHED = "已下发";

    /** 下发失败。 */
    public static final String STATUS_FAILED = "下发失败";

    /** 已结算。 */
    public static final String STATUS_SETTLED = "已结算";
}
