/**
 * 双人复核模型（03 API-AUTH-04/05）。
 *
 * 界面口径（前置包 §4.4）：
 * - 详情**必须展示 `payloadJson`**（审批内容快照）——复核人据此判断要批的是哪一份内容；
 * - 通过**不传复核人 ID**（后端取自令牌身份，服务端强校验发起人 ≠ 复核人）；
 * - 自己发起的单不能自审：界面上禁用按钮并给出原因。
 */
import { get, post } from '@/utils/request'

/** 复核业务类型（与后端 `ApprovalBizType` 枚举一致）。 */
export type ApprovalBizType =
  | 'STOCK_ADJUST'
  | 'BILL_REVERSE'
  | 'REFUND'
  | 'DEPOSIT_REFUND'
  | 'PRICE_CHANGE'
  | 'DIFF_RELEASE'
  | 'SETTLE_DISPATCH'
  | 'ADMIN_GRANT'

/** 业务类型中文名（界面展示用；**不要**在模板里硬编码文案，R7）。 */
export const ApprovalBizTypeLabel: Record<ApprovalBizType, string> = {
  STOCK_ADJUST: '库存调整',
  BILL_REVERSE: '账单冲正',
  REFUND: '退款',
  DEPOSIT_REFUND: '押金退还',
  PRICE_CHANGE: '调价',
  DIFF_RELEASE: '差异放行',
  SETTLE_DISPATCH: '结算下发',
  ADMIN_GRANT: '超管授权',
}

/** 复核单。 */
export interface ApprovalVo {
  /** 记录 ID（雪花 ID 字符串）。 */
  id: string
  /** 审批单号。 */
  approvalNo: string
  /** 业务类型。 */
  bizType: ApprovalBizType
  /** 业务类型中文。 */
  bizTypeLabel: string
  /** 业务对象 ID。 */
  bizId?: string
  /** 业务单号。 */
  bizNo?: string
  /** 涉及金额。 */
  amount?: string
  /** **审批内容快照**（执行时以它为准）。 */
  payloadJson: string
  /** 申请事由。 */
  reason: string
  /** 状态：待复核/已通过/已驳回/已撤回。 */
  status: string
  /** 发起人。 */
  applyBy: string
  /** 发起时间。 */
  applyAt: string
  /** 复核人。 */
  approveBy?: string
  /** 复核时间。 */
  approveAt?: string
  /** 驳回原因。 */
  rejectReason?: string
  /** 副作用是否已执行。 */
  executed: boolean
}

/** 提交复核入参。 */
export class ApprovalSubmitQo {
  /** 业务类型。 */
  bizType: ApprovalBizType = 'STOCK_ADJUST'
  /** 业务对象 ID。 */
  bizId?: string
  /** 业务单号。 */
  bizNo?: string
  /** 仓库。 */
  warehouseId?: string
  /** 商家。 */
  merchantId?: string
  /** 涉及金额。 */
  amount?: string
  /** 审批内容快照（JSON 字符串）。 */
  payloadJson = ''
  /** 申请事由。 */
  reason = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      bizType: this.bizType,
      payloadJson: this.payloadJson,
      reason: this.reason.trim(),
    }
    if (this.bizId) {
      params.bizId = this.bizId
    }
    if (this.bizNo) {
      params.bizNo = this.bizNo
    }
    if (this.warehouseId) {
      params.warehouseId = this.warehouseId
    }
    if (this.merchantId) {
      params.merchantId = this.merchantId
    }
    if (this.amount) {
      params.amount = this.amount
    }
    return params
  }
}

/** 复核接口集合。 */
export const ApprovalApi = {
  /**
   * 待复核列表（按发起时间正序：先提交的先处理）。
   *
   * @param bizType 业务类型（可空）
   * @returns 列表
   */
  pending(bizType?: ApprovalBizType): Promise<ApprovalVo[]> {
    return get<ApprovalVo[]>('/api/v1/approvals/pending', bizType ? { bizType } : undefined)
  },

  /**
   * 我发起的申请。
   *
   * @returns 列表
   */
  mine(): Promise<ApprovalVo[]> {
    return get<ApprovalVo[]>('/api/v1/approvals/mine')
  },

  /**
   * 详情（含内容快照）。
   *
   * @param id 记录 ID
   * @returns 详情
   */
  detail(id: string): Promise<ApprovalVo> {
    return get<ApprovalVo>(`/api/v1/approvals/${id}`)
  },

  /**
   * 复核通过（**不传复核人**：后端取自令牌）。
   *
   * @param id 记录 ID
   * @returns 审批单号
   */
  approve(id: string): Promise<{ approvalNo: string }> {
    return post<{ approvalNo: string }>(`/api/v1/approvals/${id}/approve`)
  },

  /**
   * 驳回（原因必填）。
   *
   * @param id 记录 ID
   * @param reason 驳回原因
   * @returns 无
   */
  reject(id: string, reason: string): Promise<void> {
    return post<void>(`/api/v1/approvals/${id}/reject`, { reason })
  },

  /**
   * 撤回（仅发起人本人）。
   *
   * @param id 记录 ID
   * @returns 无
   */
  withdraw(id: string): Promise<void> {
    return post<void>(`/api/v1/approvals/${id}/withdraw`)
  },
}
