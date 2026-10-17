/**
 * 幂等键规则单测。
 *
 * 这一条必须由机器验证：**"每次调用新生成键"是最容易犯、也最难在开发环境发现的错误**——
 * 本地不会超时重试，所以问题只会在生产网络抖动时出现（表现为"重复扣款/重复建单"）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { actionKeyOf, clearAllIdemKeys, clearIdemKey, idemKeyFor, newIdemKey, payloadFingerprint } from '@/utils/idem'

describe('幂等键规则', () => {
  beforeEach(() => {
    clearAllIdemKeys()
  })

  it('同一业务动作重复请求 → 拿到同一个键（重试安全）', () => {
    const action = actionKeyOf('post', '/api/v1/inventory/internal/adjustments', { skuId: 1, qty: '-5' })
    const first = idemKeyFor(action)
    const second = idemKeyFor(action)
    expect(second).toBe(first)
  })

  it('负载相同但字段顺序不同 → 视为同一动作（指纹稳定）', () => {
    const a = actionKeyOf('post', '/api/v1/x', { skuId: 1, qty: '-5' })
    const b = actionKeyOf('post', '/api/v1/x', { qty: '-5', skuId: 1 })
    expect(b).toBe(a)
  })

  it('负载变化 → 新键（那是另一个业务动作）', () => {
    const a = actionKeyOf('post', '/api/v1/x', { qty: '-5' })
    const b = actionKeyOf('post', '/api/v1/x', { qty: '-6' })
    expect(idemKeyFor(b)).not.toBe(idemKeyFor(a))
  })

  it('业务成功后清除 → 下次点击视为新动作（新键）', () => {
    const action = actionKeyOf('post', '/api/v1/x', { qty: '-5' })
    const first = idemKeyFor(action)
    clearIdemKey(action)
    expect(idemKeyFor(action)).not.toBe(first)
  })

  it('未清除时跨多次调用保持稳定（模拟网络重试 3 次）', () => {
    const action = actionKeyOf('post', '/api/v1/x', { qty: '-5' })
    const keys = [idemKeyFor(action), idemKeyFor(action), idemKeyFor(action)]
    expect(new Set(keys).size).toBe(1)
  })

  it('键长不超过 96 字符（后端校验上限）', () => {
    for (let i = 0; i < 50; i += 1) {
      expect(newIdemKey().length).toBeLessThanOrEqual(96)
    }
  })

  it('数组顺序变化 → 视为不同动作（顺序是业务语义）', () => {
    const a = payloadFingerprint({ ids: [1, 2] })
    const b = payloadFingerprint({ ids: [2, 1] })
    expect(a).not.toBe(b)
  })

  it('空负载可用（DELETE 之类的无负载写操作）', () => {
    const action = actionKeyOf('delete', '/api/v1/x/1', undefined)
    expect(idemKeyFor(action)).toHaveLength(32)
  })
})
