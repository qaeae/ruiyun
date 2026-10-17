/**
 * 通用模型（分页、排序、响应结构、展示格式化）。
 *
 * ## 联调确认的两个 JSON 口径（2026-10-14，真实后端实测）
 *
 * | 类型 | 实际形态 | 前端处理 |
 * |------|---------|---------|
 * | 雪花 ID（`Long`） | **字符串** `"103993333059158016"` | **必须按字符串传递**，不要转 Number |
 * | 计数/序号（`int`/`long`） | 数字 `2` | 按数字处理 |
 * | 金额/数量（`BigDecimal`） | 数字 `0.0000` | 按数字处理，**展示时格式化补足小数位** |
 * | 时间（`LocalDateTime`） | `"2026-10-14 23:13:17.917"` | 直接展示（后端已按 03 口径格式化） |
 *
 * <p>**为什么 ID 必须是字符串**：雪花 ID 是 19 位整数，超过 JS 的 `Number.MAX_SAFE_INTEGER`（约 9e15），
 * `JSON.parse` 会丢精度——联调时真实发生过：后端返回 `...376`，前端拿到 `...380`，
 * 拿这个 ID 去授权就报"角色不存在"。后端因此改为把 `Long` 序列化成字符串（见 `JsonTimeConfig`）。
 */

/** 排序方向（R8：命名后引用，避免各处内联 `'asc' | 'desc'`）。 */
export type SortOrder = 'asc' | 'desc'

/** 雪花 ID（后端以字符串返回，前端只做透传，不参与算术）。 */
export type SnowflakeId = string

/** 分页请求（03 §1.1：页码从 1 开始）。 */
export class PageQo {
  /** 页码（从 1 开始）。 */
  pageNum = 1

  /** 每页条数。 */
  pageSize = 20

  /** 排序字段（仅后端白名单字段生效）。 */
  sortBy?: string

  /** 排序方向。 */
  sortOrder?: SortOrder

  /**
   * 转请求参数（R3：仅非空字段）。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {
      pageNum: Number(this.pageNum) || 1,
      pageSize: Number(this.pageSize) || 20,
    }
    if (this.sortBy) {
      params.sortBy = this.sortBy
    }
    if (this.sortOrder) {
      params.sortOrder = this.sortOrder
    }
    return params
  }
}

/** 分页结果。 */
export interface PageResult<T> {
  /** 总条数。 */
  total: number
  /** 当前页数据。 */
  list: T[]
}

/**
 * 数量展示（去掉无意义的尾部 0，最多 4 位小数）。
 *
 * 后端 BigDecimal 返回的是 JSON 数字（`100`、`100.0000`），
 * 直接展示会出现 `100.0000` 这种噪声，因此在展示层统一格式化。
 *
 * @param value 数量（数字或字符串）
 * @returns 展示文案
 */
export function formatQty(value: number | string | null | undefined): string {
  if (value === null || value === undefined) {
    return '-'
  }
  const num = Number(value)
  if (Number.isNaN(num)) {
    return String(value)
  }
  return String(Number(num.toFixed(4)))
}

/**
 * 金额展示（保留 2 位小数，符合财务阅读习惯；后端精度是 4 位，需要时用 {@link formatQty}）。
 *
 * @param value 金额
 * @returns 展示文案
 */
export function formatAmount(value: number | string | null | undefined): string {
  if (value === null || value === undefined) {
    return '-'
  }
  const num = Number(value)
  if (Number.isNaN(num)) {
    return String(value)
  }
  return num.toFixed(2)
}

/**
 * 时间展示（后端已按 `yyyy-MM-dd HH:mm:ss.SSS` 输出，这里只做空值兜底与截断到秒）。
 *
 * @param value 时间字符串
 * @returns 展示文案
 */
export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  // 截掉毫秒（列表页不需要毫秒；详情页需要时可自行展示原值）
  return value.length > 19 ? value.slice(0, 19) : value
}
