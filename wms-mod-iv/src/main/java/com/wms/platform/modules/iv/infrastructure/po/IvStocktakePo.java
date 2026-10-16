package com.wms.platform.modules.iv.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 盘点单（表 {@code iv_stocktake}，DDL 见 {@code 01} §3.15）。 */
@Getter
@Setter
@TableName(value = "iv_stocktake", autoResultMap = true)
public class IvStocktakePo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 盘点单号。 */
    private String stocktakeNo;

    /** 仓库。 */
    private Long warehouseId;

    /** 库区（可空）。 */
    private Long zoneId;

    /** 范围类型：全仓/库区/库位。 */
    private String scopeType;

    /** 状态：待录入/待审批/已审批/已驳回。 */
    private String status;

    /** 明细条数。 */
    private Integer itemCount;

    /** 有差异条数。 */
    private Integer diffCount;

    /** 创建人。 */
    private Long createdBy;

    /** 提交人。 */
    private Long submittedBy;

    /** 提交时间。 */
    private LocalDateTime submittedAt;

    /** 审批人。 */
    private Long approvedBy;

    /** 审批时间。 */
    private LocalDateTime approvedAt;

    /** 驳回原因。 */
    private String reason;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;

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
     * @param stocktakeNo  单号
     * @param warehouseId  仓库
     * @param zoneId       库区（可空）
     * @param scopeType    范围类型
     * @param creatorId    创建人
     * @return 盘点单
     */
    public static IvStocktakePo of(long id, long tenantId, String stocktakeNo, long warehouseId, Long zoneId,
                                   String scopeType, Long creatorId) {
        IvStocktakePo po = new IvStocktakePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setStocktakeNo(stocktakeNo);
        po.setWarehouseId(warehouseId);
        po.setZoneId(zoneId);
        po.setScopeType(scopeType);
        // 状态由本类内置为「待录入」：Service 不得直接 setStatus（01 §1.2 状态只能由状态机改）
        po.setStatus(com.wms.platform.modules.iv.domain.StocktakeRule.STATUS_DRAFT);
        po.setItemCount(0);
        po.setDiffCount(0);
        po.setCreatedBy(creatorId);
        po.setDeleted(0);
        return po;
    }
}
