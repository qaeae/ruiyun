package com.wms.platform.modules.outbound.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 单据状态流水（表 {@code pf_biz_status_log}，DDL 见 {@code 01} §3.8）。
 *
 * <p><b>为什么状态流水不只是"日志"</b>：它的唯一键 {@code uk_status_idem(tenant_id, biz_type, idem_key)}
 * 是**状态迁移的幂等防线**——重复的装车扫码、重放的取消请求，都会在这里撞键而被拦下。
 * 因此每次状态迁移都必须写一行，且 {@code idemKey} 必须是"业务键 + 事件"（如 {@code <doId>+LOAD}），
 * 不能是随机值（随机值等于没有防线）。
 *
 * <p>{@code traceId} 一并落库：出库是长链路作业（建单→装车可能横跨数小时、多个操作人），
 * 出问题时要能按 traceId 把接口日志、库存流水、状态流水串起来（P4 的全链路追踪）。
 */
@Getter
@Setter
@TableName(value = "pf_biz_status_log", autoResultMap = true)
public class BizStatusLogPo {

    /** 业务类型：出库单。 */
    public static final String BIZ_TYPE_DO = "DO";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 业务类型：DO/SHIPMENT/ASN/FEE/FREEZE/RECONCILE。 */
    private String bizType;

    /** 业务主键（如出库单 ID）。 */
    private Long bizId;

    /** 业务单号（如 DO 号）。 */
    private String bizNo;

    /** 迁移前状态（建单时为空）。 */
    private String fromStatus;

    /** 迁移后状态。 */
    private String toStatus;

    /** 事件名（如 LOAD）。 */
    private String event;

    /** 操作人。 */
    private Long operatorId;

    /** 操作人角色（可空）。 */
    private String operatorRole;

    /** 设备编号（PDA/PC/扫码枪）。 */
    private String deviceCode;

    /** 幂等键（{@code bizType} 内唯一，状态迁移幂等防线）。 */
    private String idemKey;

    /** 备注（取消回滚计划说明、异常原因等）。 */
    private String remark;

    /** 全链路追踪 ID。 */
    private String traceId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除：0 未删、1 已删。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂：一条状态迁移流水（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户 ID
     * @param bizId       业务主键
     * @param bizNo       业务单号
     * @param fromStatus  迁移前状态（可空）
     * @param toStatus    迁移后状态
     * @param event       事件名
     * @param operatorId  操作人（可空）
     * @param deviceCode  设备编号（可空）
     * @param idemKey     幂等键（必填：它是唯一防线）
     * @param remark      备注（可空）
     * @param traceId     追踪 ID（可空）
     * @return 状态流水
     */
    public static BizStatusLogPo of(long id, long tenantId, long bizId, String bizNo, String fromStatus,
                                    String toStatus, String event, Long operatorId, String deviceCode,
                                    String idemKey, String remark, String traceId) {
        BizStatusLogPo po = new BizStatusLogPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setBizType(BIZ_TYPE_DO);
        po.setBizId(bizId);
        po.setBizNo(bizNo);
        po.setFromStatus(fromStatus);
        po.setToStatus(toStatus);
        po.setEvent(event);
        po.setOperatorId(operatorId);
        po.setDeviceCode(deviceCode);
        po.setIdemKey(idemKey);
        po.setRemark(remark);
        po.setTraceId(traceId);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }
}
