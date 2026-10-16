package com.wms.platform.modules.pda.api.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 待人工处理的补传冲突（03 API-PDA-03 {@code GET /pda/conflicts}）。
 *
 * <p>同时给出现场视角（设备/任务/动作）与处理视角（服务端状态 vs 目标状态），
 * 让库管不必回数据库查就能判断"该采纳 PDA 还是维持服务端"。
 */
@Getter
@Setter
public class PdaConflictVo {

    /** 幂等键（deviceCode:taskId:action:seq）。 */
    private String idemKey;

    /** 设备编号。 */
    private String deviceCode;

    /** 任务号。 */
    private String taskId;

    /** 动作。 */
    private String action;

    /** 序号。 */
    private int seq;

    /** 业务单号。 */
    private String bizNo;

    /** 冲突类型：状态已流转/库存被占用/号码不可识别。 */
    private String conflictType;

    /** 服务端当前状态。 */
    private String serverStatus;

    /** 补传想推进到的状态。 */
    private String targetStatus;

    /** 说明。 */
    private String message;

    /** PDA 本地扫码时间。 */
    private LocalDateTime scannedAt;

    /** 服务端受理时间。 */
    private LocalDateTime occurredAt;

    /** 处理状态：待人工处理/已处理/已忽略。 */
    private String status;
}
