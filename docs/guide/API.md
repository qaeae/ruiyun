# 接口说明

> 面向：前后端联调、加接口、排错误码。
> 契约权威顺序：`项目详细设计/03-接口定义.md` > 自动生成的 [api-contract.md](../frontend/api-contract.md) > 本文。

---

## 1. 总览

- 接口总数：**107 个**（`docs/frontend/api-contract.md` 自动生成，方法/路径/权限码三段式）
- 统一前缀：`/api/v1/**`，经网关 `:8088` 进入；直连单体时用 `wms-core:8080`
- 全部返回**统一响应体**，成功判定是 `code === 0`（**不是 HTTP 200**）

| 域 | 路径前缀 | 接口数 | 所在服务 |
|----|---------|-------|---------|
| 主数据 | `/api/v1/master/**` | 22 | wms-masterdata |
| 财务 | `/api/v1/finance/**`、`/pricing/**`、`/reconcile-bills/**` | 18 + 1 | wms-fns |
| 出库 | `/api/v1/outbound/**` | 15 | wms-core |
| 认证 | `/api/v1/auth/**` | 14 | wms-auth |
| 库存 | `/api/v1/inventory/**` | 12 | wms-core |
| 盘点 | `/api/v1/stocktakes/**` | 7 | wms-core |
| 审批 | `/api/v1/approvals/**` | 7 | wms-auth |
| 任务运维 | `/api/v1/jobs/**` | 4 | wms-core |
| 物流 | `/api/v1/logistics/**` | 3 | wms-core |
| PDA | `/api/v1/pda/**` | 2 | wms-core |
| 健康检查 | `/api/v1/health/**` | 2 | 各服务 |

## 2. 统一契约

### 2.1 响应体

```jsonc
// 成功（HTTP 200）
{ "code": 0, "message": "ok", "data": { /* 业务数据 */ }, "traceId": "7df19a25…", "success": true }

// 业务失败（HTTP 4xx，部分校验失败仍是 200 + code≠0）
{ "code": 20002, "message": "出库单已装车出库（库存已出账），不可取消；如需退回请走退货入库流程",
  "data": null, "traceId": "…", "success": false }

// 分页（data 结构固定）
{ "code": 0, "message": "ok",
  "data": { "total": 22, "pageNum": 1, "pageSize": 20, "list": [ /* 行 */ ] } }
```

| 字段 | 说明 |
|------|------|
| `code` | 0 = 成功；其他见 §5 错误码 |
| `message` | **可直接展示给用户的中文**（前端不要再编一套文案） |
| `traceId` | 链路标识，排查时给后端；与 SkyWalking traceId 对齐 |
| `success` | 冗余布尔，等价于 `code === 0`（前端请判 `code`） |

### 2.2 HTTP 状态码语义（客户端要分开处理）

| 状态 | 含义 | 前端处理 |
|------|------|---------|
| 200 | 成功，或"业务失败但 HTTP 层无更合适码"（看 `code`） | 判 `code===0` |
| 400 | 参数校验失败（`10001`，如漏 `Idempotency-Key`、必填为空） | 提示 `message` |
| 401 | **未认证**（无令牌/令牌过期，业务码 `40300`） | 清 token → 跳登录 |
| 403 | **无权限**（已登录但缺权限码，`40300`） | 只提示，**不要跳登录**（重登也解决不了） |
| 404 | 路径不存在 / 资源不存在（`10001`） | 提示 |
| 405 | 方法不允许（如 `GET` 打 `POST` 接口，`10001`） | 排查前端动词写错 |
| 409 | 状态机冲突（`20002`）/ 幂等键异体（`10002`）/ 唯一键冲突（`10007`） | 提示 + 建议刷新 |
| 429 | 并发重入（`10003`，同幂等键处理中） | 稍后重试（同键重试是安全的） |
| 500 | 系统异常（`19999`） | 提示 + 上报 traceId |

### 2.3 请求头

| 头 | 必填 | 说明 |
|----|------|------|
| `Authorization: Bearer <token>` | 除登录/健康检查外必填 | 网关验签后向内部透传 `X-User-Id`/`X-Tenant-Id`/`X-User-Permissions` 等 |
| `Idempotency-Key` | **所有写操作必填** | 见 §3 |
| `X-Trace-Id` | 建议 | 不传则后端生成；前端传了能串起前后端日志 |
| `X-Device-Code` | PDA 场景必填 | 设备未登记报 `10006` |
| `X-Sign` / `X-Timestamp` / `X-Nonce` | 开放接口场景 | 签名校验，`10004`/`10005` |

> 免幂等键的路径（会话建立前 / 逐条幂等的设备补传）：
> `/api/v1/auth/login`、`/refresh`、`/me`、`/api/v1/pda/sync/batch`

### 2.4 字段口径（前后端最容易对不上的地方）

| 类型 | JSON 形态 | 约定 |
|------|----------|------|
| 雪花 ID（`Long`） | `"105004220293316608"`（**字符串**） | 19 位整数超 JS 安全范围，后端统一序列化为字符串，前端**禁止转 Number** |
| 金额/数量/重量（`BigDecimal`） | `12.5000`（数字） | 展示层格式化，不在前端做算术 |
| 时间（`LocalDateTime`） | `"2026-10-18 02:53:17.005"` | 后端已格式化，前端直接展示（列表可截到秒） |
| 布尔 | `true` / `false` | — |
| 枚举 | 中文（如 `"待接单"`） | **中文即协议值**，筛选/下拉必须用同一套字面量 |
| 分页 | `pageNum`（从 1 开始）、`pageSize` | 返回 `total`/`pageNum`/`pageSize`/`list` |

## 3. 幂等（写接口的强制要求）

```
同一 Idempotency-Key 的第二次请求：
  ① 载荷相同  → 返回第一次的结果（replay，不重复产生副作用）
  ② 载荷不同  → 409 + 10002（mismatch，说明键被复用错了）
  ③ 首次仍在处理中 → 429 + 10003（in-progress，可稍后重试）
```

三条实践要求：

1. **键要按"动作 + 目标"生成**，不能只按"动作"。反例：收货只按 `SKU+商家` 生成键，向第二个库位补货时命中同键 → 被静默跳过（返回 200 但库存没加）。
2. **同一次操作的重试要复用同一个键**（网络超时后重试，不要换键）。
3. **换了业务载荷（改了数量/库位）就要换键**，否则会撞 10002。

## 4. 鉴权与权限

1. `POST /api/v1/auth/login` 拿 `token` + `permissions[]` + `warehouseIds[]`；
2. 后续请求带 `Authorization: Bearer <token>`；
3. 网关验签后，各服务用 `@RequiresPermission("域:对象:动作")` 判定；
4. 权限码共 **38 个**：`outbound 11` · `master 7` · `auth 6` · `stock 5` · `finance 4` · `logistics 2` · `ops 2` · `pricing 1`；
5. **超管（`adminFlag=true`）跳过权限判定**；非超管还受 `warehouseIds` 数据范围限制；
6. 权限快照在令牌里 → **改授权后需重新登录/刷新**才生效。

## 5. 错误码分段

| 段 | 域 | 代表错误 |
|----|----|---------|
| `10001–10007` | 平台通用 | 参数错误、幂等异体/重入、签名、设备未登记、重复键 |
| `19999` | 系统 | 未预期异常 |
| `20001–20005` | 出库 | 余额不足禁下单、非法状态流转、称重偏差超限、面单与单不符、未交接不可装车 |
| `30001–30004` | 物流 | 取号失败/无单号/面单不可作废/承运商超时 |
| `40001–40007` | 资金/财务 | 余额不足、冻结状态不可扣、需垫付、通道失败、已冲正、价目缺失/不匹配 |
| `40300` | 认证 | 无权限（401/403 共用） |
| `50001–50004` | 库存 | 可用不足、库存被占用、库位不可拣、不变量被破坏 |
| `60001–60003` | 主数据 | 类目资料不全、禁入品类、预检未通过 |
| `70001–70007` | 对账/结算 | 状态不可确认、差异超档需人工、已下发、账单未确认、垫付超限 |
| `80001–80003` | 审批 | 审批不存在、自审禁止、重复申请 |

**排错建议**：先看 `message`（后端给的都是可读中文），再看 `traceId` 去 SkyWalking 查这条链路。

## 6. 出库域（15 个接口，最完整的样板）

### 6.1 状态机

```
待接单 ──dispatch──▶ 拣货中 ──pick-complete──▶ 已复核 ──measure──▶ 已复核(称重后) ──pack──▶ 已打包 ──handover──▶ 待装车 ──load──▶ 已出库
  │                    │                                                                                              │
  │                    ├─shortage──▶ 缺货待处理 ──resume-picking──▶ 拣货中                                          │
  │                    └─cancel/hold──▶ 已取消 / 已拦截（终态）                                                       │
  └─cancel/hold──▶ 已取消 / 已拦截                          cancel 在除 已出库 外的任何状态可用
```

| 当前状态 | 可用动作 | 不可用的报错 |
|---------|---------|-------------|
| 待接单 | dispatch、cancel、hold | 其余 `20002` |
| 拣货中 | pick-complete、shortage、cancel、hold | 同上 |
| 缺货待处理 | resume-picking、cancel、hold | 同上 |
| 已复核 | measure、pack、partial-ship、cancel、hold | 同上 |
| 已打包 | handover、partial-ship、cancel、hold | 同上 |
| 待装车 | load、partial-ship、cancel、hold | 同上 |
| 已出库 | — | `20002`（货已出账，走退货入库） |
| 已取消 / 已拦截 | — | `20002` |

### 6.2 接口清单

| # | 方法 | 路径 | 权限 | 前置状态 | 关键副作用 |
|---|------|------|------|---------|-----------|
| 01 | POST | `/api/v1/outbound/orders` | `outbound:order:create` | — | 预占库存 + 冻结运费（不足 `20001`/`50001` 整单回滚） |
| 02 | POST | `/orders/{doNo}/dispatch` | `outbound:order:dispatch` | 待接单 | 库位分配（`allocated`）；需逐行给 `locationCode` |
| 03 | POST | `/orders/{doNo}/pick-complete` | `outbound:order:pick` | 拣货中 | 实拣必须等于应发，否则 `20002` 提示改走缺货 |
| 04 | POST | `/orders/{doNo}/shortage` | `outbound:order:pick` | 拣货中 | 差额释放预占 → 缺货待处理 |
| 05 | POST | `/orders/{doNo}/resume-picking` | `outbound:order:pick` | 缺货待处理 | 重新预占（可能因库存不足失败） |
| 06 | POST | `/orders/{doNo}/measure` | `outbound:order:measure` | 已复核 | 写实称重量/体积；偏差 >20% 报 `20003` |
| 07 | POST | `/orders/{doNo}/pack` | `outbound:order:pack` | 已复核 | 绑运单号/取面单（同号用于他单报 `20004`） |
| 08 | POST | `/orders/{doNo}/handover` | `outbound:order:handover` | 已打包 | `handoverConfirmed=true`（未交接装车报 `20005`） |
| 09 | POST | `/orders/{doNo}/load` | `outbound:order:load` | 待装车 | **出账**（实物/可用扣减）→ 已出库，不可逆 |
| 10 | POST | `/orders/{doNo}/cancel` | `outbound:order:cancel` | 除已出库/已取消/已拦截 | 按取消矩阵回滚；`cancelStage` 记录取消时点 |
| 11 | POST | `/orders/{doNo}/hold` | `outbound:order:hold` | 非终态 | 拦截：回滚预占、释放冻结、作废面单 |
| 12 | POST | `/orders/{doNo}/partial-ship` | `outbound:order:partial` | 已复核/已打包/待装车 | 本次量出账，余量生成新单（返回 `newDoNo`） |
| 13 | GET | `/api/v1/outbound/orders` | `outbound:order:query` | — | 分页：`pageNum`/`pageSize`/`status`/`warehouseId`/`merchantId`/`doNo` |
| — | GET | `/orders/{doNo}` | `outbound:order:query` | — | 详情（收件人**只有掩码**） |
| — | GET | `/orders/{doNo}/status-logs` | `outbound:order:query` | — | 轨迹（含 operator/device/traceId/idemKey） |

> 编号对应设计文档 API-OUT-01…13；详情与轨迹属于 API-OUT-13 的读侧。

### 6.3 请求体字段（照抄，别猜）

```jsonc
// 01 建单
{ "warehouseId": 1, "merchantId": 1, "sourceType": "API", "sourceOrderNo": "SO-1",
  "carrierCode": "SF", "carrierName": "顺丰", "estimatedFreight": 12.5,
  "expectedWeight": 2, "expectedVolume": 0.1,
  "receiverName": "张三", "receiverPhone": "13800000000",
  "receiverAddress": "上海市浦东新区张江路 1 号", "receiverProvince": "上海市",
  "receiverCity": "上海市", "receiverDistrict": "浦东新区", "remark": "备注",
  "items": [{ "skuCode": "SKU-V-001", "qty": 2, "unit": "件" }] }

// 02 派发（lineNo 来自详情 items[].lineNo）
{ "items": [{ "lineNo": 1, "locationCode": "V-01-01-01" }] }

// 03 拣货完成
{ "items": [{ "lineNo": 1, "pickedQty": 2 }] }

// 04 缺货
{ "items": [{ "lineNo": 1, "shortageQty": 1 }], "reason": "货架缺货" }

// 06 称重
{ "actualWeight": 2.0, "actualVolume": 0.1, "packageCount": 1 }

// 07 打包
{ "waybillNo": "WB-123", "packageCount": 1 }

// 08 交接 / 09 装车
{ "remark": "交接给装卸组" }

// 10 取消 / 11 拦截（reason 必填）
{ "reason": "商家撤单" }

// 12 部分发货
{ "items": [{ "lineNo": 1, "shipQty": 1 }], "reason": "客户只要一半" }
```

### 6.4 可执行示例（跑通全链路）

```bash
B=http://127.0.0.1:28088

# 1) 登录
TOKEN=$(curl -s -X POST $B/api/v1/auth/login \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"username":"it_admin","password":"ItPassw0rd!"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 2) 建单（注意 Idempotency-Key 唯一）
curl -s -X POST $B/api/v1/outbound/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: create-demo-1' \
  -d '{"warehouseId":1,"merchantId":1,"sourceType":"API","sourceOrderNo":"SO-DEMO-1",
       "carrierCode":"SF","estimatedFreight":10,"receiverName":"张三",
       "receiverPhone":"13800000000","receiverAddress":"上海市浦东新区张江路 1 号",
       "items":[{"skuCode":"SKU-V-001","qty":2}]}'

# 3) 依次作业（把上一步返回的 doNo 填进去）
DO=DO20261018XXXX
curl -s -X POST $B/api/v1/outbound/orders/$DO/dispatch \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dispatch-$DO" \
  -d '{"items":[{"lineNo":1,"locationCode":"V-01-01-01"}]}'
```

更完整的可重复验证脚本：

| 脚本 | 覆盖 |
|------|------|
| `scripts/contract-smoke.mjs` | 25 项契约断言（登录/幂等/权限/掩码/状态流转/取消矩阵），走网关真登录 |
| `web/src/models/__tests__/outbound.e2e.spec.ts` | 12 项，走**前端真实模型层**（`WMS_E2E_BASE` 开启） |
| `scripts/seed-demo-outbound.mjs` | 造 22 张单铺满 9 个状态（也顺带验证全部写接口） |

## 7. 加新接口的检查清单

1. Controller 方法必须 `@RequiresPermission(...)`（`PermissionAnnotationTest` 会拦）；
2. 请求体用 `Ro`（含 `@NotNull`/`@NotBlank` 等校验），响应体用 `Vo`，**不暴露 Po**；
3. 写接口必须带 `Idempotency-Key` 校验路径（走 `IdempotencyService`，别自己判重）；
4. 状态变更只能通过状态机的条件更新（`WHERE status = from`），并在同一事务写轨迹；
5. 新增错误码加到 `ErrorCode` 对应域段；
6. 加完跑：
   ```bash
   perl scripts/export-api-contract.pl > docs/frontend/api-contract.md   # 更新契约清单
   mvn -pl wms-archtest -am test                                        # 分层/权限守护
   cd web && pnpm check:api                                             # 前端调用与契约一致性
   ```
