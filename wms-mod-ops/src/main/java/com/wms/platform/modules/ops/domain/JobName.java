package com.wms.platform.modules.ops.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 定时任务清单（`01` §3.17）。
 *
 * <p><b>为什么任务名要枚举</b>：任务名是"运维看的标识"，也是租约的唯一键。
 * 若允许随便起名，同一件事可能被写成两个任务各跑一遍（比如 {@code FALLBACK_SWEEP} 与
 * {@code fallbackSweep}），而租约是按名字隔离的——**两个任务会各自拿锁、各跑一遍**，
 * 多实例防护直接失效。
 *
 * <p>任务的来源都可追溯：
 * <ul>
 *   <li>{@link #FALLBACK_SWEEP}、{@link #DAILY_RECONCILE}：`04` §5.2 与 `01` §1.2 第 8 行；</li>
 *   <li>{@link #RECONCILE_BILL_ALERT}：`04` §六「差额 → T+1 告警，T+3 内处理完毕」；</li>
 *   <li>{@link #SETTLE_STUCK_ALERT}：结算批次卡住 = 钱没付出去，必须有人知道；</li>
 *   <li>{@link #CHANNEL_QUERY_RETRY}：`04` §5.3「每 10 分钟扫描未回调并主动查询补正」；</li>
 *   <li>{@link #OUTBOX_RELAY}：P2 事务性发件箱的补投任务——没有它，崩溃期间的投递失败就永远丢了。</li>
 * </ul>
 */
public enum JobName {

    /** 兜底扣减扫描（每小时）。 */
    FALLBACK_SWEEP("兜底扣减扫描", "每小时"),

    /** 日终勾稽四层（每天 01:30 跑 T-1）。 */
    DAILY_RECONCILE("日终勾稽四层", "每天 01:30"),

    /** 对账差异超期告警（每天 09:00；制度要求 T+3 内处理完毕）。 */
    RECONCILE_BILL_ALERT("对账差异超期告警", "每天 09:00"),

    /** 结算批次卡单告警（每天 09:00）。 */
    SETTLE_STUCK_ALERT("结算批次卡单告警", "每天 09:00"),

    /** 通道未回调补查（每 10 分钟）。 */
    CHANNEL_QUERY_RETRY("通道未回调补查", "每 10 分钟"),

    /** 发件箱补投（每分钟）：兜住"立即投递失败"与"进程崩溃"两种情况，保证事件最终一定发出。 */
    OUTBOX_RELAY("发件箱补投", "每 1 分钟");

    /** 中文名。 */
    private final String displayName;

    /** 调度描述（真正生效的是配置里的 cron，这里仅用于展示与文档一致）。 */
    private final String cronDesc;

    JobName(String displayName, String cronDesc) {
        this.displayName = displayName;
        this.cronDesc = cronDesc;
    }

    /**
     * 中文名。
     *
     * @return 名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 调度描述。
     *
     * @return 描述
     */
    public String getCronDesc() {
        return cronDesc;
    }

    /**
     * 任务名（落库值）。
     *
     * @return 枚举名
     */
    public String code() {
        return name();
    }

    /**
     * 反查。
     *
     * @param value 任务名
     * @return 任务
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static JobName of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务名不能为空");
        }
        for (JobName job : values()) {
            if (job.name().equalsIgnoreCase(value.trim())) {
                return job;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "未知任务名：" + value + "（任务名是租约的唯一键，必须使用已登记的任务名）");
    }
}
