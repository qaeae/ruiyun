package com.wms.platform.modules.fns.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wms.platform.modules.fns.domain.ReconcileBillRule;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账单（表 {@code fn_reconcile_bill}，{@code 01} §3.6）。
 *
 * <p>唯一键 {@code uk_rcl(tenant_id, carrier_code, period)}：**一个承运商一个账期只有一张对账单**，
 * 重复生成请求应返回同一张（而不是新建第二张，否则同一笔钱会被结算两次）。
 */
@Getter
@Setter
@TableName(value = "fn_reconcile_bill", autoResultMap = true)
public class FnReconcileBillPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 对账单号。 */
    private String rclNo;

    /** 承运商编码。 */
    private String carrierCode;

    /** 账期（yyyyMM）。 */
    private String period;

    /** 状态。 */
    private String status;

    /** 运单数。 */
    private Integer totalWaybills;

    /** 平台侧金额合计。 */
    private BigDecimal platformAmount;

    /** 承运商侧金额合计。 */
    private BigDecimal carrierAmount;

    /** 差异合计。 */
    private BigDecimal diffAmount;

    /** 自动平账条数。 */
    private Integer autoSettledCount;

    /** 需人工处理条数。 */
    private Integer manualCount;

    /** 确认时间。 */
    private LocalDateTime confirmedAt;

    /** 确认人。 */
    private Long confirmedBy;

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
     * @param id           雪花 ID
     * @param tenantId     租户
     * @param rclNo        对账单号
     * @param carrierCode  承运商
     * @param period       账期
     * @param creatorId    创建人
     * @return 对账单
     */
    public static FnReconcileBillPo of(long id, long tenantId, String rclNo, String carrierCode, String period,
                                       Long creatorId) {
        FnReconcileBillPo po = new FnReconcileBillPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setRclNo(rclNo);
        po.setCarrierCode(carrierCode);
        po.setPeriod(period);
        // 状态由领域规则推导（ReconcileBillRule），此处先置「待确认」，汇总后由 Service 回写
        po.setStatus(ReconcileBillRule.STATUS_PENDING_CONFIRM);
        po.setTotalWaybills(0);
        po.setPlatformAmount(BigDecimal.ZERO);
        po.setCarrierAmount(BigDecimal.ZERO);
        po.setDiffAmount(BigDecimal.ZERO);
        po.setAutoSettledCount(0);
        po.setManualCount(0);
        po.setCreatedBy(creatorId);
        po.setDeleted(0);
        return po;
    }
}
