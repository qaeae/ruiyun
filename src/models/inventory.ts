/**
 * 库存模型（03 API-IV-01/02/04/05/06）。
 *
 * ## 联调确认的真实契约（2026-10-14 实测，与最初的猜测**不同**）
 *
 * | 接口 | 真实形态 |
 * |------|---------|
 * | `GET /inventory/sku` | **必填 `warehouseId`+`merchantId`+`skuId`**，返回**单个** `StockVo`（**不是分页列表**） |
 * | `GET /inventory/locations` | 必填 `warehouseId`+`skuId`，返回 `List` |
 * | `GET /inventory/movements` | `warehouseId`/`skuId`/`bizType`/`bizNo`/`limit`，返回 `List`（无分页） |
 * | `POST /inventory/internal/*` | 数量是**数字**，ID 是**字符串** |
 *
 * 所以"库存查询"页的交互不是"列表 + 筛选"，而是"**定位到一个 SKU，看它的三态、库位分布与流水**"——
 * 这才是库存管理的真实用法：先知道要查哪个商品，再看它的账。
 */
import { get, post } from '@/utils/request'
import type { SnowflakeId } from '@/models/common'

/** 库存三态（SKU 级）。 */
export interface StockVo {
  /** 仓库。 */
  warehouseId: SnowflakeId
  /** 商家。 */
  merchantId: SnowflakeId
  /** 商品。 */
  skuId: SnowflakeId
  /** 实物（= 可用 + 预留）。 */
  physicalQty: number
  /** 可用。 */
  availableQty: number
  /** 预留。 */
  reservedQty: number
  /** 库位实物合计（第二级账）。 */
  locationQtySum: number
  /** **两级账是否平衡**（SKU 三态 vs 库位合计）——后端算好，前端直接展示。 */
  twoLevelBalanced: boolean
}

/** 库位级库存（作业视角）。 */
export interface LocationStockVo {
  /** 库位。 */
  locationId: SnowflakeId
  /** 商品。 */
  skuId: SnowflakeId
  /** 实物。 */
  physicalQty: number
  /** 已分配（拣货占用）。 */
  allocatedQty: number
}

/** 库存变动流水。 */
export interface MovementVo {
  /** 流水号。 */
  movementNo: string
  /** 变动类型。 */
  movementType: string
  /** 数量（带符号）。 */
  qty: number
  /** 库位。 */
  locationId?: SnowflakeId
  /** 商品。 */
  skuId: SnowflakeId
  /** 业务类型。 */
  bizType?: string
  /** 业务单号。 */
  bizNo?: string
  /** 设备编号（PDA 来源时非空）。 */
  deviceCode?: string
  /** 发生时间。 */
  occurredAt: string
  /** 变动后实物。 */
  physicalAfter: number
  /** 变动后可用。 */
  availableAfter: number
  /** 变动后预留。 */
  reservedAfter: number
}

/** 库存操作入参（收货 / 调整共用）。 */
export class StockOpQo {
  /** 仓库。 */
  warehouseId = ''
  /** 商家。 */
  merchantId = ''
  /** 商品。 */
  skuId = ''
  /** 库位（**收货/出库/调整都必填**）。 */
  locationId = ''
  /** 数量：收货为正，调整可正可负。 */
  qty?: number
  /** 业务类型。 */
  bizType = 'MANUAL'
  /** 业务对象 ID。 */
  bizId?: string
  /** 业务单号。 */
  bizNo?: string
  /** 调整原因（复核人据此判断要批的是哪份内容）。 */
  reason = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      warehouseId: this.warehouseId,
      merchantId: this.merchantId,
      skuId: this.skuId,
      locationId: this.locationId,
      qty: this.qty,
      bizType: this.bizType,
    }
    if (this.bizId) {
      params.bizId = this.bizId
    }
    if (this.bizNo) {
      params.bizNo = this.bizNo
    }
    if (this.reason) {
      params.reason = this.reason.trim()
    }
    return params
  }
}

/** 库存调整结果（**注意：不是直接改库存，而是已提交复核**）。 */
export interface AdjustSubmitVo {
  /** 复核单号（等待他人复核通过后才真正调整库存）。 */
  approvalNo: string
  /** 提示文案。 */
  message: string
}

/** 不变量检查项。 */
export interface InvariantItemVo {
  /** 检查项名。 */
  name: string
  /** 违规条数。 */
  violation: number
  /** 是否通过。 */
  passed: boolean
}

/** 不变量检查结果。 */
export interface InvariantCheckVo {
  /** 是否全部通过。 */
  allPassed: boolean
  /** 失败项数。 */
  failedCount: number
  /** 明细。 */
  items: InvariantItemVo[]
}

/** 库存接口集合。 */
export const InventoryApi = {
  /**
   * 查某个 SKU 的三态。
   *
   * @param warehouseId 仓库
   * @param merchantId 商家
   * @param skuId 商品
   * @returns 三态（含两级账是否平衡）
   */
  getSku(warehouseId: string, merchantId: string, skuId: string): Promise<StockVo> {
    return get<StockVo>('/api/v1/inventory/sku', { warehouseId, merchantId, skuId })
  },

  /**
   * 查某 SKU 的库位分布。
   *
   * @param warehouseId 仓库
   * @param skuId 商品
   * @returns 库位列表
   */
  listLocations(warehouseId: string, skuId: string): Promise<LocationStockVo[]> {
    return get<LocationStockVo[]>('/api/v1/inventory/locations', { warehouseId, skuId })
  },

  /**
   * 查库存流水。
   *
   * @param warehouseId 仓库（'0' = 不限）
   * @param skuId 商品（'0' = 不限）
   * @param bizType 业务类型（可空）
   * @param bizNo 业务单号（可空）
   * @param limit 条数
   * @returns 流水列表
   */
  listMovements(
    warehouseId: string,
    skuId: string,
    bizType?: string,
    bizNo?: string,
    limit = 100,
  ): Promise<MovementVo[]> {
    const params: Record<string, unknown> = { warehouseId, skuId, limit }
    if (bizType) {
      params.bizType = bizType
    }
    if (bizNo) {
      params.bizNo = bizNo
    }
    return get<MovementVo[]>('/api/v1/inventory/movements', params)
  },

  /**
   * 收货入库（幂等键由请求层按动作自动生成并复用）。
   *
   * @param qo 入参（qty 传正数）
   * @returns 变更后三态
   */
  receive(qo: StockOpQo): Promise<StockVo> {
    return post<StockVo>('/api/v1/inventory/internal/receipts', qo.toParams())
  },

  /**
   * 提交库存调整（**入口层强制双人复核**：返回复核单号，不直接改库存）。
   *
   * @param qo 调整入参（qty 可正可负）
   * @returns 复核单号
   */
  submitAdjust(qo: StockOpQo): Promise<AdjustSubmitVo> {
    return post<AdjustSubmitVo>('/api/v1/inventory/internal/adjustments', qo.toParams())
  },

  /**
   * 库存不变量检查（四层对账里"库存层"的依据）。
   *
   * @returns 检查结果
   */
  invariantCheck(): Promise<InvariantCheckVo> {
    return post<InvariantCheckVo>('/api/v1/inventory/invariant-check')
  },
}

/** 三态校验结论（R7：模板不写中文三元，统一在此映射）。 */
export const StockBalanceLabel = {
  /** 平衡。 */
  BALANCED: '平衡',
  /** 不平衡（说明数据被绕过接口改动过）。 */
  UNBALANCED: '不平衡',
} as const

/**
 * 判断 SKU 三态自洽（实物 = 可用 + 预留）。
 *
 * <p>注意：后端还提供 `twoLevelBalanced`（SKU 三态 vs 库位合计），那是**第二级**校验；
 * 这里算的是第一级。两级都平衡才算账真的对得上。
 *
 * @param vo 库存
 * @returns 自洽返回 true
 */
export function isBalanced(vo: StockVo): boolean {
  return Number(vo.physicalQty) === Number(vo.availableQty) + Number(vo.reservedQty)
}

/**
 * 三态校验文案。
 *
 * @param vo 库存
 * @returns 文案
 */
export function balanceLabel(vo: StockVo): string {
  return isBalanced(vo) ? StockBalanceLabel.BALANCED : StockBalanceLabel.UNBALANCED
}

/** 两级账校验文案（R7：模板不写中文三元）。 */
export const TwoLevelLabel = {
  /** 平衡。 */
  BALANCED: '平衡',
  /** 不平衡。 */
  UNBALANCED: '不平衡',
} as const

/**
 * 第二级账（SKU 三态 vs 库位合计）文案。
 *
 * @param twoLevelBalanced 后端算好的结果
 * @returns 文案
 */
export function twoLevelLabel(twoLevelBalanced: boolean): string {
  return twoLevelBalanced ? TwoLevelLabel.BALANCED : TwoLevelLabel.UNBALANCED
}
