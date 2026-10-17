/**
 * 出库模型（03 §3.7：API-OUT-01…13，`/api/v1/outbound/orders`）。
 *
 * ## 口径（照抄后端，不再自作主张）
 *
 * | 项 | 取值 | 说明 |
 * |----|------|------|
 * | `status` | **9 个中文枚举** | 落库就是中文（`待接单`/`拣货中`/…），下拉与筛选必须用同一套字面量 |
 * | 雪花 ID | 字符串 | `warehouseId`/`merchantId`/`skuId`/`locationId` 一律按字符串透传（见 common.ts） |
 * | 金额/重量/体积 | 数字（BigDecimal） | 展示用 `formatAmount`/`formatQty`，**不做前端算术** |
 * | 收件人 | 只有掩码 | 后端只回 `nameMasked`/`phoneMasked`/`addressMasked`，明文不出接口 |
 * | 写操作 | 必须带 `Idempotency-Key` | `request.ts` 自动带；本层只负责把参数转成 `toParams()` |
 *
 * ## 状态机（01 §2.1，前端的按钮显隐**以这里为准**）
 *
 * 后端会把非法流转拒成 409 + `20002`，但"让用户看到注定失败的按钮"本身就是体验问题，
 * 所以把允许的动作集中在 {@link allowedActions} 一处：页面只按它渲染，不在模板里散写 `v-if="status === '…'"`。
 */
import { get, post } from '@/utils/request'
import { PageQo, type PageResult, type SnowflakeId } from '@/models/common'

// ---------------------------------------------------------------------------
// 字典与状态机口径
// ---------------------------------------------------------------------------

/** 出库单状态（与后端 `OutOrderStatus` 一一对应，**中文即协议值**）。 */
export const OutOrderStatus = {
  /** 待接单。 */
  PENDING: '待接单',
  /** 拣货中。 */
  PICKING: '拣货中',
  /** 缺货待处理。 */
  SHORTAGE: '缺货待处理',
  /** 已复核（拣货完成且称重量方过账）。 */
  CHECKED: '已复核',
  /** 已打包（面单已绑定）。 */
  PACKED: '已打包',
  /** 待装车（已扫码交接）。 */
  AWAITING_LOAD: '待装车',
  /** 已出库（库存已出账，不可逆）。 */
  SHIPPED: '已出库',
  /** 已取消。 */
  CANCELLED: '已取消',
  /** 已拦截（异常处理）。 */
  HELD: '已拦截',
} as const

/** 状态下拉选项（R7：选项来自模型常量，模板不硬编码）。 */
export const OUT_ORDER_STATUS_OPTIONS: string[] = Object.values(OutOrderStatus)

/** 来源类型（与后端 `out_order.source_type` 一致）。 */
export const OutOrderSourceType = {
  /** 上游系统下单。 */
  API: 'API',
  /** Excel 导入。 */
  EXCEL: 'EXCEL',
  /** 人工建单。 */
  MANUAL: 'MANUAL',
} as const

/** 来源类型选项。 */
export const OUT_ORDER_SOURCE_OPTIONS: string[] = Object.values(OutOrderSourceType)

/** 作业动作（前端按钮的标识，与后端路径动词一致）。 */
export type OutOrderAction =
  | 'dispatch'
  | 'pick'
  | 'shortage'
  | 'resume'
  | 'measure'
  | 'pack'
  | 'handover'
  | 'load'
  | 'cancel'
  | 'hold'
  | 'partial'

/** 动作需要的权限码（按钮显隐用；与后端 `PermissionCodes` 一一对应）。 */
export const OUT_ORDER_ACTION_PERM: Record<OutOrderAction, string> = {
  dispatch: 'outbound:order:dispatch',
  pick: 'outbound:order:pick',
  shortage: 'outbound:order:pick',
  resume: 'outbound:order:pick',
  measure: 'outbound:order:measure',
  pack: 'outbound:order:pack',
  handover: 'outbound:order:handover',
  load: 'outbound:order:load',
  cancel: 'outbound:order:cancel',
  hold: 'outbound:order:hold',
  partial: 'outbound:order:partial',
}

/** 动作文案（按钮标题；集中一处便于统一改口）。 */
export const OUT_ORDER_ACTION_LABEL: Record<OutOrderAction, string> = {
  dispatch: '派发拣货',
  pick: '拣货完成',
  shortage: '缺货上报',
  resume: '恢复拣货',
  measure: '称重/量方',
  pack: '打包绑面单',
  handover: '扫码交接',
  load: '装车确认',
  cancel: '取消',
  hold: '异常拦截',
  partial: '部分发货',
}

/**
 * 某状态下允许的动作（**唯一真源**，页面只按它渲染按钮）。
 *
 * @param status 当前状态（中文枚举）
 * @returns 允许的动作列表
 */
export function allowedActions(status: string): OutOrderAction[] {
  switch (status) {
    case OutOrderStatus.PENDING:
      return ['dispatch', 'cancel', 'hold']
    case OutOrderStatus.PICKING:
      return ['pick', 'shortage', 'cancel', 'hold']
    case OutOrderStatus.SHORTAGE:
      return ['resume', 'cancel', 'hold']
    case OutOrderStatus.CHECKED:
      return ['measure', 'pack', 'partial', 'cancel', 'hold']
    case OutOrderStatus.PACKED:
      return ['handover', 'partial', 'cancel', 'hold']
    case OutOrderStatus.AWAITING_LOAD:
      return ['load', 'partial', 'cancel', 'hold']
    default:
      return []
  }
}

/** el-tag 的类型取值（R8：字面量类型必须命名后引用，不在函数签名里内联联合）。 */
export type TagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

/**
 * 状态对应的标签色（展示用）。
 *
 * @param status 状态
 * @returns el-tag 的 type
 */
export function statusTagType(status: string): TagType {
  switch (status) {
    case OutOrderStatus.SHIPPED:
      return 'success'
    case OutOrderStatus.CANCELLED:
    case OutOrderStatus.HELD:
      return 'danger'
    case OutOrderStatus.SHORTAGE:
      return 'warning'
    case OutOrderStatus.PENDING:
      return 'info'
    default:
      return 'primary'
  }
}

/** 批次文案（R7：模板不写内联中文三元）。 */
export const SameDayLabel = {
  /** 截单前建单。 */
  SAME_DAY: '当日单',
  /** 截单后建单。 */
  NEXT_DAY: '次日单',
} as const

/**
 * 批次展示文案。
 *
 * @param isSameDay 是否当日单
 * @returns 文案
 */
export function sameDayLabel(isSameDay: boolean): string {
  return isSameDay ? SameDayLabel.SAME_DAY : SameDayLabel.NEXT_DAY
}

/** 交接状态文案（R7）。 */
export const HandoverLabel = {
  /** 已扫码交接。 */
  DONE: '已交接',
  /** 未扫码交接（此时不能装车）。 */
  PENDING: '未交接',
} as const

/**
 * 交接状态展示文案。
 *
 * @param confirmed 是否已交接
 * @returns 文案
 */
export function handoverLabel(confirmed: boolean): string {
  return confirmed ? HandoverLabel.DONE : HandoverLabel.PENDING
}

// ---------------------------------------------------------------------------
// Vo（后端返回，字段名与后端 record 完全一致）
// ---------------------------------------------------------------------------

/** 列表行（`OutOrderBriefVo`）。 */
export interface OutOrderVo {
  /** 主键（雪花字符串）。 */
  id: SnowflakeId
  /** 出库单号。 */
  doNo: string
  /** 状态（中文枚举）。 */
  status: string
  /** 仓库。 */
  warehouseId: SnowflakeId
  /** 商家。 */
  merchantId: SnowflakeId
  /** 承运商编码。 */
  carrierCode: string
  /** 是否当日单（按仓截单判定）。 */
  isSameDay: boolean
  /** 件数。 */
  packageCount: number | null
  /** 建单时间。 */
  createdAt: string
  /** 完成时间（已出库/已取消时有值）。 */
  finishedAt: string | null
}

/** 明细行（`OutOrderItemVo`）。 */
export interface OutOrderItemVo {
  /** 行号（从 1 开始；后续动作都按它定位）。 */
  lineNo: number
  /** 商品 ID。 */
  skuId: SnowflakeId
  /** 商品条码。 */
  skuCode: string
  /** 商品名称。 */
  skuName: string
  /** 应发数量。 */
  qty: number
  /** 实拣数量（拣货完成后有值）。 */
  pickedQty: number | null
  /** 拣货库位（派发后回填）。 */
  locationId: SnowflakeId | null
  /** 单位。 */
  unit: string
}

/** 收件人（**只有掩码**，后端不返回明文）。 */
export interface OutOrderReceiverVo {
  /** 姓名掩码。 */
  nameMasked: string
  /** 手机号掩码。 */
  phoneMasked: string
  /** 地址掩码。 */
  addressMasked: string
  /** 省。 */
  province: string | null
  /** 市。 */
  city: string | null
  /** 区。 */
  district: string | null
}

/** 详情（`OutOrderDetailVo`）。 */
export interface OutOrderDetailVo {
  /** 主键。 */
  id: SnowflakeId
  /** 出库单号。 */
  doNo: string
  /** 状态。 */
  status: string
  /** 仓库。 */
  warehouseId: SnowflakeId
  /** 商家。 */
  merchantId: SnowflakeId
  /** 来源类型。 */
  sourceType: string
  /** 上游订单号。 */
  sourceOrderNo: string | null
  /** 承运商编码。 */
  carrierCode: string
  /** 是否当日单。 */
  isSameDay: boolean
  /** 应收重量。 */
  expectedWeight: number | null
  /** 应收体积。 */
  expectedVolume: number | null
  /** 实称重量。 */
  actualWeight: number | null
  /** 实量体积。 */
  actualVolume: number | null
  /** 是否已完成称重量方。 */
  weightChecked: boolean
  /** 件数。 */
  packageCount: number | null
  /** 面单打印次数。 */
  labelPrintCount: number
  /** 是否已扫码交接。 */
  handoverConfirmed: boolean
  /** 收件人（掩码）。 */
  receiver: OutOrderReceiverVo
  /** 明细。 */
  items: OutOrderItemVo[]
  /** 取消时点（仅已取消有值）。 */
  cancelStage: string | null
  /** 取消时间。 */
  cancelledAt: string | null
  /** 完成时间。 */
  finishedAt: string | null
  /** 备注。 */
  remark: string | null
  /** 建单时间。 */
  createdAt: string
}

/** 状态轨迹行（`OutStatusLogVo`）。 */
export interface OutStatusLogVo {
  /** 迁移前状态（建单为 null）。 */
  fromStatus: string | null
  /** 迁移后状态。 */
  toStatus: string
  /** 事件名（CREATE/DISPATCH/…）。 */
  event: string
  /** 操作人。 */
  operatorId: SnowflakeId | null
  /** 操作人角色。 */
  operatorRole: string | null
  /** 设备编码。 */
  deviceCode: string | null
  /** 备注（含回滚计划说明）。 */
  remark: string | null
  /** 链路标识。 */
  traceId: string | null
  /** 幂等键。 */
  idemKey: string | null
  /** 发生时间。 */
  createdAt: string
}

/** 部分发货结果（`PartialShipVo`）。 */
export interface PartialShipVo {
  /** 原单号。 */
  originDoNo: string
  /** 本次出库数量。 */
  shippedQty: number
  /** 余量新单号。 */
  newDoNo: string
  /** 余量新单 ID。 */
  newDoId: SnowflakeId
  /** 余量数量。 */
  remainderQty: number
  /** 结果说明（可直接展示）。 */
  message: string
}

// ---------------------------------------------------------------------------
// Qo（请求入参：只放非空字段，R3）
// ---------------------------------------------------------------------------

/** 列表查询。 */
export class OutOrderQueryQo extends PageQo {
  /** 仓库（可空 = 不筛）。 */
  warehouseId?: string
  /** 商家（可空 = 不筛）。 */
  merchantId?: string
  /** 状态（中文枚举，可空）。 */
  status?: string
  /** 出库单号（精确匹配，可空）。 */
  doNo?: string

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  override toParams(): Record<string, unknown> {
    const params = super.toParams()
    if (this.warehouseId) {
      params.warehouseId = this.warehouseId
    }
    if (this.merchantId) {
      params.merchantId = this.merchantId
    }
    if (this.status) {
      params.status = this.status
    }
    if (this.doNo?.trim()) {
      params.doNo = this.doNo.trim()
    }
    return params
  }
}

/** 建单明细行。 */
export class CreateOutOrderItemQo {
  /** 商品条码（后端按条码解析主数据，不信任前端传 ID）。 */
  skuCode = ''
  /** 应发数量。 */
  qty = ''
  /** 单位（可空 = 用主数据的单位）。 */
  unit = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const item: Record<string, unknown> = { skuCode: this.skuCode.trim(), qty: this.qty }
    if (this.unit.trim()) {
      item.unit = this.unit.trim()
    }
    return item
  }
}

/** 建单入参。 */
export class CreateOutOrderQo {
  /** 仓库。 */
  warehouseId = ''
  /** 商家。 */
  merchantId = ''
  /** 来源类型。 */
  sourceType: string = OutOrderSourceType.API
  /** 上游订单号（API 来源必填，后端会做防重复下单）。 */
  sourceOrderNo = ''
  /** 承运商编码。 */
  carrierCode = ''
  /** 承运商名称。 */
  carrierName = ''
  /** 预估运费（建单即按此冻结，**不做前端试算**）。 */
  estimatedFreight = ''
  /** 应收重量（可空）。 */
  expectedWeight = ''
  /** 应收体积（可空）。 */
  expectedVolume = ''
  /** 收件人姓名。 */
  receiverName = ''
  /** 收件人电话。 */
  receiverPhone = ''
  /** 收件人地址。 */
  receiverAddress = ''
  /** 省。 */
  receiverProvince = ''
  /** 市。 */
  receiverCity = ''
  /** 区。 */
  receiverDistrict = ''
  /** 备注。 */
  remark = ''
  /** 明细。 */
  items: CreateOutOrderItemQo[] = [new CreateOutOrderItemQo()]

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      warehouseId: this.warehouseId,
      merchantId: this.merchantId,
      sourceType: this.sourceType,
      carrierCode: this.carrierCode.trim(),
      estimatedFreight: this.estimatedFreight,
      receiverName: this.receiverName.trim(),
      receiverPhone: this.receiverPhone.trim(),
      receiverAddress: this.receiverAddress.trim(),
      items: this.items.map((item) => item.toParams()),
    }
    if (this.sourceOrderNo.trim()) {
      params.sourceOrderNo = this.sourceOrderNo.trim()
    }
    if (this.carrierName.trim()) {
      params.carrierName = this.carrierName.trim()
    }
    if (this.expectedWeight) {
      params.expectedWeight = this.expectedWeight
    }
    if (this.expectedVolume) {
      params.expectedVolume = this.expectedVolume
    }
    if (this.receiverProvince.trim()) {
      params.receiverProvince = this.receiverProvince.trim()
    }
    if (this.receiverCity.trim()) {
      params.receiverCity = this.receiverCity.trim()
    }
    if (this.receiverDistrict.trim()) {
      params.receiverDistrict = this.receiverDistrict.trim()
    }
    if (this.remark.trim()) {
      params.remark = this.remark.trim()
    }
    return params
  }
}

/** 作业行入参（派发/拣货/缺货/部分发货共用：行号 + 一个数值或编码）。 */
export class OutOrderLineQo {
  /** 行号（对应明细的 lineNo）。 */
  lineNo = 0
  /** 值（派发=库位编码；拣货=实拣量；缺货=缺货量；拆单=发货量）。 */
  value = ''

  /**
   * 构造。
   *
   * @param lineNo 行号
   * @param value 值
   */
  constructor(lineNo = 0, value = '') {
    this.lineNo = lineNo
    this.value = value
  }
}

/** 作业弹窗的行输入（派发/拣货/缺货/拆单共用；value 是库位编码或数量）。 */
export interface OutOrderLineInput {
  /** 行号（对应明细 lineNo）。 */
  lineNo: number
  /** 输入值。 */
  value: string
}

/** 称重/量方入参。 */
export class MeasureQo {
  /** 实称重量（kg）。 */
  actualWeight = ''
  /** 实量体积（m³，可空）。 */
  actualVolume = ''
  /** 件数（可空）。 */
  packageCount = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = { actualWeight: this.actualWeight }
    if (this.actualVolume) {
      params.actualVolume = this.actualVolume
    }
    if (this.packageCount) {
      params.packageCount = Number(this.packageCount)
    }
    return params
  }
}

/** 打包入参。 */
export class PackQo {
  /** 运单号（扫面单）。 */
  waybillNo = ''
  /** 件数（可空）。 */
  packageCount = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = { waybillNo: this.waybillNo.trim() }
    if (this.packageCount) {
      params.packageCount = Number(this.packageCount)
    }
    return params
  }
}

/** 说明类入参（交接/装车/取消/拦截共用：只有一个必填或可空的说明）。 */
export class RemarkQo {
  /** 说明。 */
  remark = ''

  /**
   * 构造。
   *
   * @param remark 说明
   */
  constructor(remark = '') {
    this.remark = remark
  }

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return { remark: this.remark.trim() }
  }
}

/** 取消/拦截入参（原因必填）。 */
export class ReasonQo {
  /** 原因。 */
  reason = ''

  /**
   * 构造。
   *
   * @param reason 原因
   */
  constructor(reason = '') {
    this.reason = reason
  }

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return { reason: this.reason.trim() }
  }
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

/** 出库接口。 */
export const OutboundApi = {
  /**
   * 分页查询（API-OUT-13）。
   *
   * @param qo 查询条件
   * @returns 分页结果
   */
  page(qo: OutOrderQueryQo): Promise<PageResult<OutOrderVo>> {
    return get<PageResult<OutOrderVo>>('/api/v1/outbound/orders', qo.toParams())
  },

  /**
   * 详情（API-OUT-13）。
   *
   * @param doNo 出库单号
   * @returns 详情
   */
  detail(doNo: string): Promise<OutOrderDetailVo> {
    return get<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}`)
  },

  /**
   * 状态轨迹（API-OUT-13）。
   *
   * @param doNo 出库单号
   * @returns 轨迹列表
   */
  statusLogs(doNo: string): Promise<OutStatusLogVo[]> {
    return get<OutStatusLogVo[]>(`/api/v1/outbound/orders/${doNo}/status-logs`)
  },

  /**
   * 建单（API-OUT-01，含预占 + 运费冻结）。
   *
   * @param qo 入参
   * @returns 详情
   */
  create(qo: CreateOutOrderQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>('/api/v1/outbound/orders', qo.toParams())
  },

  /**
   * 派发拣货（API-OUT-02）。
   *
   * @param doNo 出库单号
   * @param lines 行号 + 库位编码
   * @returns 详情
   */
  dispatch(doNo: string, lines: OutOrderLineQo[]): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/dispatch`, {
      items: lines.map((line) => ({ lineNo: line.lineNo, locationCode: line.value.trim() })),
    })
  },

  /**
   * 拣货完成（API-OUT-03，实拣必须等于应发）。
   *
   * @param doNo 出库单号
   * @param lines 行号 + 实拣数量
   * @returns 详情
   */
  pickComplete(doNo: string, lines: OutOrderLineQo[]): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/pick-complete`, {
      items: lines.map((line) => ({ lineNo: line.lineNo, pickedQty: line.value })),
    })
  },

  /**
   * 缺货上报（API-OUT-04，差额释放预占）。
   *
   * @param doNo 出库单号
   * @param lines 行号 + 缺货数量
   * @param reason 缺货原因
   * @returns 详情
   */
  shortage(doNo: string, lines: OutOrderLineQo[], reason: string): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/shortage`, {
      items: lines.map((line) => ({ lineNo: line.lineNo, shortageQty: line.value })),
      reason,
    })
  },

  /**
   * 恢复拣货（API-OUT-05，无入参）。
   *
   * @param doNo 出库单号
   * @returns 详情
   */
  resumePicking(doNo: string): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/resume-picking`, {})
  },

  /**
   * 称重/量方（API-OUT-06，偏差 > 20% 会被后端拦下）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  measure(doNo: string, qo: MeasureQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/measure`, qo.toParams())
  },

  /**
   * 打包 + 绑面单（API-OUT-07）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  pack(doNo: string, qo: PackQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/pack`, qo.toParams())
  },

  /**
   * 扫码交接（API-OUT-08）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  handover(doNo: string, qo: RemarkQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/handover`, qo.toParams())
  },

  /**
   * 装车确认（API-OUT-09，库存出账、不可逆）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  load(doNo: string, qo: RemarkQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/load`, qo.toParams())
  },

  /**
   * 取消（API-OUT-10，按回滚矩阵处理）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  cancel(doNo: string, qo: ReasonQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/cancel`, qo.toParams())
  },

  /**
   * 异常拦截（API-OUT-11）。
   *
   * @param doNo 出库单号
   * @param qo 入参
   * @returns 详情
   */
  hold(doNo: string, qo: ReasonQo): Promise<OutOrderDetailVo> {
    return post<OutOrderDetailVo>(`/api/v1/outbound/orders/${doNo}/hold`, qo.toParams())
  },

  /**
   * 部分发货（API-OUT-12，余量生成新单）。
   *
   * @param doNo 出库单号
   * @param lines 行号 + 本次发货量
   * @param reason 拆单原因
   * @returns 拆单结果
   */
  partialShip(doNo: string, lines: OutOrderLineQo[], reason: string): Promise<PartialShipVo> {
    return post<PartialShipVo>(`/api/v1/outbound/orders/${doNo}/partial-ship`, {
      items: lines.map((line) => ({ lineNo: line.lineNo, shipQty: line.value })),
      reason,
    })
  },
}
