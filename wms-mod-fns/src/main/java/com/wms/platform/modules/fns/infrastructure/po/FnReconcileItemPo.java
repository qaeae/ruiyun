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
 * 对账明细（表 {@code fn_reconcile_item}，{@code 01} §3.6）。
 *
 * <p>唯一键 {@code uk_bill_waybill(tenant_id, bill_id, waybill_no)}：同一张对账单里一个运单只有一行。
 * 重复导入承运商文件时，靠它保证不会把差异算两遍。
 */
@Getter
@Setter
@TableName(value = "fn_reconcile_item", autoResultMap = true)
public class FnReconcileItemPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 对账单 ID。 */
    private Long billId;

    /** 运单号。 */
    private String waybillNo;

    /** 商家。 */
    private Long merchantId;

    /** 平台侧费用（可空 = 平台无对应费用）。 */
    private BigDecimal platformFee;

    /** 承运商侧费用。 */
    private BigDecimal carrierFee;

    /** 差异额。 */
    private BigDecimal diffAmount;

    /** 差异率。 */
    private BigDecimal diffRate;

    /** 差异类型。 */
    private String diffType;

    /** 处理结果：自动平账/挂账待处理/人工处理/责任方承担。 */
    private String handleResult;

    /** 责任方。 */
    private String liability;

    /** 说明。 */
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
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param billId      对账单 ID
     * @param waybillNo   运单号
     * @param merchantId  商家
     * @param platformFee 平台费用（可空）
     * @param carrierFee  承运商费用
     * @param diffAmount  差异额
     * @param diffRate    差异率
     * @param diffType    差异类型
     * @param handleResult 处理结果
     * @return 明细
     */
    public static FnReconcileItemPo of(long id, long tenantId, long billId, String waybillNo, Long merchantId,
                                       BigDecimal platformFee, BigDecimal carrierFee, BigDecimal diffAmount,
                                       BigDecimal diffRate, String diffType, String handleResult) {
        FnReconcileItemPo po = new FnReconcileItemPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setBillId(billId);
        po.setWaybillNo(waybillNo);
        po.setMerchantId(merchantId);
        po.setPlatformFee(platformFee);
        po.setCarrierFee(carrierFee);
        po.setDiffAmount(diffAmount);
        po.setDiffRate(diffRate);
        po.setDiffType(diffType);
        po.setHandleResult(handleResult);
        po.setDeleted(0);
        return po;
    }
}
