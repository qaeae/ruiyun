package com.wms.platform.modules.pda.api.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 单条补传结果（03 §七 响应 {@code results[]} 的元素）。
 *
 * <p>{@code serverStatus} 是刻意保留的：PDA 展示"服务端当前是已出库"比只显示 conflict
 * 更能让现场自己判断该怎么办（是重扫、还是找库管）。
 */
@Getter
@Setter
public class PdaSyncResultVo {

    /** 任务号。 */
    private String taskId;

    /** 序号。 */
    private int seq;

    /** 逐条结果：accepted/duplicated/conflict/rejected。 */
    private String status;

    /** 服务端当前状态（冲突时最有价值）。 */
    private String serverStatus;

    /** 说明（中文）。 */
    private String message;

    /**
     * 静态工厂（B4）。
     *
     * @param taskId       任务号
     * @param seq          序号
     * @param status       结果码
     * @param serverStatus 服务端状态
     * @param message      说明
     * @return 补传结果
     */
    public static PdaSyncResultVo of(String taskId, int seq, String status, String serverStatus, String message) {
        PdaSyncResultVo vo = new PdaSyncResultVo();
        vo.setTaskId(taskId);
        vo.setSeq(seq);
        vo.setStatus(status);
        vo.setServerStatus(serverStatus);
        vo.setMessage(message);
        return vo;
    }
}
