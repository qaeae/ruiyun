# W5 前端开工前置包

> 目标：让前端**在写第一行代码之前**就知道"怎么连、怎么错、怎么控权限、先做哪些页"。
> 本文件是前端开发的唯一入口；与设计文档冲突时以 `03-接口定义.md` 为准，本文件只做**落地口径**。

---

## 〇、开工阻塞项（**10-14 已全部清零**）

| # | 阻塞项 | 状态 |
|:--:|------|------|
| 1 | pnpm 安装 | ✅ **已装（pnpm 12.11.2）**。坑：pnpm 12 在 `pnpm-workspace.yaml` 里用 **`allowBuilds`（映射形态）** 放行依赖构建脚本——旧名 `onlyBuiltDependencies` 写了不报错但**不生效**，写成数组会报 `expected mapping start` |
| 2 | 前端工程骨架 | ✅ **已建** `wms-platform-web/`（Vue3 + TS + Vite + Element Plus + Pinia + Router）；`pnpm build`（含类型检查）、`pnpm test`（8 用例）、`pnpm check:standard` 全绿 |
| 3 | 规范自检脚本项目化改写 | ✅ **已完成**：`wms-platform-web/scripts/check-standard.mjs`（新增 3 条 F4 检查 + R7 模板中文三元，并用探针证明会拦） |
| 4 | 前端页面清单 | ✅ **已完成 16 个页面**：库存（查询/调整）、复核（待办/我的申请）、财务（对账/结算/垫付/日终勾稽/兜底扣减）、主数据（主数据 3 页签/设备档案）、运维（任务）、错误页 |
| 5 | 接口契约校验 | ✅ **已完成**：`pnpm check:api` 把 `src/models/*.ts` 的调用与后端契约清单逐条比对（本轮 **44 处调用 / 87 个契约**全部命中） |
| 6 | 后端未联调 | ⬜ **唯一剩余项**：前端已就绪，尚未与真实后端跑通（按 §六 起后端 + 建超管账号） |

> 工程自身的说明（目录、命令、三条硬约定、实测坑）见 `wms-platform-web/README.md`。

---

## 一、技术栈与规范基线

- **技术栈**：Vue 3 + TypeScript + Element Plus（后台管理台），构建/包管理走 **pnpm**。
- **强制规则 R1–R12**：以 `.agents/skills/admin-system-knowledge/PROJECT-STANDARD.md` 为唯一权威；
  评审/PR 直接引用编号（如"符合 R5、R6"）。
- **自检脚本**：`pnpm check:standard`（代码合规）、`pnpm audit:standard`（规范↔项目一致性）。
- **模板**：`templates/model-module.ts`（models 骨架）、`templates/list-page.vue`（列表页骨架）。
- **重要提醒**：规范来自**另一个项目**（`admin-system`）。凡涉及"对外契约"的部分（请求头、成功码、
  错误码、接口文档出处）一律按 §二/§三 改写；工程纪律（目录、命名、注释、遮罩、字典）**原样采纳**。

---

## 二、接入契约（与规范不同，以本节为准）

### 2.1 统一响应体

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "..." }
```

| 情形 | HTTP | body.code | 前端处理 |
|------|------|-----------|---------|
| 业务成功 | 200 | **`0`** | 取 `data` |
| 业务失败（参数/业务规则） | **200** | ≠0 | **必须判 `code`**，不能只看 HTTP 状态；提示 `message` |
| 未认证（无/非法/过期令牌） | **401** | 40300 | 跳登录页（清 token） |
| 无权限 | **403** | 40300 | 提示"无权限，请联系管理员"（**不要跳登录**） |
| 状态机冲突 / 幂等键内容不一致 | **409** | 10002/2xxxx | 提示并刷新当前数据 |
| 并发重入（同幂等键处理中） | 429 | 10003 | 短暂等待后重试（同一幂等键） |

> **规范差异**：规范的示例用 `code === 200` 表示成功；本项目成功码是 **`0`**。
> 请求封装里请写死 `code === 0`（见 §五 改写清单）。

### 2.2 请求头（按需携带）

| 头 | 何时带 | 口径 |
|----|-------|------|
| `Authorization: Bearer <token>` | 除登录/刷新/健康探针/PDA 补传外的**全部**接口 | 令牌来自 `POST /api/v1/auth/login` |
| `Idempotency-Key` | **所有写接口**（POST/PUT/PATCH/DELETE） | ≤96 字符；**同一业务动作重试必须复用同一个值**（见 §2.3） |
| `X-Trace-Id` | 可选（前端已有链路 ID 时透传） | 便于把前端日志与后端 `traceId` 关联 |
| `X-Device-Code` | 仅 PDA/扫码枪页面 | 设备档案里的 `device_code`；未登记/停用 → 403 + 10006 |

### 2.3 `Idempotency-Key` 生成与复用（**最容易做错的一条**）

| 做法 | 结果 |
|------|------|
| 每次调用新生成 UUID | ❌ 网络重试会**重复执行业务**（幂等键失去了意义） |
| **一次用户动作生成一次，重试时复用** | ✅ 正确 |
| 刷新页面/重新点击 → 新键 | ✅ 正确（那是新的业务动作） |

推荐实现：在提交按钮的 `withMask` 包裹里**先算好键**，把"提交中"期间的所有重试都传同一个键；

```ts
// 1. 一次用户动作 → 一个幂等键（重试复用）
const idemKey = crypto.randomUUID()
await withMask(() => api.saveXxx(payload, { idemKey }), '提交中...')
```

**豁免**（后端不要求该头）：`/auth/login`、`/auth/refresh`、`/auth/me`、`/pda/sync/batch`
（后者按明细逐条幂等：`deviceCode:taskId:action:seq`）。

### 2.4 时间与数值

| 项 | 口径 |
|----|------|
| 时间入参 | 兼容 `2026-10-14 10:00:00` 与 `2026-10-14T10:00:00`、小数秒 0–9 位 |
| 时间出参 | `yyyy-MM-dd HH:mm:ss.SSS`（`LocalDateTime`）；日期类为 `yyyy-MM-dd` |
| 金额/数量 | **一律字符串或 number 精确保留**（后端是 `DECIMAL`）；前端不做浮点运算，展示用格式化，提交按字符串原样回传 |
| 分页 | `pageNum`（从 1 开始）、`pageSize`；返回 `total/list` |
| 排序 | `sortBy` + `sortOrder`（`asc`/`desc`），**仅白名单字段**（非白名单会被忽略） |

---

## 三、权限与数据范围（前端控制面）

登录/刷新返回（`LoginVo`）：`token`、`expiresAt`、`userId`、`username`、`adminFlag`、
`roleCodes[]`、**`permissions[]`**、**`warehouseIds[]`**。

| 前端要做什么 | 口径 |
|-------------|------|
| 按钮/菜单显隐 | 用 `permissions` 判断（规范的 `hasPermission`）；**`adminFlag === true` 视为拥有全部权限** |
| 仓库切换器 | 只展示 `warehouseIds` 内的仓库；`adminFlag` 可看全部 |
| 401 vs 403 | 401 → 清 token 跳登录；403 → 提示无权限（**不要**跳登录，否则用户会反复重登也解决不了） |
| 权限变更生效时机 | 权限快照在令牌里，**改授权后需重新登录/刷新令牌**才生效（`/auth/refresh` 会重查） |

**权限码清单**（与 `docs/frontend/api-contract.md` 中每个接口一一对应）：

```
master:merchant:create / master:warehouse:create / master:sku:create / master:sku:unit-conv
master:status:change / master:query / master:device:manage
stock:query / stock:reserve / stock:adjust / stock:move / stock:invariant:check
finance:reconcile:manage / finance:settle:manage / finance:settle:dispatch / finance:query
auth:user:manage / auth:role:manage / auth:warehouse:grant / auth:audit:query
auth:approval:apply / auth:approval:review
ops:job:query / ops:job:run
```

> **分权要点**：`finance:settle:manage`（准备付款）与 `finance:settle:dispatch`（按下付款按钮）是
> 两个权限——界面上也应是两块不同的入口；`auth:approval:apply`（发起）与 `auth:approval:review`（复核）
> 同理。**不要为了"方便"把两者显示给同一个人。**

---

## 四、页面清单（按端拆分，含接口与权限）

接口全量清单见 `docs/frontend/api-contract.md`（87 个，自动生成）。
下面只列**W5 要做的第一批页面**与其关键接口。

### 4.1 通用（两端都要）

| 页面 | 关键接口 | 权限 |
|------|---------|------|
| 登录 | `POST /auth/login` | PublicApi |
| 布局与用户信息 | `POST /auth/me`（取最新权限快照） | PublicApi |
| 无权限/404 | — | — |

### 4.2 仓库 PC 端

| 页面 | 关键接口 | 权限 |
|------|---------|------|
| 库存查询（三态+库位） | `GET /inventory/sku`、`GET /inventory/locations`、`GET /inventory/movements` | `stock:query` |
| 库存调整（**走复核**） | `POST /inventory/internal/adjustments` → 返回 `approvalNo` | `stock:adjust` |
| 盘点单（建单/录入/提交/审批/驳回） | `POST /stocktakes`、`POST /stocktakes/{no}/items`、`/submit`、`/approve`、`/reject` | `stock:adjust`（查询 `stock:query`） |
| 设备档案 | `GET/POST /master/devices*` | `master:device:manage` / `master:query` |
| 主数据（商家/仓库/库区/库位/SKU/箱规） | `GET/POST /master/*` | `master:*` |
| **复核待办**（见 §4.4） | `GET /approvals/pending`、`/{id}/approve`、`/{id}/reject` | 复核 `auth:approval:review`；发起 `auth:approval:apply` |
| **任务运维**（见 §4.5） | `GET /jobs`、`GET /jobs/runs`、`POST /jobs/{name}/run` | 查询 `ops:job:query`；补跑 `ops:job:run` |

### 4.3 商家端

| 页面 | 关键接口 | 权限 |
|------|---------|------|
| 对账单（列表/详情/差异） | `GET /finance/reconcile-bills`、`/{rclNo}` | `finance:query` |
| 差异处理与确认 | `POST /finance/reconcile-bills/{rclNo}/items/{itemId}/handle`、`/{rclNo}/confirm` | `finance:reconcile:manage` |
| 结算批次 | `GET /finance/settle-batches`、`POST .../{stbNo}/dispatch`（**走复核**） | `finance:query` / `finance:settle:dispatch` |
| 垫付台账 | `GET /finance/advance-payments`、`GET .../limit` | `finance:query` |
| 日终勾稽四层 | `GET /finance/daily-reconciliation?bizDate=` | `finance:query` |

### 4.4 必须做的两个"新"页面（后端已就绪、前端还没有）

**① 复核待办（`/approvals`）**

| 交互 | 口径 |
|------|------|
| 列表 | `GET /approvals/pending?bizType=`（按发起时间正序：先提交的先处理） |
| 详情 | `GET /approvals/{id}` —— **必须展示 `payloadJson`（审批内容快照）**，复核人据此判断要批的是哪一份内容 |
| 通过 | `POST /approvals/{id}/approve` —— **不要传复核人 ID**，后端取自令牌 |
| 驳回 | `POST /approvals/{id}/reject`，`reason` 必填 |
| 我的申请 | `GET /approvals/mine` + `POST /approvals/{id}/withdraw`（仅发起人） |
| 关键提示 | 自己发起的单**不能自审**（后端 80002 会拒），界面上应禁用"通过/驳回"按钮并给出原因 |

**② 任务运维（`/jobs`）**

| 交互 | 口径 |
|------|------|
| 任务一览 | `GET /jobs` —— 展示 `lastStatus`/`lastStartAt`/`lastMessage`/`successCount`/`failCount`/`leaseOwner` |
| 执行历史 | `GET /jobs/runs?jobName=&limit=` —— `triggerType` 区分定时/补跑，`status` 三态（成功/失败/**跳过**） |
| 补跑 | `POST /jobs/{jobName}/run`（权限 `ops:job:run`）—— 结果可能是「**跳过**」（未抢到租约/已停用），**不是错误** |
| 启停 | `POST /jobs/{jobName}/enabled?enabled=` |
| 关键提示 | 界面文案要区分"跳过"与"失败"（多实例下每周期一半实例会跳过）；`lastStatus` 停留在「执行中」= 任务卡死或被杀 |

---

## 五、规范的项目化改写清单（开工前完成）

| # | 规范原文 | 本项目改写 |
|:--:|---------|-----------|
| 1 | R4：每次请求传 `apiCode` | **删除该字段**；写接口传 `Idempotency-Key`（§2.3）；链路标识用 `X-Trace-Id` |
| 2 | 接口文档出处 `接口文档.md` | 改为 `项目一方案-详细设计/03-接口定义.md` + `docs/frontend/api-contract.md`（自动生成） |
| 3 | 成功码 `code === 200` | **`code === 0`** |
| 4 | `scripts/audit-standard.mjs` 审计 `apiCode` | 改为审计 `Idempotency-Key`：所有写接口调用点必须显式传 `idemKey`；401/403 分支必须存在 |
| 5 | 路由权限基于本地角色常量 | 改为基于令牌里的 `permissions[]`（`adminFlag` 视为全权限） |
| 6 | 模板里的示例字段/文案 | 换成 WMS 领域（仓库/商家/SKU/库位/运单/对账单），避免直接把系统管理示例留在页面上 |
| 7 | 登录页/菜单示例 | 按 §4 的页面清单重建菜单（仓库 PC / 商家端两套） |

> 改写产物应当**回写进规范的那份文档或本目录的补丁文件**，而不是只在口头约定——
> 否则下一个人读规范又会写出 `code === 200`。

---

## 六、联调环境

### 6.1 起后端

```bash
# 1) 一次性 MySQL（不碰本机 3306；数据落在临时目录）
MY="C:/Program Files/MySQL/MySQL Server 8.4/bin"
"$MY/mysqld" --datadir="C:/Users/AD/AppData/Local/Temp/wms-poc4/data" --port=3399 \
  --bind-address=127.0.0.1 --log-error="C:/Users/AD/AppData/Local/Temp/wms-poc4/error.log" &

# 2) 起后端（Flyway 会自动建表）
cd wms-platform
mvn -pl wms-core -am spring-boot:run \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:mysql://127.0.0.1:3399/wms_poc4 \
  --spring.datasource.username=root --spring.datasource.password= \
  --wms.payment.channel.mode=MOCK"
```

### 6.2 联调要点

| 项 | 口径 |
|----|------|
| 健康检查 | `GET /api/v1/health`（PublicApi，可用于联调前探活） |
| 登录账号 | 需先在库里建用户（`POST /auth/users` 需权限）→ 一期可由后端初始化一个 `adminFlag=1` 的超管用于联调 |
| 支付通道 | 默认 `MOCK`：只记账不动钱，**指令号带 `MOCK-` 前缀**（界面上应能看出是演练数据） |
| 定时任务 | 默认关闭（`wms.schedule.enabled=false`）；联调任务运维页时用 `POST /jobs/{name}/run` 手动触发 |
| 写接口幂等 | 联调重复提交时**观察是否被幂等拦住**（同键重复提交应返回首次结果，而非重复执行） |

---

## 七、验收清单（DoR / DoD）

**DoR（开工前必须完成）**

- [ ] `pnpm` 已安装（`pnpm -v` 有输出）
- [ ] 前端工程骨架按规范创建，`pnpm check:standard` 可运行
- [ ] §五 的 7 项改写已落到规范文档/补丁文件
- [ ] 已按 `docs/frontend/api-contract.md` 生成 `src/models/` 的 Qo/Vo 骨架（可先只做登录+库存两页）
- [ ] 请求封装已实现：`code===0` 判定、`Authorization`、`Idempotency-Key`（含重试复用）、401/403 分支

**DoD（第一批页面完成的判定）**

- [ ] 登录 → 拿 `permissions` → 菜单/按钮按权限显隐（`adminFlag` 全权限）
- [ ] 库存查询页：三态（实物/可用/预留）展示正确，库位明细与 SKU 合计一致
- [ ] 库存调整页：提交后**返回 `approvalNo` 而非直接改库存**，并能在复核待办里看到、通过后库存变化
- [ ] 复核待办页：详情展示内容快照；自审按钮禁用；驳回必填原因
- [ ] 任务运维页：区分"成功/失败/跳过"；补跑后能看到新的执行记录
- [ ] 所有写操作都有遮罩文案（R6）与 `Idempotency-Key`
- [ ] `pnpm check:standard` 通过（0 违规）
