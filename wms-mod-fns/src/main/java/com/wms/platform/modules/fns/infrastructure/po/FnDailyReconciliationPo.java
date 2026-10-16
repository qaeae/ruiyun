package com.wms.platform.modules.fns.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日终勾稽记录（表 {@code fn_daily_reconciliation}，{@code 04} §5.2）。
 *
 * <p>唯一键 {@code uk_daily_layer(tenant_id, biz_date, layer)}：**同一天同一层只有一条记录**，
 * 重复执行勾稽是幂等的（补跑、重跑不会产生第二份结论）。
 */
@Getter
@Setter
@TableName(value = "fn_daily_reconciliation", autoResultMap = true)
public class FnDailyReconciliationPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 业务日期。 */
    private LocalDate bizDate;

    /** 层：FUND/FEE/CARRIER/INVENTORY。 */
    private String layer;

    /** 平台侧金额。 */
    private BigDecimal platformAmount;

    /** 对方侧金额。 */
    private BigDecimal channelAmount;

    /** 差额。 */
    private BigDecimal diffAmount;

    /** 差异笔数。 */
    private Integer diffCount;

    /** 状态：一致/有差异/待接入/已处理。 */
    private String status;

    /** 差异明细（JSON）。 */
    private String detailJson;

    /** 处理人。 */
    private Long handledBy;

    /** 处理时间。 */
    private LocalDateTime handledAt;

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
     * @param id       雪花 ID
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @param layer    层
     * @return 记录
     */
    public static FnDailyReconciliationPo of(long id, long tenantId, LocalDate bizDate, String layer) {
        FnDailyReconciliationPo po = new FnDailyReconciliationPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setBizDate(bizDate);
        po.setLayer(layer);
        po.setPlatformAmount(BigDecimal.ZERO);
        po.setChannelAmount(BigDecimal.ZERO);
        po.setDiffAmount(BigDecimal.ZERO);
        po.setDiffCount(0);
        po.setDeleted(0);
        return po;
    }

    /**
     * 写入本次勾稽结论（**状态只能经这里改**，`01` §1.2）。
     *
     * <p>把赋值收口到 Po 而不是让 Service 逐个 set：
     * 一是符合"状态只能由状态机改"的约定（勾稽层就是本表的状态机），
     * 二是避免"改了金额忘了改状态"这类漏项——它们必须一起变才有意义。
     *
     * @param platformAmount 平台侧金额
     * @param channelAmount  对方侧金额
     * @param diffAmount     差额
     * @param diffCount      差异笔数
     * @param status         状态
     * @param detailJson     差异明细
     * @param operatorId     操作人
     */
    public void applyResult(BigDecimal platformAmount, BigDecimal channelAmount, BigDecimal diffAmount,
                            int diffCount, String status, String detailJson, Long operatorId) {
        this.platformAmount = platformAmount;
        this.channelAmount = channelAmount;
        this.diffAmount = diffAmount;
        this.diffCount = diffCount;
        this.status = status;
        this.detailJson = detailJson;
        this.updatedBy = operatorId;
    }
}
