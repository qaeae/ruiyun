/**
 * 写操作遮罩（规范 R6）。
 *
 * 所有写操作（删除/提交/启停/授权/刷新/重置/详情加载）都必须有明确文案的遮罩，
 * 否则用户会重复点击——**而重复点击在写接口上就是重复提交**。
 */
import { ElLoading, ElMessage } from 'element-plus'
import { showError } from '@/utils/request'

/** 遮罩文案约定（规范 R6 的取值，避免各处自定义导致口径不一）。 */
export const MaskText = {
  /** 删除。 */
  REMOVE: '操作中...',
  /** 提交。 */
  SUBMIT: '提交中...',
  /** 启停。 */
  TOGGLE: '处理中...',
  /** 授权。 */
  GRANT: '授权中...',
  /** 刷新。 */
  REFRESH: '刷新中...',
  /** 重置。 */
  RESET: '重置中...',
  /** 详情。 */
  DETAIL: '数据加载中...',
} as const

/**
 * 带遮罩执行写操作。
 *
 * @param task 业务动作
 * @param text 遮罩文案（默认「提交中...」）
 * @returns 动作结果（失败时返回 undefined 并已提示）
 */
export async function withMask<T>(task: () => Promise<T>, text: string = MaskText.SUBMIT): Promise<T | undefined> {
  const loading = ElLoading.service({ text, background: 'rgba(0, 0, 0, 0.35)' })
  try {
    return await task()
  } catch (error) {
    showError(error)
    return undefined
  } finally {
    loading.close()
  }
}

/**
 * 成功提示（统一出口，便于以后接埋点/通知）。
 *
 * @param text 文案
 */
export function showSuccess(text: string): void {
  ElMessage.success(text)
}
