package com.wms.platform.modules.pda.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PDA 补传冲突任务（表 {@code pf_pda_conflict}，DDL 见 {@code 01} §3.11）。
 *
 * <p>它是**工作流台账**而非审计日志：状态会从 `待人工处理` 变为 `已处理/已忽略`，
 * 这正是审计日志（追加型）表达不了的。
 */
@Getter
@Setter
@TableName(value = "pf_pda_conflict", autoResultMap = true)
public class PdaConflictPo {

    /** 待人工处理。 */
    public static final String STATUS_PENDING = "待人工处理";

    /** 已处理。 */
    public static final String STATUS_HANDLED = "已处理";

    /** 已忽略。 */
    public static final String STATUS_IGNORED = "已忽略";

    /** 主键（雪花 ID）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 幂等键（deviceCode:taskId:action:seq）。 */
    private String idemKey;

    /** 设备编号。 */
    private String deviceCode;

    /** 任务号。 */
    private String taskId;

    /** 动作。 */
    private String action;

    /** 序号。 */
    private Integer seq;

    /** 业务单号。 */
    private String bizNo;

    /** 商家 ID。 */
    private Long merchantId;

    /** 冲突类型：状态已流转/库存被占用/号码不可识别。 */
    private String conflictType;

    /** 服务端当前状态。 */
    private String serverStatus;

    /** 补传想推进到的状态。 */
    private String targetStatus;

    /** 原始补传内容（JSON，人工核对依据）。 */
    private String payloadJson;

    /** 处理状态。 */
    private String status;

    /** 说明。 */
    private String message;

    /** PDA 本地扫码时间。 */
    private LocalDateTime scannedAt;

    /** 服务端受理时间。 */
    private LocalDateTime occurredAt;

    /** 处理人。 */
    private Long handledBy;

    /** 处理时间。 */
    private LocalDateTime handledAt;

    /** 人工处理结论。 */
    private String handleResult;

    /** 备注。 */
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
     * 静态工厂：新冲突任务（B4）。
     *
     * @param id           雪花 ID
     * @param tenantId     租户 ID
     * @param idemKey      幂等键
     * @param deviceCode   设备编号
     * @param taskId       任务号
     * @param action       动作码
     * @param seq          序号
     * @param bizNo        业务单号
     * @param conflictType 冲突类型
     * @param serverStatus 服务端状态
     * @param targetStatus 目标状态
     * @param payloadJson  原始请求 JSON
     * @param message      说明
     * @param scannedAt    PDA 扫码时间
     * @return 待人工处理的冲突任务
     */
    public static PdaConflictPo of(long id, long tenantId, String idemKey, String deviceCode, String taskId,
                                   String action, int seq, String bizNo, String conflictType, String serverStatus,
                                   String targetStatus, String payloadJson, String message, LocalDateTime scannedAt) {
        PdaConflictPo po = new PdaConflictPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setIdemKey(idemKey);
        po.setDeviceCode(deviceCode);
        po.setTaskId(taskId);
        po.setAction(action);
        po.setSeq(seq);
        po.setBizNo(bizNo);
        po.setConflictType(conflictType);
        po.setServerStatus(serverStatus);
        po.setTargetStatus(targetStatus);
        po.setPayloadJson(payloadJson);
        po.setStatus(STATUS_PENDING);
        po.setMessage(message);
        po.setScannedAt(scannedAt);
        po.setOccurredAt(LocalDateTime.now());
        po.setDeleted(0);
        return po;
    }
}
