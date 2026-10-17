/**
 * 登录与身份模型（03 API-AUTH-01~03）。
 *
 * 规范 R2：后缀 Qo（入参）/Vo（出参）；R1：请求负载必须由 `toParams()` 产生。
 */
import { post } from '@/utils/request'

/** 登录入参。 */
export class LoginQo {
  /** 用户名。 */
  username = ''

  /** 口令（明文仅用于本次请求，前端不落地）。 */
  password = ''

  /**
   * 转请求参数。
   *
   * @returns 请求参数
   */
  toParams(): Record<string, unknown> {
    return {
      username: this.username.trim(),
      password: this.password,
    }
  }
}

/** 登录返回。 */
export interface LoginVo {
  /** 令牌（放 `Authorization: Bearer`）。 */
  token: string
  /** 过期时间。 */
  expiresAt: string
  /** 用户 ID（雪花 ID 字符串）。 */
  userId: string
  /** 用户名。 */
  username: string
  /** 超管标记（**true 视为拥有全部权限**）。 */
  adminFlag: boolean
  /** 角色码。 */
  roleCodes: string[]
  /** 权限码快照（**改授权后需重新登录/刷新才生效**）。 */
  permissions: string[]
  /** 可见仓库范围（超管可为空 = 全部）。 */
  warehouseIds: string[]
}

/** 当前身份（`POST /auth/me`：拿最新权限快照）。 */
export interface CurrentUserVo {
  /** 用户 ID。 */
  userId: string
  /** 用户名。 */
  username: string
  /** 超管标记。 */
  adminFlag: boolean
  /** 角色码。 */
  roleCodes: string[]
  /** 权限码。 */
  permissions: string[]
  /** 仓库范围。 */
  warehouseIds: string[]
}

/** 认证接口集合。 */
export const AuthApi = {
  /**
   * 登录（免幂等键：会话建立前无从生成业务幂等键）。
   *
   * @param qo 登录入参
   * @returns 登录信息
   */
  login(qo: LoginQo): Promise<LoginVo> {
    return post<LoginVo>('/api/v1/auth/login', qo.toParams())
  },

  /**
   * 刷新令牌（会重新查库，因此权限变更在刷新后生效）。
   *
   * @returns 登录信息
   */
  refresh(): Promise<LoginVo> {
    return post<LoginVo>('/api/v1/auth/refresh')
  },

  /**
   * 当前身份（取最新权限快照）。
   *
   * @returns 当前用户
   */
  me(): Promise<CurrentUserVo> {
    return post<CurrentUserVo>('/api/v1/auth/me')
  },
}
