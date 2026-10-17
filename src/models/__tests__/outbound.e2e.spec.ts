/**
 * 出库域**前端模型层 ↔ 真实后端**的联调用例（不是 mock）。
 *
 * ## 为什么这么做
 *
 * 页面写完后，"能不能用"的瓶颈通常不在 Vue 模板，而在**模型层**：
 * `toParams()` 少传/多传一个字段、数值被转成 Number 丢了精度、幂等键复用粒度不对——
 * 这些问题点开页面也可能看不出来（比如后端按默认值兜底了），但会在某个边界上炸。
 *
 * 这个用例直接调用 `src/models/outbound.ts` 里的 `OutboundApi` 与 Qo 类，
 * 走**真实的请求层**（axios 拦截器 → `Idempotency-Key` → `code===0` 判定），对一个真后端跑完整链路。
 * 等价于"页面点一遍"，但可重复、可定位到具体一步。
 *
 * ## 运行方式（默认跳过，不影响 `pnpm test` 与 CI）
 *
 * ```bash
 * # 后端已在运行（网关 8088；本仓库的 Docker 编排是 28088）
 * WMS_E2E_BASE=http://127.0.0.1:28088 pnpm test
 * ```
 *
 * 依赖：后端库里有 `scripts/seed-smoke-users.sql` 种好的 `it_admin`，
 * 以及主数据 `SKU-V-001` + 库位 `V-01-01-01`、库存 ≥ 10（见 wms-platform README §57/§59）。
 */
import { beforeAll, describe, expect, it } from 'vitest'
import http, { configureAuthBridge } from '@/utils/request'
import { AuthApi, LoginQo } from '@/models/auth'
import {
  CreateOutOrderQo,
  MeasureQo,
  OutOrderLineQo,
  OutOrderQueryQo,
  OutOrderStatus as Status,
  OutboundApi,
  PackQo,
  ReasonQo,
  RemarkQo,
  allowedActions,
  sameDayLabel,
  statusTagType,
} from '@/models/outbound'

/** 真后端地址（为空则整组跳过：CI 里没有后端）。 */
const BASE = process.env.WMS_E2E_BASE ?? ''

/** 有后端才跑的用例组。 */
const describeE2E = BASE ? describe : describe.skip

/** 每轮用不同的上游单号，避免撞上"防重复下单"唯一键。 */
const RUN = `UI-${Date.now().toString(36)}`

describeE2E('出库域：前端模型层 × 真实后端', () => {
  beforeAll(async () => {
    // 1. axios 在 Node 里没有 origin，必须给绝对 base（浏览器里是 vite 代理）
    http.defaults.baseURL = BASE
    // 2. 真实登录拿 token（走 request.ts 的公开路径豁免，不需要幂等键）
    const qo = new LoginQo()
    qo.username = process.env.WMS_E2E_USER ?? 'it_admin'
    qo.password = process.env.WMS_E2E_PWD ?? 'ItPassw0rd!'
    const session = await AuthApi.login(qo)
    // 3. 注入 token 提供者（等价于 auth store 在应用启动时做的事）
    configureAuthBridge(() => session.token, () => undefined)
  })

  it('状态机与展示口径（纯前端，不需要后端）', () => {
    // 1. 待接单只允许派发/取消/拦截
    expect(allowedActions(Status.PENDING)).toEqual(['dispatch', 'cancel', 'hold'])
    // 2. 已出库是终态，没有任何动作（前端不渲染按钮，后端也会拒）
    expect(allowedActions(Status.SHIPPED)).toEqual([])
    // 3. 批次与标签色走映射，不在模板里写三元
    expect(sameDayLabel(true)).toBe('当日单')
    expect(statusTagType(Status.SHIPPED)).toBe('success')
    expect(statusTagType(Status.CANCELLED)).toBe('danger')
  })

  it('建单 → 派发 → 拣货 → 称重 → 打包 → 交接 → 装车（页面按钮的真实调用序列）', async () => {
    // 1. 建单（Qo.toParams 会剔除空字段；幂等键由拦截器生成）
    const create = new CreateOutOrderQo()
    create.warehouseId = '1'
    create.merchantId = '1'
    create.sourceOrderNo = `${RUN}-A`
    create.carrierCode = 'SF'
    create.carrierName = '顺丰'
    create.estimatedFreight = '10.5'
    create.expectedWeight = '2'
    create.receiverName = '前端联调'
    create.receiverPhone = '13900000002'
    create.receiverAddress = '上海市浦东新区张江路 1 号'
    create.receiverProvince = '上海市'
    create.receiverCity = '上海市'
    create.receiverDistrict = '浦东新区'
    create.items[0].skuCode = 'SKU-V-001'
    create.items[0].qty = '2'
    create.items[0].unit = '件'
    const created = await OutboundApi.create(create)
    expect(created.status).toBe(Status.PENDING)
    expect(created.doNo).toMatch(/^DO/)
    const doNo = created.doNo

    // 2. 详情 + 轨迹（页面首屏的两个请求）
    const detail = await OutboundApi.detail(doNo)
    expect(detail.items).toHaveLength(1)
    expect(detail.receiver.phoneMasked).toContain('*')
    expect((await OutboundApi.statusLogs(doNo))[0].event).toBe('CREATE')

    // 3. 派发：行号 + 库位编码（行号来自 detail.items[].lineNo）
    const lineNo = detail.items[0].lineNo
    expect((await OutboundApi.dispatch(doNo, [new OutOrderLineQo(lineNo, 'V-01-01-01')])).status)
      .toBe(Status.PICKING)

    // 4. 拣货完成：实拣 = 应发（否则后端会要求走缺货上报）
    expect((await OutboundApi.pickComplete(doNo, [new OutOrderLineQo(lineNo, '2')])).status)
      .toBe(Status.CHECKED)

    // 5. 称重（页面预填应收值；偏差 > 20% 会被 20003 拦停）
    const measure = new MeasureQo()
    measure.actualWeight = '2'
    measure.actualVolume = '0.1'
    measure.packageCount = '1'
    expect((await OutboundApi.measure(doNo, measure)).weightChecked).toBe(true)

    // 6. 打包：绑面单
    const pack = new PackQo()
    pack.waybillNo = `WB-${RUN}-A`
    pack.packageCount = '1'
    expect((await OutboundApi.pack(doNo, pack)).status).toBe(Status.PACKED)

    // 7. 扫码交接
    expect((await OutboundApi.handover(doNo, new RemarkQo('交接给装卸'))).handoverConfirmed).toBe(true)

    // 8. 装车（不可逆）→ 已出库，且终态没有可执行动作
    const shipped = await OutboundApi.load(doNo, new RemarkQo('装车完成'))
    expect(shipped.status).toBe(Status.SHIPPED)
    expect(allowedActions(shipped.status)).toEqual([])
  })

  it('列表分页：PageResult 结构可被页面直接绑定', async () => {
    const qo = new OutOrderQueryQo()
    qo.pageNum = 1
    qo.pageSize = 5
    qo.status = Status.SHIPPED
    const page = await OutboundApi.page(qo)
    expect(typeof page.total).toBe('number')
    expect(Array.isArray(page.list)).toBe(true)
    // 1. 筛选生效：全部都是已出库
    for (const row of page.list) {
      expect(row.status).toBe(Status.SHIPPED)
      expect(typeof row.id).toBe('string')
    }
  })

  it('取消：待接单可取消，已出库被拒且错误文案可直接展示', async () => {
    // 1. 新建一张单
    const create = new CreateOutOrderQo()
    create.warehouseId = '1'
    create.merchantId = '1'
    create.sourceOrderNo = `${RUN}-C`
    create.carrierCode = 'SF'
    create.estimatedFreight = '1'
    create.receiverName = '前端联调'
    create.receiverPhone = '13900000003'
    create.receiverAddress = '上海市浦东新区张江路 1 号'
    create.items[0].skuCode = 'SKU-V-001'
    create.items[0].qty = '1'
    const created = await OutboundApi.create(create)

    // 2. 取消成功，且带回取消时点（页面用来解释"回滚了什么"）
    const cancelled = await OutboundApi.cancel(created.doNo, new ReasonQo('前端联调取消'))
    expect(cancelled.status).toBe(Status.CANCELLED)
    expect(cancelled.cancelStage).toBe(Status.PENDING)

    // 3. 终态再取消 → 409 + 20002，前端 showError 直接弹后端文案
    await expect(OutboundApi.cancel(created.doNo, new ReasonQo('重复取消'))).rejects.toMatchObject({
      code: 20002,
    })
  })
})
