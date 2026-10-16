package com.wms.platform.modules.ops.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 定时任务（表 {@code pf_scheduled_job}，{@code 01} §3.17）。
 *
 * <p>一行一个任务，既是**租约载体**也是**最近一次结果的载体**（运维看的就是这张表）。
 */
@Getter
@Setter
@TableName(value = "pf_scheduled_job", autoResultMap = true)
public class PfScheduledJobPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 任务名（唯一）。 */
    private String jobName;

    /** 中文名。 */
    private String displayName;

    /** 调度描述。 */
    private String cronDesc;

    /** 是否启用。 */
    private Integer enabled;

    /** 持锁实例（可空）。 */
    private String leaseOwner;

    /** 租约到期时间（可空）。 */
    private LocalDateTime leaseUntil;

    /** 上次结果：成功/失败/跳过。 */
    private String lastStatus;

    /** 上次开始时间。 */
    private LocalDateTime lastStartAt;

    /** 上次结束时间。 */
    private LocalDateTime lastEndAt;

    /** 上次结果摘要。 */
    private String lastMessage;

    /** 成功次数。 */
    private Integer successCount;

    /** 失败次数。 */
    private Integer failCount;

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
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param jobName     任务名
     * @param displayName 中文名
     * @param cronDesc    调度描述
     * @return 任务行
     */
    public static PfScheduledJobPo of(long id, long tenantId, String jobName, String displayName, String cronDesc) {
        PfScheduledJobPo po = new PfScheduledJobPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setJobName(jobName);
        po.setDisplayName(displayName);
        po.setCronDesc(cronDesc);
        po.setEnabled(1);
        po.setSuccessCount(0);
        po.setFailCount(0);
        po.setDeleted(0);
        return po;
    }
}
