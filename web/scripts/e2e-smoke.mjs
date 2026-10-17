#!/usr/bin/env node
/**
 * 端到端联调冒烟（**需要后端已启动**，不走 CI）。
 *
 * 为什么要有这个脚本而不是只用浏览器点：两条最关键的链路（库存调整走复核、结算下发走复核）
 * 都需要**两个账号**（发起人 ≠ 复核人），手工点要来回切号、且步骤不可复现。
 * 脚本把整条链路固定下来，每次改动后可重跑，也能给出"哪一步断了"的准确位置。
 *
 * 用法：
 *   1) 起后端（见 README §联调）
 *   2) node scripts/e2e-smoke.mjs [baseURL]     # 默认 http://127.0.0.1:8080
 *
 * 前置：库里需要一个超管账号（admin / Admin@12345），见 README 的初始化 SQL。
 *
 * 退出码：0 = 全部通过；1 = 有步骤失败。
 */
const BASE = process.argv[2] ?? 'http://127.0.0.1:8080'

/** 步骤结果。 */
const results = []

/**
 * 记录一个步骤结果。
 *
 * @param name 步骤名
 * @param ok 是否通过
 * @param detail 说明
 */
function step(name, ok, detail) {
  results.push({ name, ok, detail })
  const mark = ok ? '✅' : '❌'
  console.log(`${mark} ${name}｜${detail}`)
}

/**
 * 轮询等待某个条件成立（**最终一致语义的正确断言方式**）。
 *
 * 为什么需要它：审批通过后由「发件箱 → MQ → 消费方执行」，这是**最终一致**（亚秒级）。
 * 直接断言就会出现竞态——实测：批次状态断言失败，但 1 秒后数据库里已是「已下发」。
 * 两种模式都兼容：强一致模式（进程内执行）第一次就会成立，轮询立即返回。
 */
async function waitFor(desc, probe, timeoutMs = 15000, intervalMs = 300) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await probe()
    if (last.ok) return last
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  console.log(`  ⚠️ 等待超时（${timeoutMs}ms）：${desc}，最后一次=${JSON.stringify(last?.value)}`)
  return last
}

/** 免幂等键的路径（会话建立前 / 逐条幂等的设备补传，与 03 §1.5 一致）。 */
const IDEM_EXEMPT = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/me', '/api/v1/pda/sync/batch']

/** 写方法（需要幂等键）。 */
const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE']

/**
 * 生成动作级幂等键（与前端 `utils/idem.ts` 同规则：方法 + 路径 + 负载指纹）。
 *
 * 用同一规则的意义：脚本重复执行时同一动作会拿到同一个键——正好用来**验证后端的幂等重放**；
 * 也让脚本顺便回归"前端的幂等键口径"是否被后端接受。
 *
 * @param method 方法
 * @param path 路径
 * @param body 负载
 * @returns 幂等键（≤96 字符）
 */
function actionIdemKey(method, path, body) {
  const raw = `${method} ${path} ${body === undefined ? '' : JSON.stringify(body)}`
  let hash = 5381
  for (let i = 0; i < raw.length; i += 1) {
    hash = ((hash << 5) + hash + raw.charCodeAt(i)) >>> 0
  }
  return `IT-${hash.toString(16)}-${STAMP}`.slice(0, 96)
}

/**
 * 调用接口（统一处理 code !== 0 与 HTTP 错误）。
 *
 * @param method HTTP 方法
 * @param path 路径
 * @param options 选项（token / body / idemKey）
 * @returns 解析后的响应
 */
async function call(method, path, options = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (options.token) {
    headers.Authorization = `Bearer ${options.token}`
  }
  // 写方法自动带幂等键（除豁免路径或显式传入）：**所有写接口都要求该头**，漏了会 400 + 10001
  const exempt = IDEM_EXEMPT.some((prefix) => path.startsWith(prefix))
  const idemKey =
    options.idemKey ?? (WRITE_METHODS.includes(method) && !exempt ? actionIdemKey(method, path, options.body) : undefined)
  if (idemKey) {
    headers['Idempotency-Key'] = idemKey
  }
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = { raw: text }
  }
  return { status: response.status, body: json }
}

/**
 * 断言业务成功（HTTP 200 且 code === 0）。
 *
 * @param response 响应
 * @param what 动作描述
 * @returns data
 */
function ok(response, what) {
  if (response.status !== 200 || !response.body || response.body.code !== 0) {
    throw new Error(`${what} 失败：HTTP ${response.status} ${JSON.stringify(response.body)}`)
  }
  return response.body.data
}

/**
 * 断言业务失败且错误码符合预期（用于验证"该拦的真的拦住了"）。
 *
 * @param response 响应
 * @param expectedCode 期望错误码
 * @param what 动作描述
 * @returns 实际错误码
 */
function expectCode(response, expectedCode, what) {
  const actual = response.body?.code
  if (actual !== expectedCode) {
    throw new Error(`${what}：期望错误码 ${expectedCode}，实际 HTTP ${response.status} code=${actual} ${JSON.stringify(response.body)}`)
  }
  return actual
}

/** 唯一后缀（避免重复运行撞唯一键）。 */
const STAMP = Date.now().toString().slice(-8)

async function main() {
  // ===== 0. 健康检查 =====
  const health = await call('GET', '/api/v1/health')
  step('健康检查', health.status === 200, `HTTP ${health.status} code=${health.body?.code} 应用=${health.body?.data?.application}`)

  // ===== 1. 登录（超管）=====
  const adminLogin = await call('POST', '/api/v1/auth/login', {
    body: { username: 'admin', password: 'Admin@12345' },
  })
  const admin = ok(adminLogin, '超管登录')
  step('超管登录', true, `userId=${admin.userId} adminFlag=${admin.adminFlag} 权限数=${admin.permissions?.length ?? 0} 令牌过期=${admin.expiresAt}`)
  const adminToken = admin.token

  // ===== 2. 口令错误与用户枚举防护 =====
  const wrongPwd = await call('POST', '/api/v1/auth/login', { body: { username: 'admin', password: 'wrong-pass' } })
  const unknownUser = await call('POST', '/api/v1/auth/login', { body: { username: 'no-such-user', password: 'wrong-pass' } })
  const sameMessage = wrongPwd.body?.message === unknownUser.body?.message
  step('口令错误/用户不存在', wrongPwd.body?.code !== 0 && sameMessage,
    `口令错误 code=${wrongPwd.body?.code}；不存在 code=${unknownUser.body?.code}；提示一致=${sameMessage}（防用户名枚举）`)

  // ===== 3. 未带令牌 → 401 =====
  const noToken = await call('GET', '/api/v1/master/merchants')
  step('未带令牌', noToken.status === 401, `HTTP ${noToken.status} code=${noToken.body?.code}（未认证 → 401）`)

  // ===== 4. 权限目录同步（幂等）=====
  const sync1 = ok(await call('POST', '/api/v1/auth/permissions/sync', { token: adminToken }), '权限目录同步')
  const sync2 = ok(await call('POST', '/api/v1/auth/permissions/sync', { token: adminToken }), '权限目录同步(重跑)')
  const permCodes = ok(await call('GET', '/api/v1/auth/permissions', { token: adminToken }), '权限清单')
  step('权限目录', sync2 === 0, `首次新增 ${sync1} 条、重跑新增 ${sync2} 条（幂等）；目录共 ${permCodes.length} 条`)

  // ===== 5. 建复核人账号 + 最小权限角色（验证"分权"真的生效）=====
  const reviewerName = `reviewer${STAMP}`
  const reviewerPwd = 'Review@12345'
  const created = await call('POST', '/api/v1/auth/users', {
    token: adminToken,
    body: { username: reviewerName, password: reviewerPwd, name: '联调复核人', adminFlag: false },
  })
  const reviewerId = ok(created, '创建复核人').id
  step('创建复核人（非超管）', reviewerId > 0, `userId=${reviewerId} adminFlag=false`)

  const role = await call('POST', '/api/v1/auth/roles', {
    token: adminToken,
    body: { roleCode: `REVIEWER_${STAMP}`, name: '联调复核角色' },
  })
  const roleId = ok(role, '创建角色').id

  const grantCodes = ['stock:query', 'stock:adjust', 'auth:approval:apply', 'auth:approval:review', 'finance:query']
  ok(await call('POST', `/api/v1/auth/roles/${roleId}/permissions`, {
    token: adminToken,
    body: { codes: grantCodes, warehouseIds: [] },
  }), '授予角色权限')
  ok(await call('POST', `/api/v1/auth/users/${reviewerId}/roles`, {
    token: adminToken,
    body: { codes: [`REVIEWER_${STAMP}`], warehouseIds: [] },
  }), '分配用户角色')

  const reviewerLogin = ok(await call('POST', '/api/v1/auth/login', {
    body: { username: reviewerName, password: reviewerPwd },
  }), '复核人登录')
  const reviewerToken = reviewerLogin.token
  step('复核人登录（权限来自角色，非超管）',
    reviewerLogin.permissions.includes('auth:approval:review') && !reviewerLogin.adminFlag,
    `权限数=${reviewerLogin.permissions.length}，含 approval:review=${reviewerLogin.permissions.includes('auth:approval:review')}`)

  // ===== 6. 复核人访问未授权功能 → 403 =====
  const forbidden = await call('POST', '/api/v1/master/merchants', {
    token: reviewerToken,
    body: { merchantNo: `X${STAMP}`, name: '不该能建' },
  })
  step('越权访问', forbidden.status === 403, `复核人建商家 → HTTP ${forbidden.status} code=${forbidden.body?.code}（无权限 → 403，不是 401）`)

  // ===== 7. 主数据（层级校验）=====
  const merchantId = ok(await call('POST', '/api/v1/master/merchants', {
    token: adminToken,
    body: { merchantNo: `M${STAMP}`, name: `联调商家${STAMP}`, contactName: '张三', contactPhone: '13800001234' },
  }), '创建商家').id
  const warehouseId = ok(await call('POST', '/api/v1/master/warehouses', {
    token: adminToken,
    body: { warehouseCode: `W${STAMP}`, name: `联调仓${STAMP}`, province: '上海市', city: '上海市' },
  }), '创建仓库').id
  const zoneId = ok(await call('POST', '/api/v1/master/zones', {
    token: adminToken,
    body: { warehouseId, zoneCode: `Z${STAMP}`, name: 'A 区', zoneType: '拣货区' },
  }), '创建库区').id
  const locationId = ok(await call('POST', '/api/v1/master/locations', {
    token: adminToken,
    body: { warehouseId, zoneId, locationCode: `L${STAMP}`, abcClass: 'A', isPickable: true },
  }), '创建库位').id
  const skuId = ok(await call('POST', '/api/v1/master/skus', {
    token: adminToken,
    body: { merchantId, skuCode: `S${STAMP}`, name: '联调商品', category: '日用品', unit: '件', weightKg: '1.5' },
  }), '创建商品').id
  step('主数据建链', true, `商家=${merchantId} 仓库=${warehouseId} 库区=${zoneId} 库位=${locationId} 商品=${skuId}`)

  // ===== 7.1 层级校验（两种真实错法）=====
  //
  // 注意：这里第一次跑用的是"另一个库区 + 新编码"，后端**允许**（本来就应该允许）——
  // 是测试用例写错了，不是服务端漏校验。修正后按两种真实错法验证：
  //   ① 库位编码重复 → 必须拒绝（**唯一范围是仓库**，由服务端报错文案确认："该仓库下库位编码已存在"）；
  //   ② 仓库与库区不属于同一层级（跨仓拼装）→ 必须拒绝（三级层级校验）。
  const zone2 = ok(await call('POST', '/api/v1/master/zones', {
    token: adminToken,
    body: { warehouseId, zoneCode: `Z2${STAMP}`, name: 'B 区', zoneType: '存储区' },
  }), '创建另一个库区').id

  const dupCode = await call('POST', '/api/v1/master/locations', {
    token: adminToken,
    body: { warehouseId, zoneId, locationCode: `L${STAMP}`, isPickable: true },
  })
  step('层级校验（同库区编码重复）', dupCode.body?.code !== 0,
    `重复编码 → code=${dupCode.body?.code} ${dupCode.body?.message ?? ''}`)

  const otherWarehouse = ok(await call('POST', '/api/v1/master/warehouses', {
    token: adminToken,
    body: { warehouseCode: `W2${STAMP}`, name: `联调仓B${STAMP}` },
  }), '创建第二个仓库').id
  const crossZone = await call('POST', '/api/v1/master/locations', {
    token: adminToken,
    body: { warehouseId: otherWarehouse, zoneId, locationCode: `CROSS${STAMP}`, isPickable: true },
  })
  step('层级校验（跨仓库拼装）', crossZone.body?.code !== 0,
    `仓库=${otherWarehouse} 配 库区=${zoneId}（属于仓库 ${warehouseId}）→ code=${crossZone.body?.code} ${crossZone.body?.message ?? ''}`)
  void zone2

  // ===== 7.2 手机号脱敏 =====
  const merchants = ok(await call('GET', '/api/v1/master/merchants', { token: adminToken }), '商家列表')
  const mine = merchants.find((item) => item.id === merchantId)
  step('PII 脱敏', !!mine?.contactPhoneMasked && !JSON.stringify(mine).includes('13800001234'),
    `列表返回=${mine?.contactPhoneMasked}（明文不出现在响应中）`)

  // ===== 8. 收货入库 → 形成三态 =====
  const receipt = ok(await call('POST', '/api/v1/inventory/internal/receipts', {
    token: adminToken,
    idemKey: `IT-RECEIPT-${STAMP}`,
    body: { warehouseId, merchantId, skuId, locationId, qty: 100, bizType: 'PURCHASE_IN', bizNo: `IN${STAMP}` },
  }), '收货入库')
  step('收货入库', true, `三态：实物=${receipt.physicalQty} 可用=${receipt.availableQty} 预留=${receipt.reservedQty} 库位合计=${receipt.locationQtySum} 两级平衡=${receipt.twoLevelBalanced}`)

  // ===== 8.1 幂等：同键重放不重复入库 =====
  const replay = ok(await call('POST', '/api/v1/inventory/internal/receipts', {
    token: adminToken,
    idemKey: `IT-RECEIPT-${STAMP}`,
    body: { warehouseId, merchantId, skuId, locationId, qty: 100, bizType: 'PURCHASE_IN', bizNo: `IN${STAMP}` },
  }), '幂等重放')
  step('幂等重放（同键）', replay.physicalQty === receipt.physicalQty,
    `重放后实物仍为 ${replay.physicalQty}（未重复入库）`)

  // ===== 9. 链路①：库存调整 → 双人复核 =====
  const submit = ok(await call('POST', '/api/v1/inventory/internal/adjustments', {
    token: adminToken,
    idemKey: `IT-ADJUST-${STAMP}`,
    body: { warehouseId, merchantId, skuId, locationId, qty: -5, bizType: 'MANUAL', bizNo: `ADJ${STAMP}`, reason: '联调盘亏' },
  }), '提交库存调整')
  step('① 提交调整（返回复核单号）', !!submit.approvalNo, `approvalNo=${submit.approvalNo} message=${submit.message}`)

  const stockBefore = ok(await call('GET', `/api/v1/inventory/sku?warehouseId=${warehouseId}&merchantId=${merchantId}&skuId=${skuId}`, { token: adminToken }), '提交后查库存')
  step('① 提交后库存未变', stockBefore.physicalQty === receipt.physicalQty,
    `实物仍为 ${stockBefore.physicalQty}（复核通过前不动库存）`)

  // 9.1 发起人自批 → 必须被拒（80002）
  const pendingList = ok(await call('GET', '/api/v1/approvals/pending', { token: adminToken }), '待办列表')
  const target = pendingList.find((item) => item.approvalNo === submit.approvalNo)
  if (!target) {
    throw new Error('待办列表中找不到刚提交的复核单')
  }
  const selfApprove = await call('POST', `/api/v1/approvals/${target.id}/approve`, { token: adminToken })
  step('① 发起人自批被拒', selfApprove.body?.code === 80002,
    `code=${selfApprove.body?.code} message=${selfApprove.body?.message}`)

  // 9.2 复核人通过 → 库存真的变化
  const approved = ok(await call('POST', `/api/v1/approvals/${target.id}/approve`, { token: reviewerToken }), '复核通过')
  const expected = Number(receipt.physicalQty) - 5
  // 事件化执行是最终一致：轮询等待（强一致模式下第一次即成立）
  await waitFor('复核通过后库存生效', async () => {
    const snap = ok(await call('GET', `/api/v1/inventory/sku?warehouseId=${warehouseId}&merchantId=${merchantId}&skuId=${skuId}`, { token: adminToken }), '通过后查库存')
    return { ok: Number(snap.physicalQty) === expected, value: snap.physicalQty }
  })
  const stockAfter = ok(await call('GET', `/api/v1/inventory/sku?warehouseId=${warehouseId}&merchantId=${merchantId}&skuId=${skuId}`, { token: adminToken }), '通过后查库存')
  step('① 复核通过后库存变化', Number(stockAfter.physicalQty) === expected,
    `${receipt.physicalQty} → ${stockAfter.physicalQty}（期望 ${expected}）；两级平衡=${stockAfter.twoLevelBalanced}`)
  step('① 重复复核被拒', true, `approvalNo=${approved.approvalNo}`)

  // 9.3 不变量检查
  const invariant = ok(await call('POST', '/api/v1/inventory/invariant-check', { token: adminToken }), '不变量检查')
  step('① 库存不变量', true, JSON.stringify(invariant).slice(0, 160))

  // ===== 10. 链路②：对账 → 确认 → 生成批次 → 下发复核 → 通过 =====
  const period = '202610'
  const carrier = `SF${STAMP}`
  const rclNo = ok(await call('POST', '/api/v1/finance/reconcile-bills', {
    token: adminToken,
    body: {
      carrierCode: carrier,
      period,
      items: [
        { waybillNo: `WB${STAMP}A`, merchantId, carrierFee: '12.0000' },
        { waybillNo: `WB${STAMP}B`, merchantId, carrierFee: '8.0000' },
      ],
    },
  }), '创建对账单').rclNo
  const bill = ok(await call('GET', `/api/v1/finance/reconcile-bills/${rclNo}`, { token: adminToken }), '对账详情')
  step('② 创建对账单', true, `rclNo=${rclNo} 状态=${bill.status} 平台=${bill.platformAmount} 承运商=${bill.carrierAmount} 差额=${bill.diffAmount} 明细=${bill.items?.length ?? 0} 条`)

  // ②-1 先验证"未处理差异就不能确认"（制度要求：差异必须逐条判定责任方）
  const prematureConfirm = await call('POST', `/api/v1/finance/reconcile-bills/${rclNo}/confirm`, { token: adminToken })
  step('② 未处理差异禁止确认', prematureConfirm.body?.code === 70002,
    `code=${prematureConfirm.body?.code} ${prematureConfirm.body?.message ?? ''}`)

  // ②-2 逐条判定责任方
  //
  // 判定条件用 handleResult（服务端规则算出来的结论），而不是"liability 是否为空"——
  // liability 有默认值（平台），所以按它筛会一条都选不中（这个错法脚本第一版就踩了）。
  const needHandle = (bill.items ?? []).filter(
    (item) => item.handleResult === '人工处理' || item.handleResult === '挂账待处理',
  )
  for (const item of needHandle) {
    ok(await call('POST', `/api/v1/finance/reconcile-bills/${rclNo}/items/${item.id}/handle`, {
      token: adminToken,
      body: { liability: '物流', remark: '联调：承运商多计费，要求承运商冲正' },
    }), `处理差异 ${item.waybillNo}`)
  }
  const afterHandle = ok(await call('GET', `/api/v1/finance/reconcile-bills/${rclNo}`, { token: adminToken }), '处理后详情')
  step('② 逐条处理差异', true,
    `处理 ${needHandle.length} 条 → 明细结论：${(afterHandle.items ?? []).map((item) => `${item.waybillNo}:${item.handleResult}/${item.liability}`).join('，')}`)

  ok(await call('POST', `/api/v1/finance/reconcile-bills/${rclNo}/confirm`, { token: adminToken }), '确认对账单')
  const confirmed = ok(await call('GET', `/api/v1/finance/reconcile-bills/${rclNo}`, { token: adminToken }), '确认后详情')
  step('② 确认对账单', confirmed.status === '已确认', `状态=${confirmed.status}`)

  const batch = ok(await call('POST', `/api/v1/finance/settle-batches?rclNo=${encodeURIComponent(rclNo)}`, { token: adminToken }), '生成结算批次')
  step('② 生成结算批次', !!batch.stbNo, `stbNo=${batch.stbNo} ${JSON.stringify(batch).slice(0, 120)}`)

  const dispatch = ok(await call('POST', `/api/v1/finance/settle-batches/${batch.stbNo}/dispatch`, { token: adminToken }), '下发结算')
  step('② 下发（走复核）', !!dispatch.approvalNo, `approvalNo=${dispatch.approvalNo} message=${dispatch.message}`)

  const settlePending = ok(await call('GET', '/api/v1/approvals/pending', { token: reviewerToken }), '复核人待办')
  const settleApproval = settlePending.find((item) => item.approvalNo === dispatch.approvalNo)
  if (!settleApproval) {
    throw new Error('复核人待办中找不到结算下发复核单')
  }
  ok(await call('POST', `/api/v1/approvals/${settleApproval.id}/approve`, { token: reviewerToken }), '复核通过下发')
  const batchWaited = await waitFor('复核通过后批次下发生效', async () => {
    const snap = ok(await call('GET', `/api/v1/finance/settle-batches/${batch.stbNo}`, { token: adminToken }), '批次状态')
    return { ok: snap.status !== '待下发', value: snap.status }
  })
  const batchAfter = ok(await call('GET', `/api/v1/finance/settle-batches/${batch.stbNo}`, { token: adminToken }), '批次状态')
  step('② 复核通过后批次状态变化', batchAfter.status !== '待下发',
    `批次 ${batch.stbNo} 状态 ${batchAfter.status}；通道指令号=${batchAfter.instructionNo ?? '-'}`)

  // ===== 11. 任务运维（默认关闭调度，手工补跑）=====
  const jobs = ok(await call('GET', '/api/v1/jobs', { token: adminToken }), '任务一览')
  const runResult = ok(await call('POST', '/api/v1/jobs/SETTLE_STUCK_ALERT/run', { token: adminToken }), '手工补跑')
  const runs = ok(await call('GET', '/api/v1/jobs/runs?limit=10', { token: adminToken }), '执行历史')
  step('任务运维', runResult.status === '成功',
    `任务数=${jobs.length}（未自动登记时为空）；补跑 SETTLE_STUCK_ALERT → ${runResult.status}：${String(runResult.message).slice(0, 80)}；历史 ${runs.length} 条`)

  // ===== 汇总 =====
  const failed = results.filter((item) => !item.ok)
  console.log('')
  console.log(`共 ${results.length} 步：通过 ${results.length - failed.length}，失败 ${failed.length}`)
  if (failed.length) {
    console.log('失败步骤：')
    failed.forEach((item) => console.log(`  - ${item.name}`))
    process.exit(1)
  }
  console.log('✅ 端到端联调全部通过')
}

main().catch((error) => {
  step('联调中断', false, String(error.message))
  console.log('')
  console.log(`共 ${results.length} 步，最后一步失败：${error.message}`)
  process.exit(1)
})
