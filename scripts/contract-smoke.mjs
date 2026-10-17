#!/usr/bin/env node
/**
 * 前后端**契约级**联调冒烟（不依赖任何浏览器/前端构建产物）。
 *
 * <p>为什么要单独做这一层：出库域（API-OUT-01…13）目前还没有前端页面，无法在 UI 里点。
 * 但前端与后端真正会打架的地方不在 UI，而在**调用约定**上——一旦约定不一致，
 * 症状是"页面空白/一直转圈/报成功却没生效"，且排查时双方各说各话。所以这里把
 * `wms-platform-web/src/utils/request.ts` 的约定**原样复刻**成脚本，逐条验证：
 *
 * <ol>
 *   <li>成功判定只看 <b>body.code === 0</b>（不是 HTTP 200 就算成功）；</li>
 *   <li>写操作必须带 <b>Idempotency-Key</b>，且"同动作重试复用同一个键"；</li>
 *   <li>分页结构必须是 <b>{ total: number, list: T[] }</b>；</li>
 *   <li>雪花 ID 必须是 <b>字符串</b>（JS number 只有 53 位，Long 会精度丢失）；</li>
 *   <li>失败时必须能拿到 <b>body.message</b> 给用户看，并区分 401（重新登录）/403（无权限）；</li>
 *   <li>列表不返回收件人明文（只回掩码）。</li>
 * </ol>
 *
 * <p>用法（后端已在跑时）：
 * <pre>
 * node scripts/contract-smoke.mjs \
 *   --base http://127.0.0.1:28088 \
 *   --admin it_admin --admin-pwd ItPassw0rd! \
 *   --worker it_worker --worker-pwd ItPassw0rd!
 * </pre>
 *
 * <p>退出码：全部通过 0；有失败 1（可直接挂进 CI，但需要先有上述两个账号，故默认不纳入 CI）。
 */

const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  if (process.argv[i].startsWith('--')) args.set(process.argv[i].slice(2), process.argv[i + 1]);
}

const BASE = args.get('base') ?? 'http://127.0.0.1:28088';
const ADMIN = { username: args.get('admin') ?? 'it_admin', password: args.get('admin-pwd') ?? 'ItPassw0rd!' };
const WORKER = { username: args.get('worker') ?? 'it_worker', password: args.get('worker-pwd') ?? 'ItPassw0rd!' };

/** 每次运行一个随机后缀：保证重复运行不会撞上上一轮的幂等键，但**同一用例内**复用同一键。 */
const RUN = Math.random().toString(36).slice(2, 8);

const results = [];

/**
 * 记录一条结果。
 *
 * @param {string} name 用例名
 * @param {boolean} ok 是否通过
 * @param {string} detail 说明
 */
function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
}

/**
 * 断言。
 *
 * @param {string} name 用例名
 * @param {boolean} cond 条件
 * @param {string} detail 说明
 */
function check(name, cond, detail) {
  record(name, !!cond, detail);
}

/**
 * 发一次请求（复刻 request.ts：Bearer 头 + 写操作幂等键 + code===0 判定）。
 *
 * <p>注意：这里**不自动重试**，也**不吞异常**——联调脚本要看到原始状态码与 body。
 *
 * @param {string} method 方法
 * @param {string} path 路径（含查询串）
 * @param {object} [opts] 选项
 * @returns {Promise<{status:number, body:any, data:any}>} 结果
 */
async function call(method, path, opts = {}) {
  const headers = { Accept: 'application/json' };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  // 前端约定：写方法自动带幂等键（同动作重试复用同一个键）
  if (method !== 'GET' && opts.idemKey) headers['Idempotency-Key'] = opts.idemKey;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';

  const res = await fetch(BASE + path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  let body = null;
  const text = await res.text();
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text };
  }
  return { status: res.status, body, data: body?.data };
}

/**
 * 登录拿 token（前端 LoginView 走的就是这个接口）。
 *
 * @param {{username:string,password:string}} cred 账号
 * @returns {Promise<any>} 登录响应的 data
 */
async function login(cred) {
  const r = await call('POST', '/api/v1/auth/login', {
    body: { username: cred.username, password: cred.password },
    idemKey: `smoke-login-${RUN}-${cred.username}`,
  });
  return { ...r, login: r.data };
}

function isSnowflakeString(v) {
  return typeof v === 'string' && /^\d{10,25}$/.test(v);
}

function isIsoLikeOrDateTime(v) {
  return typeof v === 'string' && /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/.test(v);
}

async function main() {
  console.log(`\n契约联调 @ ${BASE}  (run=${RUN})\n`);

  // ---------------------------------------------------------------- 登录
  const adminLogin = await login(ADMIN);
  check('登录（超管）：code===0 且返回 token',
    adminLogin.status === 200 && adminLogin.body?.code === 0 && typeof adminLogin.login?.token === 'string',
    `HTTP ${adminLogin.status}, code=${adminLogin.body?.code}`);
  const adminToken = adminLogin.login?.token;

  // ---------------------------------------------------------------- 分页结构
  const page = await call('GET', '/api/v1/outbound/orders?pageNum=1&pageSize=5', { token: adminToken });
  check('分页：code===0 / total 是 number / list 是数组',
    page.body?.code === 0 && typeof page.data?.total === 'number' && Array.isArray(page.data?.list),
    `total=${page.data?.total} (${typeof page.data?.total}), list=${Array.isArray(page.data?.list) ? 'array' : typeof page.data?.list}`);
  const firstRow = page.data?.list?.[0];
  if (firstRow) {
    check('分页行：雪花 ID 是字符串（前端 SnowflakeId=string）',
      isSnowflakeString(firstRow.id) || typeof firstRow.id === 'string',
      `id=${firstRow.id} (${typeof firstRow.id})`);
    check('分页行：时间字段可被前端直接解析',
      isIsoLikeOrDateTime(firstRow.createdAt),
      `createdAt=${firstRow.createdAt}`);
  }

  // ---------------------------------------------------------------- 建单 + 幂等
  const orderNo = `SMOKE-${RUN}`;
  const createBody = {
    warehouseId: 1, merchantId: 1, sourceType: 'API', sourceOrderNo: orderNo,
    carrierCode: 'SF', carrierName: '顺丰', estimatedFreight: 10.5,
    expectedWeight: 2.0, expectedVolume: 0.1,
    receiverName: '联调收件人', receiverPhone: '13900000001',
    receiverAddress: '上海市浦东新区张江路 1 号', receiverProvince: '上海市',
    receiverCity: '上海市', receiverDistrict: '浦东新区', remark: '契约联调',
    items: [{ skuCode: 'SKU-V-001', qty: 2, unit: '件' }],
  };
  const createKey = `smoke-create-${RUN}`;
  const created = await call('POST', '/api/v1/outbound/orders', { token: adminToken, body: createBody, idemKey: createKey });
  check('建单（超管）：code===0 且返回 doNo',
    created.body?.code === 0 && typeof created.data?.doNo === 'string',
    `HTTP ${created.status}, code=${created.body?.code}, doNo=${created.data?.doNo}`);
  const doNo = created.data?.doNo;

  // 同键同体 → 重放返回同一单
  const replay = await call('POST', '/api/v1/outbound/orders', { token: adminToken, body: createBody, idemKey: createKey });
  check('幂等重放：同键同体返回同一 doNo（前端重试安全）',
    replay.body?.code === 0 && replay.data?.doNo === doNo,
    `replay doNo=${replay.data?.doNo}`);

  // 同键异体 → 409 + 10002
  const mismatched = await call('POST', '/api/v1/outbound/orders', {
    token: adminToken, body: { ...createBody, items: [{ skuCode: 'SKU-V-001', qty: 3, unit: '件' }] }, idemKey: createKey,
  });
  check('幂等冲突：同键异体 → HTTP 409 + code 10002（前端应提示而非静默）',
    mismatched.status === 409 && mismatched.body?.code === 10002,
    `HTTP ${mismatched.status}, code=${mismatched.body?.code}, msg=${mismatched.body?.message}`);

  // 写接口漏带幂等键 → 必须被拦（前端若忘了传，会立刻暴露而非偶发重复下单）
  const noKey = await call('POST', '/api/v1/outbound/orders', { token: adminToken, body: { ...createBody, sourceOrderNo: orderNo + '-NOKEY' } });
  check('漏带 Idempotency-Key → 400（前端写操作必须带）',
    noKey.status === 400 || noKey.status === 428,
    `HTTP ${noKey.status}, code=${noKey.body?.code}`);

  // ---------------------------------------------------------------- 详情 / 轨迹 / 脱敏
  const detail = await call('GET', `/api/v1/outbound/orders/${doNo}`, { token: adminToken });
  check('详情：明细数组 + 状态字段',
    detail.body?.code === 0 && Array.isArray(detail.data?.items) && typeof detail.data?.status === 'string',
    `status=${detail.data?.status}, items=${detail.data?.items?.length}`);
  const masked = detail.data?.receiver ?? {};
  check('详情：收件人只回掩码（无明文手机号/地址）',
    typeof masked.phoneMasked === 'string' && masked.phoneMasked.includes('*')
      && !JSON.stringify(detail.data).includes('13900000001'),
    `phoneMasked=${masked.phoneMasked}`);

  const logs = await call('GET', `/api/v1/outbound/orders/${doNo}/status-logs`, { token: adminToken });
  check('状态轨迹：数组且首条是 CREATE',
    logs.body?.code === 0 && Array.isArray(logs.data) && logs.data[0]?.event === 'CREATE',
    `logs=${logs.data?.length}, first=${logs.data?.[0]?.event}`);

  // ---------------------------------------------------------------- 权限：作业员
  const workerLogin = await login(WORKER);
  check('登录（作业员）：code===0',
    workerLogin.status === 200 && workerLogin.body?.code === 0,
    `permissions=${JSON.stringify(workerLogin.login?.permissions)}`);
  const workerToken = workerLogin.login?.token;

  const workerQuery = await call('GET', '/api/v1/outbound/orders?pageNum=1&pageSize=1', { token: workerToken });
  check('作业员：有 outbound:order:query → 查得到',
    workerQuery.body?.code === 0,
    `code=${workerQuery.body?.code}`);

  const workerCreate = await call('POST', '/api/v1/outbound/orders', {
    token: workerToken, body: { ...createBody, sourceOrderNo: orderNo + '-W' }, idemKey: `smoke-w-${RUN}`,
  });
  check('作业员：无 outbound:order:create → HTTP 403 + code 40300（前端提示"找管理员要权限"）',
    workerCreate.status === 403 && workerCreate.body?.code === 40300,
    `HTTP ${workerCreate.status}, code=${workerCreate.body?.code}`);

  // ---------------------------------------------------------------- 认证与协议细节
  const anonymous = await (async () => {
    const res = await fetch(`${BASE}/api/v1/outbound/orders`);
    return { status: res.status, body: await res.json().catch(() => null) };
  })();
  check('未登录：HTTP 401 + code 40300（前端跳登录）',
    anonymous.status === 401 && anonymous.body?.code === 40300,
    `HTTP ${anonymous.status}, code=${anonymous.body?.code}`);

  const wrongMethod = await call('GET', `/api/v1/outbound/orders/${doNo}/load`, { token: adminToken });
  check('协议：GET 打 POST-only 接口 → 405（不是 500）',
    wrongMethod.status === 405,
    `HTTP ${wrongMethod.status}, code=${wrongMethod.body?.code}`);

  const notFound = await call('GET', '/api/v1/outbound/orders/DO-NOT-EXIST', { token: adminToken });
  check('不存在单号 → 10001 且带可读 message',
    notFound.body?.code === 10001 && typeof notFound.body?.message === 'string',
    `code=${notFound.body?.code}, msg=${notFound.body?.message}`);

  // ---------------------------------------------------------------- 作业全链路（前端按钮序列）
  const flow = [
    ['dispatch', { items: [{ lineNo: 1, locationCode: 'V-01-01-01' }] }, '拣货中'],
    ['pick-complete', { items: [{ lineNo: 1, pickedQty: 2 }] }, '已复核'],
    ['measure', { actualWeight: 2.0, actualVolume: 0.1, packageCount: 1 }, '已复核'],
    ['pack', { waybillNo: `WB-${RUN}`, packageCount: 1 }, '已打包'],
    ['handover', { remark: '交接给装卸' }, '待装车'],
    ['load', { remark: '装车完成' }, '已出库'],
  ];
  for (const [action, body, expectStatus] of flow) {
    const r = await call('POST', `/api/v1/outbound/orders/${doNo}/${action}`, {
      token: adminToken, body, idemKey: `smoke-${action}-${RUN}`,
    });
    check(`作业动作 ${action} → ${expectStatus}`,
      r.body?.code === 0 && r.data?.status === expectStatus,
      `code=${r.body?.code}, status=${r.data?.status}, msg=${r.body?.message ?? ''}`);
  }

  // ---------------------------------------------------------------- 取消（另一张单）
  const toCancel = await call('POST', '/api/v1/outbound/orders', {
    token: adminToken,
    body: { ...createBody, sourceOrderNo: orderNo + '-C' },
    idemKey: `smoke-create-c-${RUN}`,
  });
  const cancelNo = toCancel.data?.doNo;
  const cancelled = await call('POST', `/api/v1/outbound/orders/${cancelNo}/cancel`, {
    token: adminToken, body: { reason: '契约联调取消' }, idemKey: `smoke-cancel-${RUN}`,
  });
  check('取消：待接单 → 已取消，且带回 cancelStage',
    cancelled.body?.code === 0 && cancelled.data?.status === '已取消' && cancelled.data?.cancelStage === '待接单',
    `status=${cancelled.data?.status}, cancelStage=${cancelled.data?.cancelStage}`);

  const cancelShipped = await call('POST', `/api/v1/outbound/orders/${doNo}/cancel`, {
    token: adminToken, body: { reason: '已出库还想取消' }, idemKey: `smoke-cancel-shipped-${RUN}`,
  });
  check('取消已出库 → 409 + 20002（不可逆点，前端应给出明确文案）',
    cancelShipped.status === 409 && cancelShipped.body?.code === 20002,
    `HTTP ${cancelShipped.status}, code=${cancelShipped.body?.code}, msg=${cancelShipped.body?.message}`);

  // ---------------------------------------------------------------- 汇总
  const failed = results.filter((r) => !r.ok);
  console.log(`\n合计 ${results.length} 项：通过 ${results.length - failed.length}，失败 ${failed.length}`);
  if (failed.length) {
    console.log('\n失败明细：');
    for (const f of failed) console.log(`  - ${f.name}${f.detail ? '：' + f.detail : ''}`);
  }
  process.exit(failed.length ? 1 : 0);
}

main().catch((ex) => {
  console.error('联调脚本异常：', ex);
  process.exit(2);
});
