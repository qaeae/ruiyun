/**
 * 定时任务模型（03 API-OPS-01）。
 *
 * 界面口径（前置包 §4.5）：
 * - **「跳过」不是失败**（多实例下每周期一半实例会跳过，记成失败会造成告警疲劳）；
 * - `lastStatus` 停留在「执行中」= 任务卡死或被杀（先写开始、结束再更新留下的痕迹）；
 * - 补跑与定时**共用同一段代码**（含租约），因此补跑成功基本等价于定时能成功。
 */
import { get, post } from '@/utils/request'

/** 任务状态取值（后端落库值）。 */
export const JobStatus = {
  /** 执行中（**停留在该状态 = 卡死/被杀**）。 */
  RUNNING: '执行中',
  /** 成功。 */
  SUCCESS: '成功',
  /** 失败（任务幂等，可补跑）。 */
  FAILED: '失败',
  /** 跳过（未抢到租约 / 已停用，**不是错误**）。 */
  SKIPPED: '跳过',
} as const

/** 触发方式。 */
export const TriggerType = {
  /** 定时。 */
  SCHEDULE: 'SCHEDULE',
  /** 手工补跑。 */
  MANUAL: 'MANUAL',
} as const

/** 任务视图。 */
export interface JobVo {
  /** 任务名（补跑用）。 */
  jobName: string
  /** 中文名。 */
  displayName: string
  /** 调度描述。 */
  cronDesc: string
  /** 是否启用。 */
  enabled: boolean
  /** 当前持锁实例（非空 = 正在执行）。 */
  leaseOwner?: string
  /** 租约到期时间。 */
  leaseUntil?: string
  /** 上次结果。 */
  lastStatus?: string
  /** 上次开始。 */
  lastStartAt?: string
  /** 上次结束。 */
  lastEndAt?: string
  /** 上次摘要。 */
  lastMessage?: string
  /** 成功次数。 */
  successCount: number
  /** 失败次数。 */
  failCount: number
}

/** 执行记录。 */
export interface JobRunVo {
  /** 记录 ID（雪花 ID 字符串）。 */
  id: string
  /** 任务名。 */
  jobName: string
  /** 触发方式。 */
  triggerType: string
  /** 结果。 */
  status: string
  /** 开始时间。 */
  startedAt: string
  /** 结束时间。 */
  endedAt?: string
  /** 耗时（毫秒）。 */
  costMs?: number
  /** 摘要或异常信息。 */
  message?: string
  /** 手工补跑的操作人。 */
  operatorId?: string
}

/** 任务运维接口。 */
export const JobApi = {
  /**
   * 任务一览。
   *
   * @returns 列表
   */
  list(): Promise<JobVo[]> {
    return get<JobVo[]>('/api/v1/jobs')
  },

  /**
   * 执行历史。
   *
   * @param jobName 任务名（可空 = 全部）
   * @param limit 条数
   * @returns 列表
   */
  runs(jobName?: string, limit = 50): Promise<JobRunVo[]> {
    const params: Record<string, unknown> = { limit }
    if (jobName) {
      params.jobName = jobName
    }
    return get<JobRunVo[]>('/api/v1/jobs/runs', params)
  },

  /**
   * 手工补跑（与定时同路径，含租约保护）。
   *
   * @param jobName 任务名
   * @returns 本次执行记录
   */
  run(jobName: string): Promise<JobRunVo> {
    return post<JobRunVo>(`/api/v1/jobs/${jobName}/run`)
  },

  /**
   * 启停任务。
   *
   * @param jobName 任务名
   * @param enabled 是否启用
   * @returns 无
   */
  setEnabled(jobName: string, enabled: boolean): Promise<void> {
    return post<void>(`/api/v1/jobs/${jobName}/enabled?enabled=${enabled}`)
  },
}

// ---------------------------------------------------------------------------
// 展示映射（R7：模板不写中文三元）
// ---------------------------------------------------------------------------

/** 任务启用状态文案。 */
export const JobEnabledLabel = {
  /** 启用。 */
  ENABLED: '启用',
  /** 停用。 */
  DISABLED: '停用',
} as const

/**
 * 任务启用状态文案。
 *
 * @param enabled 是否启用
 * @returns 文案
 */
export function jobEnabledLabel(enabled: boolean): string {
  return enabled ? JobEnabledLabel.ENABLED : JobEnabledLabel.DISABLED
}

/**
 * 任务启停按钮文案（给出"下一步动作"）。
 *
 * @param enabled 是否启用
 * @returns 按钮文案
 */
export function jobToggleLabel(enabled: boolean): string {
  return enabled ? JobEnabledLabel.DISABLED : JobEnabledLabel.ENABLED
}
