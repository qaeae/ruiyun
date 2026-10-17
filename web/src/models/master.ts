/**
 * 主数据模型（03 API-MD-01~20）。
 *
 * 层级口径：**仓库 → 库区 → 库位**（三级校验在服务端做，前端按层级串联选择）；
 * `md_location` 是**库位主数据**（编码/ABC/是否可拣），`iv_location` 是**库位库存**（实物/已分配）——
 * 两者不是一回事，界面上也不要混在一个列表里。
 */
import { get, post } from '@/utils/request'

/** 主数据启用状态取值（后端 `status` 中文落库）。 */
export const EnableStatus = {
  /** 启用。 */
  ENABLED: '启用',
  /** 停用。 */
  DISABLED: '停用',
} as const

/** 库区类型（后端 `zoneType` 中文落库）。 */
export const ZoneType = {
  /** 存储区。 */
  STORAGE: '存储区',
  /** 拣货区。 */
  PICK: '拣货区',
  /** 暂存区。 */
  TEMP: '暂存区',
  /** 退货区。 */
  RETURN: '退货区',
} as const

/** 设备类型（与后端 `DeviceType` 一致）。 */
export const DeviceType = {
  /** PDA。 */
  PDA: 'PDA',
  /** 扫码枪。 */
  SCANNER: 'SCANNER',
  /** 打印机。 */
  PRINTER: 'PRINTER',
  /** 电子秤。 */
  SCALE: 'SCALE',
  /** 闸机。 */
  GATE: 'GATE',
} as const

/** 设备类型中文（展示用）。 */
export const DeviceTypeLabel: Record<string, string> = {
  PDA: 'PDA',
  SCANNER: '扫码枪',
  PRINTER: '打印机',
  SCALE: '电子秤',
  GATE: '闸机',
}

/** 商家。 */
export interface MerchantVo {
  /** 商家 ID（雪花 ID，字符串）。 */
  id: string
  /** 商家编号。 */
  merchantNo: string
  /** 名称。 */
  name: string
  /** 联系人。 */
  contactName?: string
  /** 手机号（**脱敏**：后端只回 masked，前端不接触明文）。 */
  contactPhoneMasked?: string
  /** 状态。 */
  status: string
  /** 信用分。 */
  creditScore?: number
}

/** 仓库。 */
export interface WarehouseVo {
  /** 仓库 ID（雪花 ID，字符串）。 */
  id: string
  /** 仓库编码。 */
  warehouseCode: string
  /** 名称。 */
  name: string
  /** 省。 */
  province?: string
  /** 市。 */
  city?: string
  /** 地址。 */
  address?: string
  /** 截单时间（超过此时间当天的单进入次日）。 */
  cutoffTime?: string
  /** 状态。 */
  status: string
}

/** 库区。 */
export interface ZoneVo {
  /** 库区 ID（雪花 ID，字符串）。 */
  id: string
  /** 仓库。 */
  warehouseId: string
  /** 库区编码。 */
  zoneCode: string
  /** 类型。 */
  zoneType: string
  /** 名称。 */
  name?: string
  /** 是否隔离（贵重/危险品区）。 */
  isolation: boolean
}

/** 库位（主数据）。 */
export interface LocationVo {
  /** 库位 ID（雪花 ID，字符串）。 */
  id: string
  /** 仓库。 */
  warehouseId: string
  /** 库区。 */
  zoneId: string
  /** 库位编码。 */
  locationCode: string
  /** ABC 分类。 */
  abcClass?: string
  /** 是否可拣货（不可拣的库位不会出现在拣货建议里）。 */
  pickable: boolean
  /** 配额单位。 */
  quotaUnit?: number
  /** 状态。 */
  status: string
}

/** 商品（SKU）。 */
export interface SkuVo {
  /** 商品 ID（雪花 ID，字符串）。 */
  id: string
  /** 商家。 */
  merchantId: string
  /** 商品条码。 */
  skuCode: string
  /** 名称。 */
  name: string
  /** 品类。 */
  category?: string
  /** 计量单位。 */
  unit?: string
  /** 重量（kg）。 */
  weightKg?: string
  /** 体积比。 */
  volumeRatio?: string
  /** 是否禁入。 */
  forbidden: boolean
  /** 禁入原因。 */
  forbiddenReason?: string
  /** 状态。 */
  status: string
}

/** 箱规换算关系。 */
export interface UnitConvVo {
  /** 商品。 */
  skuId: string
  /** 源单位。 */
  unitFrom: string
  /** 目标单位。 */
  unitTo: string
  /** 1 unitFrom = factor unitTo。 */
  factor: string
}

/** 换算结果。 */
export interface QtyConvertVo {
  /** 商品。 */
  skuId: string
  /** 源单位。 */
  fromUnit: string
  /** 目标单位。 */
  toUnit: string
  /** 源数量。 */
  sourceQty: string
  /** 结果数量。 */
  resultQty: string
}

/** 设备档案。 */
export interface DeviceVo {
  /** 设备编号（`X-Device-Code` 取值）。 */
  deviceCode: string
  /** 设备类型。 */
  deviceType: string
  /** 类型中文。 */
  deviceTypeLabel?: string
  /** 名称。 */
  name?: string
  /** 绑定仓库。 */
  warehouseId?: string
  /** 摆放位置。 */
  locationDesc?: string
  /** 状态。 */
  status: string
  /** 最近在线时间（**不做心跳**，仅登记用途）。 */
  lastOnlineAt?: string
  /** 备注。 */
  remark?: string
  /** 创建时间。 */
  createdAt?: string
}

/** 新建商家入参。 */
export class MerchantCreateQo {
  /** 商家编号。 */
  merchantNo = ''
  /** 名称。 */
  name = ''
  /** 联系人。 */
  contactName = ''
  /** 手机号（服务端加密存储 + 脱敏返回）。 */
  contactPhone = ''
  /** 信用分。 */
  creditScore?: number

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      merchantNo: this.merchantNo.trim(),
      name: this.name.trim(),
    }
    if (this.contactName) {
      params.contactName = this.contactName.trim()
    }
    if (this.contactPhone) {
      params.contactPhone = this.contactPhone.trim()
    }
    if (this.creditScore) {
      params.creditScore = this.creditScore
    }
    return params
  }
}

/** 新建仓库入参。 */
export class WarehouseCreateQo {
  /** 仓库编码。 */
  warehouseCode = ''
  /** 名称。 */
  name = ''
  /** 省。 */
  province = ''
  /** 市。 */
  city = ''
  /** 地址。 */
  address = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      warehouseCode: this.warehouseCode.trim(),
      name: this.name.trim(),
    }
    if (this.province) {
      params.province = this.province.trim()
    }
    if (this.city) {
      params.city = this.city.trim()
    }
    if (this.address) {
      params.address = this.address.trim()
    }
    return params
  }
}

/** 新建库区入参。 */
export class ZoneCreateQo {
  /** 仓库。 */
  warehouseId?: string
  /** 库区编码。 */
  zoneCode = ''
  /** 名称。 */
  name = ''
  /** 类型。 */
  zoneType: string = ZoneType.STORAGE

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return {
      warehouseId: Number(this.warehouseId) || 0,
      zoneCode: this.zoneCode.trim(),
      name: this.name.trim(),
      zoneType: this.zoneType,
    }
  }
}

/** 新建库位入参。 */
export class LocationCreateQo {
  /** 仓库。 */
  warehouseId?: string
  /** 库区。 */
  zoneId?: string
  /** 库位编码。 */
  locationCode = ''
  /** ABC 分类。 */
  abcClass = ''
  /** 是否可拣货。 */
  isPickable = true

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      warehouseId: Number(this.warehouseId) || 0,
      zoneId: Number(this.zoneId) || 0,
      locationCode: this.locationCode.trim(),
      isPickable: this.isPickable,
    }
    if (this.abcClass) {
      params.abcClass = this.abcClass.trim()
    }
    return params
  }
}

/** 新建商品入参。 */
export class SkuCreateQo {
  /** 商家。 */
  merchantId?: string
  /** 商品条码。 */
  skuCode = ''
  /** 名称。 */
  name = ''
  /** 品类。 */
  category = ''
  /** 单位。 */
  unit = ''
  /** 重量 kg。 */
  weightKg = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      merchantId: Number(this.merchantId) || 0,
      skuCode: this.skuCode.trim(),
      name: this.name.trim(),
    }
    if (this.category) {
      params.category = this.category.trim()
    }
    if (this.unit) {
      params.unit = this.unit.trim()
    }
    if (this.weightKg) {
      params.weightKg = this.weightKg
    }
    return params
  }
}

/** 箱规入参。 */
export class UnitConvCreateQo {
  /** 源单位。 */
  unitFrom = ''
  /** 目标单位。 */
  unitTo = ''
  /** 换算系数（1 unitFrom = factor unitTo）。 */
  factor = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return { unitFrom: this.unitFrom.trim(), unitTo: this.unitTo.trim(), factor: this.factor }
  }
}

/** 设备登记入参。 */
export class DeviceRegisterQo {
  /** 设备编号（大写字母/数字/短横线）。 */
  deviceCode = ''
  /** 设备类型。 */
  deviceType: string = DeviceType.PDA
  /** 名称。 */
  name = ''
  /** 仓库。 */
  warehouseId?: string
  /** 摆放位置。 */
  locationDesc = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      deviceCode: this.deviceCode.trim(),
      deviceType: this.deviceType,
    }
    if (this.name) {
      params.name = this.name.trim()
    }
    if (this.warehouseId) {
      params.warehouseId = this.warehouseId
    }
    if (this.locationDesc) {
      params.locationDesc = this.locationDesc.trim()
    }
    return params
  }
}

/** 主数据接口。 */
export const MasterApi = {
  /**
   * 商家列表。
   *
   * @param status 状态（可空）
   * @returns 列表
   */
  listMerchants(status?: string): Promise<MerchantVo[]> {
    return get<MerchantVo[]>('/api/v1/master/merchants', status ? { status } : undefined)
  },

  /**
   * 新建商家。
   *
   * @param qo 入参
   * @returns 新 ID
   */
  createMerchant(qo: MerchantCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>('/api/v1/master/merchants', qo.toParams())
  },

  /**
   * 启停商家。
   *
   * @param merchantId 商家 ID
   * @param enabled 是否启用
   * @returns 无
   */
  changeMerchantStatus(merchantId: string, enabled: boolean): Promise<void> {
    return post<void>(`/api/v1/master/merchants/${merchantId}/status?enabled=${enabled}`)
  },

  /**
   * 仓库列表。
   *
   * @returns 列表
   */
  listWarehouses(): Promise<WarehouseVo[]> {
    return get<WarehouseVo[]>('/api/v1/master/warehouses')
  },

  /**
   * 新建仓库。
   *
   * @param qo 入参
   * @returns 新 ID
   */
  createWarehouse(qo: WarehouseCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>('/api/v1/master/warehouses', qo.toParams())
  },

  /**
   * 启停仓库。
   *
   * @param warehouseId 仓库 ID
   * @param enabled 是否启用
   * @returns 无
   */
  changeWarehouseStatus(warehouseId: string, enabled: boolean): Promise<void> {
    return post<void>(`/api/v1/master/warehouses/${warehouseId}/status?enabled=${enabled}`)
  },

  /**
   * 库区列表（按仓库）。
   *
   * @param warehouseId 仓库 ID
   * @returns 列表
   */
  listZones(warehouseId: string): Promise<ZoneVo[]> {
    return get<ZoneVo[]>('/api/v1/master/zones', { warehouseId })
  },

  /**
   * 新建库区。
   *
   * @param qo 入参
   * @returns 新 ID
   */
  createZone(qo: ZoneCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>('/api/v1/master/zones', qo.toParams())
  },

  /**
   * 库位列表（按仓库）。
   *
   * @param warehouseId 仓库 ID
   * @returns 列表
   */
  listLocations(warehouseId: string): Promise<LocationVo[]> {
    return get<LocationVo[]>('/api/v1/master/locations', { warehouseId })
  },

  /**
   * 新建库位。
   *
   * @param qo 入参
   * @returns 新 ID
   */
  createLocation(qo: LocationCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>('/api/v1/master/locations', qo.toParams())
  },

  /**
   * 启停库位。
   *
   * @param locationId 库位 ID
   * @param enabled 是否启用
   * @returns 无
   */
  changeLocationStatus(locationId: string, enabled: boolean): Promise<void> {
    return post<void>(`/api/v1/master/locations/${locationId}/status?enabled=${enabled}`)
  },

  /**
   * 商品列表（按商家）。
   *
   * @param merchantId 商家 ID
   * @returns 列表
   */
  listSkus(merchantId: string): Promise<SkuVo[]> {
    return get<SkuVo[]>('/api/v1/master/skus', { merchantId })
  },

  /**
   * 新建商品。
   *
   * @param qo 入参
   * @returns 新 ID
   */
  createSku(qo: SkuCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>('/api/v1/master/skus', qo.toParams())
  },

  /**
   * 启停商品。
   *
   * @param skuId 商品 ID
   * @param enabled 是否启用
   * @returns 无
   */
  changeSkuStatus(skuId: string, enabled: boolean): Promise<void> {
    return post<void>(`/api/v1/master/skus/${skuId}/status?enabled=${enabled}`)
  },

  /**
   * 箱规列表。
   *
   * @param skuId 商品 ID
   * @returns 列表
   */
  listUnitConversions(skuId: string): Promise<UnitConvVo[]> {
    return get<UnitConvVo[]>(`/api/v1/master/skus/${skuId}/unit-conversions`)
  },

  /**
   * 新增箱规。
   *
   * @param skuId 商品 ID
   * @param qo 入参
   * @returns 新 ID
   */
  addUnitConversion(skuId: string, qo: UnitConvCreateQo): Promise<{ id: string }> {
    return post<{ id: string }>(`/api/v1/master/skus/${skuId}/unit-conversions`, qo.toParams())
  },

  /**
   * 数量换算（1 跳 → 2 跳 → 反向推导；**无路径会报错而不是返回原值**）。
   *
   * @param skuId 商品 ID
   * @param qty 数量
   * @param fromUnit 源单位
   * @param toUnit 目标单位
   * @returns 换算结果
   */
  convertQty(skuId: string, qty: string, fromUnit: string, toUnit: string): Promise<QtyConvertVo> {
    return get<QtyConvertVo>(`/api/v1/master/skus/${skuId}/convert`, { qty, fromUnit, toUnit })
  },

  /**
   * 设备列表。
   *
   * @param deviceType 类型（可空）
   * @param warehouseId 仓库（可空）
   * @returns 列表
   */
  listDevices(deviceType?: string, warehouseId?: string): Promise<DeviceVo[]> {
    const params: Record<string, unknown> = {}
    if (deviceType) {
      params.deviceType = deviceType
    }
    if (warehouseId) {
      params.warehouseId = warehouseId
    }
    return get<DeviceVo[]>('/api/v1/master/devices', params)
  },

  /**
   * 登记设备。
   *
   * @param qo 入参
   * @returns 无
   */
  registerDevice(qo: DeviceRegisterQo): Promise<void> {
    return post<void>('/api/v1/master/devices', qo.toParams())
  },

  /**
   * 启停设备（停用后 PDA 携带该 `X-Device-Code` 会被 403 拒绝）。
   *
   * @param deviceCode 设备编号
   * @param enabled 是否启用
   * @returns 无
   */
  changeDeviceStatus(deviceCode: string, enabled: boolean): Promise<void> {
    return post<void>('/api/v1/master/devices/enable-opt', { deviceCode, enabled })
  },
}

// ---------------------------------------------------------------------------
// 展示映射（R7：模板不写业务字面量与中文三元，统一在 models 里）
// ---------------------------------------------------------------------------

/** 启停按钮文案。 */
export const ToggleLabel = {
  /** 当前启用 → 可执行"停用"。 */
  ENABLE_TO_DISABLE: '停用',
  /** 当前停用 → 可执行"启用"。 */
  DISABLE_TO_ENABLE: '启用',
} as const

/** 禁入标记文案。 */
export const ForbiddenLabel = {
  /** 禁入。 */
  FORBIDDEN: '禁入',
  /** 正常。 */
  NORMAL: '正常',
} as const

/**
 * 启停按钮文案（按当前状态给出"下一步动作"）。
 *
 * @param status 当前状态
 * @returns 按钮文案
 */
export function toggleLabel(status: string): string {
  return status === EnableStatus.ENABLED ? ToggleLabel.ENABLE_TO_DISABLE : ToggleLabel.DISABLE_TO_ENABLE
}

/**
 * 某条记录是否处于启用态。
 *
 * @param status 状态
 * @returns 启用返回 true
 */
export function isEnabled(status: string): boolean {
  return status === EnableStatus.ENABLED
}

/**
 * 禁入文案。
 *
 * @param forbidden 是否禁入
 * @returns 文案
 */
export function forbiddenLabel(forbidden: boolean): string {
  return forbidden ? ForbiddenLabel.FORBIDDEN : ForbiddenLabel.NORMAL
}

/** 是否可拣文案。 */
export const PickableLabel = {
  /** 可拣。 */
  PICKABLE: '可拣',
  /** 不可拣。 */
  UNPICKABLE: '不可拣',
} as const

/** 是否文案。 */
export const YesNoLabel = {
  /** 是。 */
  YES: '是',
  /** 否。 */
  NO: '否',
} as const

/**
 * 可拣文案。
 *
 * @param pickable 是否可拣
 * @returns 文案
 */
export function pickableLabel(pickable: boolean): string {
  return pickable ? PickableLabel.PICKABLE : PickableLabel.UNPICKABLE
}

/**
 * 是否文案。
 *
 * @param value 布尔
 * @returns 文案
 */
export function yesNoLabel(value: boolean): string {
  return value ? YesNoLabel.YES : YesNoLabel.NO
}

// ---------------------------------------------------------------------------
// 状态语义（联调确认：**各主数据的"启用态"取值不同**，不能一律按「启用」判断）
// ---------------------------------------------------------------------------

/**
 * 商家状态（真实取值见 `V4__md_master_data.sql`：**正常** 与 停用/黑名单）。
 *
 * <p>踩坑记录：最初把商家也按「启用/停用」判断，结果列表里已启用的商家被显示成"启用"按钮
 * （意思是"可以点它去启用"），与实际状态相反——这类错误只会在真实数据下暴露。
 */
export const MerchantStatus = {
  /** 正常（启用态）。 */
  NORMAL: '正常',
  /** 停用。 */
  DISABLED: '停用',
  /** 黑名单（业务上拒绝服务）。 */
  BLACKLIST: '黑名单',
} as const

/**
 * 商家是否处于可用状态。
 *
 * @param status 状态
 * @returns 可用返回 true
 */
export function isMerchantEnabled(status: string): boolean {
  return status === MerchantStatus.NORMAL
}

/**
 * 商家启停按钮文案（给出"下一步动作"）。
 *
 * @param status 状态
 * @returns 按钮文案
 */
export function merchantToggleLabel(status: string): string {
  return status === MerchantStatus.NORMAL ? MerchantStatus.DISABLED : MerchantStatus.NORMAL
}

/** 状态标签类型映射（R7：模板不写三元链）。 */
export const MerchantStatusTag = {
  /** 正常 → success。 */
  NORMAL: 'success',
  /** 停用 → info。 */
  DISABLED: 'info',
  /** 黑名单 → danger。 */
  BLACKLIST: 'danger',
} as const
