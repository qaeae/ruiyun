/**
 * 财务模型（03：
 * - 对账：API-FN-01~05 `finance/reconcile-bills`
 * - 结算批次：API-FN-06~08 `finance/settle-batches`
 * - 兜底扣减：`finance/fallback-deductions`
 * - 日终勾稽：`finance/daily-reconciliation`
 * - 垫付台账：`finance/advance-payments`
 * ）。
 *
 * 口径：
 * - 金额一律**BigDecimal 语义**（前端按字符串/数字原样传，不做浮点运算）；
 * - 对账差异档位（1 元 / 1% / 20%）= `ReconcileDiffRule`，前端只展示后端结论，不自己判档；
 * - **结算下发走双人复核**：接口返回复核单号，不是"已付款"。
 */
import { get, post } from '@/utils/request'

// ---------------------------------------------------------------------------
// 对账
// ---------------------------------------------------------------------------

/** 差异处理结论（与后端 `HandleResult` 一致）。 */
export type ReconcileHandleResult = '自动平账' | '挂账待处理' | '人工处理' | '责任方承担'

/** 责任方（与后端 `Liability` 一致）。 */
export type ReconcileLiability = '平台' | '商家' | '物流'

/** 差异类型（后端 `diffType` 的取值）。 */
export const ReconcileDiffType = {
  /** 完全一致。 */
  MATCHED: '完全一致',
  /** 平台无对应费用。 */
  NO_PLATFORM_FEE: '平台无对应费用',
  /** 超档差异。 */
  OVER_THRESHOLD: '超档差异',
} as const

/** 对账明细行。 */
export interface ReconcileItemVo {
  /** 明细 ID（处理差异用；雪花 ID 字符串）。 */
  id: string
  /** 运单号。 */
  waybillNo: string
  /** 平台计费。 */
  platformFee: string
  /** 承运商计费。 */
  carrierFee: string
  /** 差额。 */
  diffAmount: string
  /** 差异率（分母为平台费用）。 */
  diffRate: string
  /** 差异类型。 */
  diffType: string
  /** 处理结论。 */
  handleResult: string
  /** 责任方。 */
  liability: string
  /** 备注。 */
  remark: string
}

/** 对账单。 */
export interface ReconcileVo {
  /** 对账单号。 */
  rclNo: string
  /** 承运商编码。 */
  carrierCode: string
  /** 账期 yyyyMM。 */
  period: string
  /** 状态（差异待处理/已确认…）。 */
  status: string
  /** 运单数。 */
  totalWaybills: number
  /** 平台金额合计。 */
  platformAmount: string
  /** 承运商金额合计。 */
  carrierAmount: string
  /** 差额合计。 */
  diffAmount: string
  /** 自动平账条数。 */
  autoCount: number
  /** 人工处理条数。 */
  manualCount: number
  /** 明细（列表接口可能为空，详情接口才有）。 */
  items?: ReconcileItemVo[]
}

/** 对账明细入参。 */
export class ReconcileItemQo {
  /** 运单号。 */
  waybillNo = ''
  /** 商家。 */
  merchantId?: string
  /** 承运商金额。 */
  carrierFee = '0'

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const item: Record<string, unknown> = {
      waybillNo: this.waybillNo.trim(),
      carrierFee: this.carrierFee,
    }
    if (this.merchantId) {
      item.merchantId = this.merchantId
    }
    return item
  }
}

/** 创建对账单入参。 */
export class ReconcileCreateQo {
  /** 承运商编码。 */
  carrierCode = ''
  /** 账期 yyyyMM。 */
  period = ''
  /** 明细。 */
  items: ReconcileItemQo[] = []

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return {
      carrierCode: this.carrierCode.trim(),
      period: this.period.trim(),
      items: this.items.map((item) => item.toParams()),
    }
  }
}

/** 差异处理入参。 */
export class ReconcileHandleQo {
  /** 责任方。 */
  liability: ReconcileLiability = '平台'
  /** 处理说明。 */
  remark = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return { liability: this.liability, remark: this.remark.trim() }
  }
}

// ---------------------------------------------------------------------------
// 结算批次
// ---------------------------------------------------------------------------

/** 结算批次。 */
export interface SettleBatchVo {
  /** 批次号。 */
  stbNo: string
  /** 承运商。 */
  carrierCode: string
  /** 账期。 */
  period: string
  /** 状态（待下发/处理中/已成功/下发失败）。 */
  status: string
  /** 运单数。 */
  waybillCount: number
  /** 总金额。 */
  totalAmount: string
  /** 通道指令号（下发后回填）。 */
  instructionNo?: string
  /** 失败原因。 */
  failReason?: string
}

// ---------------------------------------------------------------------------
// 兜底扣减
// ---------------------------------------------------------------------------

/** 兜底候选（揽收后 7 天无回传）。 */
export interface FallbackCandidateVo {
  /** 运单号。 */
  waybillNo: string
  /** 商家。 */
  merchantId: string
  /** 预估费用。 */
  estimatedFee: string
  /** 揽收时间。 */
  collectedAt: string
  /** 是否已扣过（重复扫描时区分展示）。 */
  alreadyDeducted: boolean
  /** 说明。 */
  message: string
}

/** 多退少补调整结果。 */
export interface FallbackAdjustment {
  /** 调整金额（正数补收、负数退还）。 */
  adjustAmount: string
  /** 动作：REFUND/SUPPLEMENT/NONE/MANUAL。 */
  action: string
  /** 说明。 */
  message: string
}

// ---------------------------------------------------------------------------
// 日终勾稽（四层）
// ---------------------------------------------------------------------------

/** 勾稽层名（与后端一致）。 */
export const ReconcileLayer = {
  /** 资金层。 */
  FUND: '资金',
  /** 业务层。 */
  BIZ: '业务',
  /** 运营商层。 */
  CARRIER: '三方',
  /** 库存层。 */
  STOCK: '库存',
} as const

/** 勾稽状态。 */
export const DailyReconcileStatus = {
  /** 一致。 */
  MATCH: '一致',
  /** 有差异。 */
  DIFF: '有差异',
  /** 待接入（缺通道/承运商数据，**不计入差异告警**）。 */
  WAITING_CHANNEL: '待接入',
} as const

/** 日终勾稽记录。 */
export interface DailyReconciliationVo {
  /** 记录 ID（处理差异用；雪花 ID 字符串）。 */
  id: string
  /** 业务日期。 */
  bizDate: string
  /** 层。 */
  layer: string
  /** 状态。 */
  status: string
  /** 平台侧金额。 */
  platformAmount: string
  /** 通道/三方侧金额。 */
  channelAmount: string
  /** 差额。 */
  diffAmount: string
  /** 差异笔数。 */
  diffCount: number
  /** 明细 JSON。 */
  detailJson?: string
  /** 处理人。 */
  handledBy?: number
}

// ---------------------------------------------------------------------------
// 垫付台账
// ---------------------------------------------------------------------------

/** 垫付记录。 */
export interface AdvanceVo {
  /** 运单号。 */
  waybillNo: string
  /** 商家。 */
  merchantId: string
  /** 垫付金额。 */
  amount: string
  /** 状态。 */
  status: string
  /** 账期。 */
  billingPeriod: string
  /** 发生时间。 */
  occurredAt: string
  /** 核销时间。 */
  clearedAt?: string
  /** 备注。 */
  remark?: string
}

/** 垫付限额查询结果。 */
export interface AdvanceLimitVo {
  /** 商家。 */
  merchantId: string
  /** 累计垫付。 */
  cumulativeAdvance: string
  /** 连续垫付笔数。 */
  consecutiveCount: number
  /** 是否已触发限额（**触发后需人工审批**）。 */
  limited: boolean
  /** 累计金额上限。 */
  limitAmount: string
  /** 连续笔数上限。 */
  limitCount: number
  /** 说明。 */
  message: string
}

/** 垫付登记入参。 */
export class AdvanceRegisterQo {
  /** 运单号。 */
  waybillNo = ''
  /** 商家。 */
  merchantId?: string
  /** 冻结单号（可空）。 */
  freezeId?: string
  /** 金额。 */
  amount = '0'
  /** 账期。 */
  billingPeriod = ''
  /** 备注。 */
  remark = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      waybillNo: this.waybillNo.trim(),
      merchantId: Number(this.merchantId) || 0,
      amount: this.amount,
    }
    if (this.freezeId) {
      params.freezeId = this.freezeId
    }
    if (this.billingPeriod) {
      params.billingPeriod = this.billingPeriod.trim()
    }
    if (this.remark) {
      params.remark = this.remark.trim()
    }
    return params
  }
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

/** 对账接口。 */
export const ReconcileApi = {
  /**
   * 对账单列表。
   *
   * @param carrierCode 承运商（可空）
   * @param period 账期（可空）
   * @returns 列表
   */
  list(carrierCode?: string, period?: string): Promise<ReconcileVo[]> {
    const params: Record<string, unknown> = {}
    if (carrierCode) {
      params.carrierCode = carrierCode
    }
    if (period) {
      params.period = period
    }
    return get<ReconcileVo[]>('/api/v1/finance/reconcile-bills', params)
  },

  /**
   * 对账单详情（含明细）。
   *
   * @param rclNo 对账单号
   * @returns 详情
   */
  detail(rclNo: string): Promise<ReconcileVo> {
    return get<ReconcileVo>(`/api/v1/finance/reconcile-bills/${rclNo}`)
  },

  /**
   * 创建对账单（导入承运商账单）。
   *
   * @param qo 入参
   * @returns 对账单号
   */
  create(qo: ReconcileCreateQo): Promise<{ rclNo: string }> {
    return post<{ rclNo: string }>('/api/v1/finance/reconcile-bills', qo.toParams())
  },

  /**
   * 处理差异明细（责任方 + 说明）。
   *
   * @param rclNo 对账单号
   * @param itemId 明细 ID
   * @param qo 入参
   * @returns 处理结果
   */
  handleItem(rclNo: string, itemId: string, qo: ReconcileHandleQo): Promise<Record<string, unknown>> {
    return post<Record<string, unknown>>(
      `/api/v1/finance/reconcile-bills/${rclNo}/items/${itemId}/handle`,
      qo.toParams(),
    )
  },

  /**
   * 确认对账单（确认后不可再改差异）。
   *
   * @param rclNo 对账单号
   * @returns 无
   */
  confirm(rclNo: string): Promise<void> {
    return post<void>(`/api/v1/finance/reconcile-bills/${rclNo}/confirm`)
  },
}

/** 结算接口。 */
export const SettleApi = {
  /**
   * 由对账单生成结算批次。
   *
   * @param rclNo 对账单号
   * @returns 批次号
   */
  generate(rclNo: string): Promise<Record<string, unknown>> {
    return post<Record<string, unknown>>(
      `/api/v1/finance/settle-batches?rclNo=${encodeURIComponent(rclNo)}`,
    )
  },

  /**
   * 批次列表。
   *
   * @param carrierCode 承运商（可空）
   * @returns 列表
   */
  list(carrierCode?: string): Promise<SettleBatchVo[]> {
    return get<SettleBatchVo[]>(
      '/api/v1/finance/settle-batches',
      carrierCode ? { carrierCode } : undefined,
    )
  },

  /**
   * 批次详情。
   *
   * @param stbNo 批次号
   * @returns 详情
   */
  detail(stbNo: string): Promise<SettleBatchVo> {
    return get<SettleBatchVo>(`/api/v1/finance/settle-batches/${stbNo}`)
  },

  /**
   * 下发结算（**走双人复核**：返回复核单号，不是"已付款"）。
   *
   * @param stbNo 批次号
   * @returns 复核单号
   */
  dispatch(stbNo: string): Promise<{ approvalNo: string; message: string }> {
    return post<{ approvalNo: string; message: string }>(
      `/api/v1/finance/settle-batches/${stbNo}/dispatch`,
    )
  },
}

/** 兜底扣减接口。 */
export const FallbackApi = {
  /**
   * 扫描到期未回传的运单。
   *
   * @param days 天数（可空 = 服务端默认 7 天）
   * @returns 候选列表
   */
  sweep(days?: number): Promise<FallbackCandidateVo[]> {
    return post<FallbackCandidateVo[]>(
      `/api/v1/finance/fallback-deductions/sweep${days ? `?days=${days}` : ''}`,
    )
  },

  /**
   * 迟回传结算（多退少补）。
   *
   * @param waybillNo 运单号
   * @param actualFee 实际费用
   * @returns 调整结果
   */
  settleLateReturn(waybillNo: string, actualFee: string): Promise<FallbackAdjustment> {
    return post<FallbackAdjustment>(
      `/api/v1/finance/fallback-deductions/${waybillNo}/settle?actualFee=${encodeURIComponent(actualFee)}`,
    )
  },
}

/** 日终勾稽接口。 */
export const DailyReconciliationApi = {
  /**
   * 执行勾稽（幂等：同一天重跑覆盖同一天的结论）。
   *
   * @param bizDate 业务日期 yyyy-MM-dd
   * @returns 四层结果
   */
  run(bizDate: string): Promise<DailyReconciliationVo[]> {
    return post<DailyReconciliationVo[]>(
      `/api/v1/finance/daily-reconciliation/run?bizDate=${encodeURIComponent(bizDate)}`,
    )
  },

  /**
   * 查询某日勾稽结果。
   *
   * @param bizDate 业务日期 yyyy-MM-dd
   * @returns 四层结果
   */
  query(bizDate: string): Promise<DailyReconciliationVo[]> {
    return get<DailyReconciliationVo[]>(
      '/api/v1/finance/daily-reconciliation',
      { bizDate },
    )
  },

  /**
   * 处理差异（填写说明）。
   *
   * @param id 记录 ID
   * @param remark 处理说明
   * @returns 无
   */
  handle(id: string, remark: string): Promise<void> {
    return post<void>(
      `/api/v1/finance/daily-reconciliation/${id}/handle?remark=${encodeURIComponent(remark)}`,
    )
  },
}

/** 垫付接口。 */
export const AdvanceApi = {
  /**
   * 登记垫付。
   *
   * @param qo 入参
   * @returns 结果
   */
  register(qo: AdvanceRegisterQo): Promise<Record<string, unknown>> {
    return post<Record<string, unknown>>('/api/v1/finance/advance-payments', qo.toParams())
  },

  /**
   * 查限额（触发后需人工审批）。
   *
   * @param merchantId 商家
   * @returns 限额信息
   */
  limit(merchantId: string): Promise<AdvanceLimitVo> {
    return get<AdvanceLimitVo>('/api/v1/finance/advance-payments/limit', { merchantId })
  },

  /**
   * 垫付列表。
   *
   * @param merchantId 商家（可空）
   * @returns 列表
   */
  list(merchantId?: string): Promise<AdvanceVo[]> {
    return get<AdvanceVo[]>(
      '/api/v1/finance/advance-payments',
      merchantId ? { merchantId } : undefined,
    )
  },

  /**
   * 核销垫付。
   *
   * @param waybillNo 运单号
   * @returns 无
   */
  clear(waybillNo: string): Promise<void> {
    return post<void>(`/api/v1/finance/advance-payments/${waybillNo}/clear`)
  },
}

/** 兜底扣减状态文案。 */
export const FallbackDeductedLabel = {
  /** 已扣过（重复扫描时不会重复扣款）。 */
  DEDUCTED: '已扣过',
  /** 本次可扣。 */
  PENDING: '本次可扣',
} as const

/**
 * 兜底扣减状态文案。
 *
 * @param alreadyDeducted 是否已扣过
 * @returns 文案
 */
export function deductedLabel(alreadyDeducted: boolean): string {
  return alreadyDeducted ? FallbackDeductedLabel.DEDUCTED : FallbackDeductedLabel.PENDING
}
