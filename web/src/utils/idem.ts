/**
 * 幂等键生成与复用（`Idempotency-Key`）。
 *
 * ## 为什么这一小段代码值得单独一个文件
 *
 * 写接口的幂等键如果"每次调用都新生成"，那么**网络重试就会重复执行业务**——
 * 幂等防线形同虚设，而这在开发环境几乎发现不了（本地不会超时重试）。
 *
 * 正确的语义是：
 *
 * | 场景 | 幂等键 |
 * |------|--------|
 * | 同一次用户动作、请求失败后重试 | **复用同一个键**（否则重试等于新业务） |
 * | 用户改动了提交内容（负载变了） | 新键（那是另一个业务动作） |
 * | 上一次已成功，用户又点了一次 | 新键（新的业务动作） |
 *
 * 这里用「业务标识 + 负载指纹」作为缓存键：**同一动作的重复请求天然拿到同一个键**，
 * 不需要调用方记着"重试要复用"，也就不会有人写错。
 */

/** 已生成的幂等键缓存：actionKey（url + 负载指纹）→ idemKey。 */
const keyCache = new Map<string, string>()

/**
 * 负载指纹（稳定序列化：对象键排序，避免字段顺序不同导致指纹不同）。
 *
 * @param payload 请求负载（可为空）
 * @returns 指纹字符串
 */
export function payloadFingerprint(payload: unknown): string {
  if (payload === null || payload === undefined) {
    return ''
  }
  return JSON.stringify(sortDeep(payload))
}

/**
 * 递归排序对象的键（数组保持顺序；数组顺序是业务语义的一部分）。
 *
 * @param value 任意值
 * @returns 键排序后的等价结构
 */
function sortDeep(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => sortDeep(item))
  }
  if (value !== null && typeof value === 'object') {
    const source = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(source).sort()) {
      sorted[key] = sortDeep(source[key])
    }
    return sorted
  }
  return value
}

/**
 * 生成 32 位十六进制随机串（不依赖 `crypto.randomUUID`，兼容非安全上下文）。
 *
 * @returns 随机 idemKey
 */
export function newIdemKey(): string {
  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * 取（或创建）某业务动作的幂等键。
 *
 * @param actionKey 业务动作标识（建议 `METHOD url + 负载指纹`）
 * @returns 幂等键（同一 actionKey 在清除前恒定）
 */
export function idemKeyFor(actionKey: string): string {
  const cached = keyCache.get(actionKey)
  if (cached) {
    return cached
  }
  const created = newIdemKey()
  keyCache.set(actionKey, created)
  return created
}

/**
 * 清除某业务动作的幂等键（业务已成功，下次点击应视为新动作）。
 *
 * @param actionKey 业务动作标识
 */
export function clearIdemKey(actionKey: string): void {
  keyCache.delete(actionKey)
}

/**
 * 清空全部缓存（登出时调用：避免换用户后复用上一个会话的键）。
 */
export function clearAllIdemKeys(): void {
  keyCache.clear()
}

/**
 * 为写请求计算动作标识。
 *
 * @param method HTTP 方法
 * @param url 请求地址
 * @param payload 请求负载
 * @returns 动作标识
 */
export function actionKeyOf(method: string, url: string, payload: unknown): string {
  return `${method.toUpperCase()} ${url} ${payloadFingerprint(payload)}`
}
