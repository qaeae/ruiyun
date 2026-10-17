/**
 * 认证与权限 store（前端控制面的唯一来源）。
 *
 * 口径（前置包 §三）：
 * - 按钮显隐用 `permissions`；**`adminFlag === true` 视为拥有全部权限**；
 * - 仓库切换器只展示 `warehouseIds`（超管可为空 = 全部）；
 * - 权限快照在令牌里 → **改授权后需重新登录/刷新令牌才生效**。
 */
import { defineStore } from 'pinia'
import { AuthApi, type LoginQo, type LoginVo } from '@/models/auth'
import { clearAllIdemKeys } from '@/utils/idem'

/** localStorage 键（令牌持久化：刷新页面不丢登录态）。 */
const TOKEN_KEY = 'wms.token'
const USER_KEY = 'wms.user'

/** 会话信息（token + 权限快照）。 */
export interface SessionState {
  /** 令牌。 */
  token: string
  /** 用户 ID（雪花 ID 字符串）。 */
  userId: string
  /** 用户名。 */
  username: string
  /** 超管标记。 */
  adminFlag: boolean
  /** 权限码。 */
  permissions: string[]
  /** 仓库范围（字符串 ID）。 */
  warehouseIds: string[]
}

/**
 * 从 localStorage 读会话（解析失败返回 null，避免脏数据把应用卡死）。
 *
 * @returns 会话或 null
 */
function loadSession(): SessionState | null {
  const token = localStorage.getItem(TOKEN_KEY)
  const rawUser = localStorage.getItem(USER_KEY)
  if (!token || !rawUser) {
    return null
  }
  try {
    const user = JSON.parse(rawUser) as Omit<SessionState, 'token'>
    return { token, ...user }
  } catch {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    /** 当前会话（未登录为 null）。 */
    session: loadSession() as SessionState | null,
  }),

  getters: {
    /** 是否已登录。 */
    isLogin: (state): boolean => !!state.session?.token,

    /** 当前用户 ID（判断"不能自审"用；空会话为空串）。 */
    userId: (state): string => state.session?.userId ?? '',

    /** 超管。 */
    isAdmin: (state): boolean => state.session?.adminFlag === true,

    /**
     * 权限判定（超管直接放行）。
     *
     * @returns 判定函数
     */
    can: (state) => {
      const permissions = state.session?.permissions ?? []
      const isAdmin = state.session?.adminFlag === true
      return (code?: string): boolean => {
        if (!code) {
          return true
        }
        return isAdmin || permissions.includes(code)
      }
    },

    /** 可见仓库（空数组 = 全部，通常只有超管会为空）。 */
    visibleWarehouseIds: (state): string[] => state.session?.warehouseIds ?? [],
  },

  actions: {
    /**
     * 登录。
     *
     * @param qo 登录入参
     */
    async login(qo: LoginQo): Promise<void> {
      const vo = await AuthApi.login(qo)
      this.applyLogin(vo)
    },

    /**
     * 刷新令牌（重新查库 → 权限变更在此生效）。
     */
    async refresh(): Promise<void> {
      const vo = await AuthApi.refresh()
      this.applyLogin(vo)
    },

    /**
     * 写入会话并持久化。
     *
     * @param vo 登录返回
     */
    applyLogin(vo: LoginVo): void {
      const session: SessionState = {
        token: vo.token,
        userId: vo.userId,
        username: vo.username,
        adminFlag: vo.adminFlag === true,
        permissions: vo.permissions ?? [],
        warehouseIds: vo.warehouseIds ?? [],
      }
      this.session = session
      localStorage.setItem(TOKEN_KEY, session.token)
      localStorage.setItem(USER_KEY, JSON.stringify({
        userId: session.userId,
        username: session.username,
        adminFlag: session.adminFlag,
        permissions: session.permissions,
        warehouseIds: session.warehouseIds,
      }))
    },

    /** 退出登录（清会话 + 清幂等键缓存，避免换用户后复用上一个会话的键）。 */
    logout(): void {
      this.session = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      clearAllIdemKeys()
    },
  },
})
