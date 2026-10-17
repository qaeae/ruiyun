/**
 * 统一请求封装（前端接入契约的**唯一实现处**）。
 *
 * 契约来源：`wms-platform/docs/frontend/W5-FRONTEND-KICKOFF.md` §二/§三。
 *
 * | 口径 | 实现 |
 * |------|------|
 * | 成功码 `code === 0` | 响应拦截器判定（**不是** HTTP 200 就算成功） |
 * | 401 未认证 | 清 token + 清幂等键 + 跳登录 |
 * | 403 无权限 | **只提示，不跳登录**（跳了用户反复重登也解决不了） |
 * | 409 冲突 / 429 并发重入 | 提示并让调用方决定是否刷新（同幂等键的重试是安全的） |
 * | `Idempotency-Key` | 写方法自动携带；**同动作重试复用同一个键**（见 utils/idem.ts） |
 * | `X-Trace-Id` | 每次请求生成，响应回写，便于与后端 `traceId` 对齐 |
 */
import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { actionKeyOf, clearAllIdemKeys, clearIdemKey, idemKeyFor } from '@/utils/idem'

/** 后端统一响应体（03 §1.2）。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

/** 成功码（本项目的口径：0，不是 200）。 */
export const CODE_SUCCESS = 0

/** 错误码分段（03 §1.3）。 */
export const ErrorCode = {
  /** 参数校验失败。 */
  PARAM_ERROR: 10001,
  /** 幂等键内容不一致（同键不同负载）。 */
  IDEMPOTENT_MISMATCH: 10002,
  /** 同幂等键请求处理中（并发重入）。 */
  IDEMPOTENT_IN_PROGRESS: 10003,
  /** 设备未登记或已停用。 */
  DEVICE_NOT_REGISTERED: 10006,
  /** 无权限（401 与 403 都用这个业务码，靠 HTTP 状态区分）。 */
  FORBIDDEN: 40300,
} as const

/** 写方法（需要幂等键）。 */
const WRITE_METHODS = ['post', 'put', 'patch', 'delete']

/** 免幂等键的路径（会话建立前 / 逐条幂等的设备补传，03 §1.5）。 */
const IDEM_EXEMPT_PATHS = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/me', '/api/v1/pda/sync/batch']

/** token 读写（由 stores/auth.ts 注册，避免循环依赖）。 */
let tokenProvider: () => string | null = () => null
let onUnauthorized: () => void = () => undefined

/**
 * 注册 token 提供者与 401 处理（在应用启动时由 auth store 注入）。
 *
 * @param provider 取当前 token
 * @param unauthorized 401 时的回调（清会话 + 跳登录）
 */
export function configureAuthBridge(provider: () => string | null, unauthorized: () => void): void {
  tokenProvider = provider
  onUnauthorized = unauthorized
}

/** 生成 traceId（前端侧链路标识）。 */
function newTraceId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** axios 实例。 */
const http: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

// 1. 请求拦截：认证头、幂等键、traceId
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 1.1 认证头（登录/刷新等公开接口不需要，但带上也无害；这里按"有则带"处理）
  const token = tokenProvider()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  // 1.2 链路标识
  const traceId = newTraceId()
  config.headers.set('X-Trace-Id', traceId)
  ;(config as AxiosRequestConfig & { __traceId?: string }).__traceId = traceId

  // 1.3 写方法：自动携带幂等键（同动作重试复用同一个键）
  const method = (config.method ?? 'get').toLowerCase()
  const url = config.url ?? ''
  const exempt = IDEM_EXEMPT_PATHS.some((path) => url.startsWith(path))
  if (WRITE_METHODS.includes(method) && !exempt) {
    const actionKey = actionKeyOf(method, url, config.data)
    config.headers.set('Idempotency-Key', idemKeyFor(actionKey))
    ;(config as AxiosRequestConfig & { __actionKey?: string }).__actionKey = actionKey
  }
  return config
})

// 2. 响应拦截：统一判 code、区分 401/403、成功后释放幂等键
http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown> | undefined
    // 2.1 非统一响应体（如文件下载）：原样返回
    if (!body || typeof body.code !== 'number') {
      return response
    }
    // 2.2 业务成功：释放该动作的幂等键，返回 data
    if (body.code === CODE_SUCCESS) {
      const actionKey = (response.config as AxiosRequestConfig & { __actionKey?: string }).__actionKey
      if (actionKey) {
        clearIdemKey(actionKey)
      }
      return response
    }
    // 2.3 业务失败（HTTP 200 + code≠0）：抛给调用方，由页面决定提示方式
    return Promise.reject(new BizError(body.code, body.message, body.traceId))
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const status = error.response?.status
    const body = error.response?.data
    const message = body?.message ?? error.message

    // 3.1 未认证 → 清会话并跳登录
    if (status === 401) {
      clearAllIdemKeys()
      onUnauthorized()
      return Promise.reject(new BizError(body?.code ?? ErrorCode.FORBIDDEN, '登录已失效，请重新登录', body?.traceId))
    }
    // 3.2 无权限 → **只提示，不跳登录**（跳了用户反复重登也解决不了问题）
    if (status === 403) {
      return Promise.reject(new BizError(body?.code ?? ErrorCode.FORBIDDEN, message || '无权限，请联系管理员', body?.traceId))
    }
    // 3.3 冲突（幂等键内容不一致 / 状态机冲突）：提示后由调用方决定是否刷新
    if (status === 409) {
      return Promise.reject(new BizError(body?.code ?? 19999, message || '数据已变化，请刷新后重试', body?.traceId))
    }
    // 3.4 并发重入：同幂等键的重试是安全的，稍后重试即可
    if (status === 429) {
      return Promise.reject(new BizError(body?.code ?? ErrorCode.IDEMPOTENT_IN_PROGRESS, '请求正在处理中，请稍后重试', body?.traceId))
    }
    return Promise.reject(new BizError(body?.code ?? status ?? 19999, message || '网络异常，请稍后重试', body?.traceId))
  },
)

/**
 * 业务异常（携带后端错误码与 traceId）。
 */
export class BizError extends Error {
  /** 后端错误码。 */
  readonly code: number

  /** 链路标识（排查时提供给后端）。 */
  readonly traceId?: string

  /**
   * 构造。
   *
   * @param code 错误码
   * @param message 文案
   * @param traceId 链路标识
   */
  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
    this.traceId = traceId
  }
}

/**
 * 统一错误提示（页面里 `catch (e) { showError(e) }` 即可）。
 *
 * @param error 异常
 */
export function showError(error: unknown): void {
  const message = error instanceof BizError ? error.message : '操作失败，请稍后重试'
  ElMessage.error(message)
}

/**
 * GET。
 *
 * @param url 地址
 * @param params 查询参数
 * @returns 业务数据
 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

/**
 * POST（自动携带幂等键）。
 *
 * @param url 地址
 * @param data 负载
 * @returns 业务数据
 */
export async function post<T>(url: string, data?: unknown): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, data)
  return response.data.data
}

/**
 * PUT（自动携带幂等键）。
 *
 * @param url 地址
 * @param data 负载
 * @returns 业务数据
 */
export async function put<T>(url: string, data?: unknown): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, data)
  return response.data.data
}

export default http
