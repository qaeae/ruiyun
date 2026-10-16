package com.wms.platform.modules.ops.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务执行历史（表 {@code pf_job_run}，{@code 01} §3.17）。
 *
 * <p>{@code triggerType} 区分「定时」与「手工补跑」：两者走同一段代码，只在留痕上区分。
 * 这个区分在排查时很关键——"补跑能成功、定时总失败"往往指向环境/时序问题，而不是任务逻辑本身。
 */
@Getter
@Setter
@TableName(value = "pf_job_run", autoResultMap = true)
public class PfJobRunPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 任务名。 */
    private String jobName;

    /** 触发方式：SCHEDULE/MANUAL。 */
    private String triggerType;

    /** 结果：成功/失败/跳过。 */
    private String status;

    /** 开始时间。 */
    private LocalDateTime startedAt;

    /** 结束时间。 */
    private LocalDateTime endedAt;

    /** 耗时（毫秒）。 */
    private Long costMs;

    /** 结果摘要或异常信息。 */
    private String message;

    /** 手工补跑的操作人（定时为空）。 */
    private Long operatorId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param jobName     任务名
     * @param triggerType 触发方式
     * @param startedAt   开始时间
     * @param operatorId  操作人（定时为空）
     * @return 执行记录
     */
    public static PfJobRunPo of(long id, long tenantId, String jobName, String triggerType, LocalDateTime startedAt,
                                Long operatorId) {
        PfJobRunPo po = new PfJobRunPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setJobName(jobName);
        po.setTriggerType(triggerType);
        po.setStartedAt(startedAt);
        po.setOperatorId(operatorId);
        // 初始状态「执行中」：`status` 是 NOT NULL，且"有开始没结束"的记录本身就是**卡死/被杀**的痕迹
        // （若插入时留空，要么插入失败，要么只能记成"成功/失败"，两种情况都会掩盖"任务卡住"）
        po.setStatus("执行中");
        po.setDeleted(0);
        return po;
    }

    /**
     * 收尾（写入结果与耗时）。
     *
     * @param status  结果
     * @param endedAt 结束时间
     * @param costMs  耗时
     * @param message 摘要
     */
    public void finish(String status, LocalDateTime endedAt, long costMs, String message) {
        this.status = status;
        this.endedAt = endedAt;
        this.costMs = costMs;
        this.message = message;
    }
}
