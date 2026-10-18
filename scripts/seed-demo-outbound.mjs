#!/usr/bin/env node
/**
 * 造演示数据：把出库单铺满**全部 9 个状态**，方便在页面上逐个点。
 *
 * # 为什么走接口而不是直接 INSERT
 *
 * 一张出库单的"正确样子"不只是 `out_order` 里那行数据，它还牵着：
 * 库存预占（`iv_sku.reserved`）、库位分配（`iv_location.allocated`）、运费冻结（`ac_fee_freeze`）、
 * 装运单（`lg_shipment`）、库存流水（`iv_movement`）、状态轨迹（`pf_biz_status_log`）。
 * 直接改库造出来的单，点开详情就会出现"状态是待装车、但库存没扣、面单没绑"的自相矛盾，
 * 测试时会误判成 bug。所以这里**全部通过真实接口**跑出来，副作用天然自洽。
 *
 * # 用法
 *
 * ```bash
 * # 1) 先补主数据（商家/商品/库位/账户/账号）
 * mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wms_docker < scripts/seed-demo-master.sql
 *
 * # 2) 再造订单（默认 18 张 + 1 张部分发货余量单）
 * node scripts/seed-demo-outbound.mjs --base http://127.0.0.1:28088
 * ```
 *
 * 参数：`--base`（默认网关 28088）、`--user`/`--pwd`（默认 it_admin/ItPassw0rd!）、
 * `--per-status`（每个状态造几张，默认 2）、`--topup`（先补多少库存，默认 500/商品）。
 *
 * 退出码：0 = 全部造完；非 0 = 有场景失败（会打印到哪一步）。
 */

const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  if (process.argv[i].startsWith('--')) args.set(process.argv[i].slice(2), process.argv[i + 1])
}

/** 网关地址。 */
const BASE = args.get('base') ?? 'http://127.0.0.1:28088'
/** 造单账号（需要超管或全量出库权限）。 */
const USER = args.get('user') ?? 'it_admin'
/** 口令。 */
const PWD = args.get('pwd') ?? 'ItPassw0rd!'
/** 每个状态造几张。 */
const PER_STATUS = Number(args.get('per-status') ?? 2)
/** 补库存量。 */
const TOPUP = Number(args.get('topup') ?? 500)

/** 每轮唯一后缀（防"上游单号重复"唯一键）。 */
const RUN = Date.now().toString(36).toUpperCase()

/** 演示用的商家/商品/库位（与 seed-demo-master.sql 一一对应）。 */
const DEMO = {
  warehouseId: '1',
  merchantA: '1',
  merchantB: '2',
  skuA: 'SKU-V-001',
  skuC: 'SKU-V-003',
  skuB: 'SKU-V-002',
  /** 库位 ID（收货接口要 ID）。 */
  locationId: 2001,
  locationId2: 2002,
  /** 库位编码（派发接口要编码）。 */
  locationCode: 'V-01-01-01',
  locationCode2: 'V-01-01-02',
}

/**
 * 场景 → ASCII slug。
 *
 * **为什么要这层**：上游单号会进 `Idempotency-Key` 请求头，而 HTTP 头只允许 ASCII。
 * 直接拼中文会抛 `Cannot convert argument to a ByteString`（真踩过）。
 * 中文文案只留在 body（remark）里。
 */
const SCENARIO_SLUG = {
  待接单: 'PENDING',
  拣货中: 'PICKING',
  缺货待处理: 'SHORTAGE',
  已复核: 'CHECKED',
  已打包: 'PACKED',
  待装车: 'AWAITING',
  已出库: 'SHIPPED',
  已取消: 'CANCELLED',
  已拦截: 'HELD',
  部分发货: 'PARTIAL',
}

/**
 * 取场景的 ASCII 标识。
 *
 * @param {string} scenario 场景（中文）
 * @returns {string} slug
 */
function slugOf(scenario) {
  return SCENARIO_SLUG[scenario] ?? 'MISC'
}

/** 会话令牌。 */
let TOKEN = ''

/**
 * 造单结果：**单号 → 最终状态**。
 *
 * 注意不能按"途经状态"统计：一张单会依次经过 待接单 → 拣货中 → … → 已出库，
 * 若每个途经状态都记一笔，汇总会变成"待接单 20 张"这种误导数字（第一版就是这么错的）。
 */
const created = new Map()

/**
 * 发一次请求。
 *
 * @param {string} method 方法
 * @param {string} path 路径
 * @param {object} [body] 负载
 * @param {string} [idemKey] 幂等键（写操作必传）
 * @returns {Promise<any>} 业务数据（code!==0 抛错）
 */
async function call(method, path, body, idemKey) {
  const headers = { Accept: 'application/json' }
  if (TOKEN) headers.Authorization = `Bearer ${TOKEN}`
  if (method !== 'GET') {
    headers['Content-Type'] = 'application/json; charset=utf-8'
    headers['Idempotency-Key'] = idemKey ?? `${method}-${path}-${Math.random().toString(36).slice(2)}`
  }
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    throw new Error(`${method} ${path} 返回非 JSON：${text.slice(0, 120)}`)
  }
  if (!json || json.code !== 0) {
    throw new Error(`${method} ${path} 失败：HTTP ${res.status} code=${json?.code} msg=${json?.message}`)
  }
  return json.data
}

/**
 * 登录。
 */
async function login() {
  const data = await call('POST', '/api/v1/auth/login', { username: USER, password: PWD })
  TOKEN = data.token
  console.log(`登录成功：${data.username}（adminFlag=${data.adminFlag}）`)
}

/**
 * 补库存（走收货物料接口，保证 SKU 三态与库位账同时起来）。
 *
 * @param {string} merchantId 商家
 * @param {number} skuId 商品
 * @param {number} skuCode 条码（仅日志用）
 * @param {string} locationId 库位
 * @param {number} qty 数量
 */
async function topUp(merchantId, skuId, skuCode, locationId, qty) {
  await call(
    'POST',
    '/api/v1/inventory/internal/receipts',
    {
      warehouseId: Number(DEMO.warehouseId),
      merchantId: Number(merchantId),
      skuId,
      locationId: Number(locationId),
      qty,
      bizType: 'ASN',
      bizId: 0,
      bizNo: `ASN-DEMO-${RUN}-${skuCode}`,
      reason: '演示数据补货',
    },
    `RECV-${RUN}-${skuCode}-${merchantId}-${locationId}`,
  )
  console.log(`补库存：${skuCode} × ${qty}（商家 ${merchantId}，库位 ${locationId}）`)
}

/**
 * 建一张单。
 *
 * @param {object} o 参数
 * @returns {Promise<any>} 详情
 */
async function createOrder(o) {
  return call(
    'POST',
    '/api/v1/outbound/orders',
    {
      warehouseId: Number(DEMO.warehouseId),
      merchantId: Number(o.merchantId),
      sourceType: 'API',
      sourceOrderNo: o.sourceNo,
      carrierCode: o.carrier ?? 'SF',
      carrierName: '顺丰',
      estimatedFreight: o.freight ?? 12.5,
      expectedWeight: o.expectedWeight ?? 2,
      expectedVolume: 0.1,
      receiverName: o.receiverName ?? '张三',
      receiverPhone: o.receiverPhone ?? '13800000000',
      receiverAddress: '上海市浦东新区张江路 1 号 3 幢 201',
      receiverProvince: '上海市',
      receiverCity: '上海市',
      receiverDistrict: '浦东新区',
      remark: o.remark ?? `演示单 ${o.sourceNo}`,
      items: o.items,
    },
    `CREATE-${o.sourceNo}`,
  )
}

/**
 * 派发全部明细到指定库位。
 *
 * @param {any} detail 详情
 * @param {string} locationCode 库位编码
 * @returns {Promise<any>} 详情
 */
async function dispatchAll(detail, locationCode) {
  return call(
    'POST',
    `/api/v1/outbound/orders/${detail.doNo}/dispatch`,
    { items: detail.items.map((item) => ({ lineNo: item.lineNo, locationCode })) },
    `DISPATCH-${detail.doNo}`,
  )
}

/**
 * 全部明细拣货完成（实拣 = 应发）。
 *
 * @param {any} detail 详情
 * @returns {Promise<any>} 详情
 */
async function pickAll(detail) {
  return call(
    'POST',
    `/api/v1/outbound/orders/${detail.doNo}/pick-complete`,
    { items: detail.items.map((item) => ({ lineNo: item.lineNo, pickedQty: item.qty })) },
    `PICK-${detail.doNo}`,
  )
}

/**
 * 称重（按应收值，避免触发 20% 偏差拦停）。
 *
 * @param {any} detail 详情
 * @returns {Promise<any>} 详情
 */
async function measure(detail) {
  return call(
    'POST',
    `/api/v1/outbound/orders/${detail.doNo}/measure`,
    { actualWeight: detail.expectedWeight ?? 2, actualVolume: 0.1, packageCount: 1 },
    `MEASURE-${detail.doNo}`,
  )
}

/**
 * 打包绑面单。
 *
 * @param {any} detail 详情
 * @returns {Promise<any>} 详情
 */
async function pack(detail) {
  return call(
    'POST',
    `/api/v1/outbound/orders/${detail.doNo}/pack`,
    { waybillNo: `WB-${detail.doNo.slice(-8)}`, packageCount: 1 },
    `PACK-${detail.doNo}`,
  )
}

/**
 * 扫码交接。
 *
 * @param {any} detail 详情
 * @returns {Promise<any>} 详情
 */
async function handover(detail) {
  return call(
    'POST',
    `/api/v1/outbound/orders/${detail.doNo}/handover`,
    { remark: '交接给装卸组' },
    `HANDOVER-${detail.doNo}`,
  )
}

/**
 * 记录/覆盖某单的当前状态（后写覆盖前写，最终留下的是终态）。
 *
 * @param {string} status 状态
 * @param {string} doNo 单号
 */
function remember(status, doNo) {
  created.set(doNo, status)
}

/**
 * 造一个指定场景的单。
 *
 * @param {string} scenario 场景（= 目标状态）
 * @param {number} index 序号（用于区分上游单号）
 */
async function buildScenario(scenario, index) {
  // 1. 商家/明细按场景区分：一半单商家、一半多行（多行能测派发/拣货的逐行输入）
  const isMerchantB = index % 2 === 1
  const multiLine = index % 2 === 0
  const items = isMerchantB
    ? [{ skuCode: DEMO.skuB, qty: '1', unit: '件' }]
    : multiLine
      ? [{ skuCode: DEMO.skuA, qty: '2', unit: '件' }, { skuCode: DEMO.skuC, qty: '1', unit: '箱' }]
      : [{ skuCode: DEMO.skuA, qty: '3', unit: '件' }]

  const sourceNo = `DEMO-${RUN}-${slugOf(scenario)}-${index}`
  let detail = await createOrder({
    merchantId: isMerchantB ? DEMO.merchantB : DEMO.merchantA,
    sourceNo,
    items,
    freight: 10 + index,
    receiverName: `收件人${index}`,
    receiverPhone: `1380000${String(1000 + index).slice(-4)}`,
    remark: `演示：${scenario}`,
  })
  remember('待接单', detail.doNo)

  // 2. 按目标状态逐步推进（每一步都是真实动作，副作用自洽）
  if (scenario === '待接单') {
    return
  }
  if (scenario === '已取消') {
    await call('POST', `/api/v1/outbound/orders/${detail.doNo}/cancel`, { reason: '演示：商家撤单' }, `CANCEL-${detail.doNo}`)
    remember('已取消', detail.doNo)
    return
  }
  if (scenario === '已拦截') {
    await call('POST', `/api/v1/outbound/orders/${detail.doNo}/hold`, { reason: '演示：疑似禁入品' }, `HOLD-${detail.doNo}`)
    remember('已拦截', detail.doNo)
    return
  }

  detail = await dispatchAll(detail, index % 2 === 0 ? DEMO.locationCode : DEMO.locationCode2)
  remember('拣货中', detail.doNo)
  if (scenario === '拣货中') {
    return
  }
  if (scenario === '缺货待处理') {
    // 第 1 行缺 1 个 → 走缺货上报（差额释放预占）
    await call(
      'POST',
      `/api/v1/outbound/orders/${detail.doNo}/shortage`,
      { items: [{ lineNo: detail.items[0].lineNo, shortageQty: 1 }], reason: '演示：货架缺货' },
      `SHORTAGE-${detail.doNo}`,
    )
    remember('缺货待处理', detail.doNo)
    return
  }

  detail = await pickAll(detail)
  remember('已复核', detail.doNo)
  if (scenario === '已复核') {
    return
  }
  if (scenario === '已打包') {
    detail = await measure(detail)
    detail = await pack(detail)
    remember('已打包', detail.doNo)
    return
  }

  detail = await measure(detail)
  detail = await pack(detail)
  detail = await handover(detail)
  remember('待装车', detail.doNo)
  if (scenario === '待装车') {
    return
  }
  if (scenario === '部分发货') {
    // 首行发一半，余量生成新单（新单会落在"待接单"）
    const first = detail.items[0]
    const shipQty = (Number(first.qty) / 2).toFixed(4)
    const result = await call(
      'POST',
      `/api/v1/outbound/orders/${detail.doNo}/partial-ship`,
      { items: [{ lineNo: first.lineNo, shipQty }], reason: '演示：客户只要一半' },
      `PARTIAL-${detail.doNo}`,
    )
    remember('已出库（部分发货）', detail.doNo)
    remember('待接单（余量单）', result.newDoNo)
    return
  }

  await call('POST', `/api/v1/outbound/orders/${detail.doNo}/load`, { remark: '演示：装车完成' }, `LOAD-${detail.doNo}`)
  remember('已出库', detail.doNo)
}

async function main() {
  console.log(`\n造演示数据 @ ${BASE}  (run=${RUN}, 每状态 ${PER_STATUS} 张)\n`)
  await login()

  // 1. 补库存：**两个库位都要补**——派发会轮流用 2001/2002，
  //    只补一个库位时，派到另一个库位的单会在"拣货完成"被 50002 拦下（后端是对的，是脚本漏了）
  const topupPerLocation = Math.ceil(TOPUP / 2)
  for (const locationId of [DEMO.locationId, DEMO.locationId2]) {
    await topUp(DEMO.merchantA, 1001, DEMO.skuA, locationId, topupPerLocation)
    await topUp(DEMO.merchantA, 1003, DEMO.skuC, locationId, topupPerLocation)
    await topUp(DEMO.merchantB, 1002, DEMO.skuB, locationId, topupPerLocation)
  }

  // 2. 逐状态造单（顺序刻意从"早状态"到"终态"，中途失败时已造的仍可用）
  const scenarios = ['待接单', '拣货中', '缺货待处理', '已复核', '已打包', '待装车', '已出库', '已取消', '已拦截', '部分发货']
  for (const scenario of scenarios) {
    for (let i = 0; i < PER_STATUS; i++) {
      try {
        await buildScenario(scenario, i)
        console.log(`  ✓ ${scenario} 第 ${i + 1} 张`)
      } catch (error) {
        console.log(`  ✗ ${scenario} 第 ${i + 1} 张失败：${error.message}`)
      }
    }
  }

  // 3. 汇总（`created` 是「单号 → 最终状态」，先按状态分组再打印）
  console.log('\n=== 造单结果（按最终状态）===')
  const byStatus = new Map()
  for (const [doNo, status] of created) {
    if (!byStatus.has(status)) {
      byStatus.set(status, [])
    }
    byStatus.get(status).push(doNo)
  }
  for (const [status, list] of byStatus) {
    console.log(`${status.padEnd(12, ' ')} ${String(list.length).padStart(2)} 张   ${list.slice(0, 2).join(', ')}${list.length > 2 ? ' …' : ''}`)
  }
  console.log(`合计 ${created.size} 张（含部分发货产生的余量单）`)
}

main().catch((error) => {
  console.error('造数据失败：', error)
  process.exit(1)
})
