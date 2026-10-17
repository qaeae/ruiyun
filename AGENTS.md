# AGENTS.md — 交互约定与技能加载入口

> 本文件只保留**交互约定、技能加载入口、规范优先级**。
> 项目知识（架构、业务、表结构、流程）在设计文档与技能库中，不在此重复。

## 1. 项目一句话

智能化仓库平台（空间租赁 + 代发货 + 物流代结算）。**现状结构**（2026-10-17）：

```
wms-kernel      共享内核（ApiResponse/BizException/错误码/常量/令牌）
wms-common      基础设施（幂等/雪花ID/PII/追踪/缓存/设备端口/远程调用）
wms-discovery   Nacos 服务发现
wms-mod-*       领域模块：au / md / acct / iv / fns / ops / pda / logistics / outbound
wms-auth | wms-masterdata | wms-fns | wms-gateway | wms-core   5 个可部署服务
wms-archtest    ArchUnit 边界与规范守护（CI 阻断）
```

域实现进度：`md`（主数据）、`au`（RBAC + 双人复核）、`iv`（三态库存/库位/流水/盘点）、
`fns`（对账/结算/兜底/垫付/日终勾稽）、`ops`（调度）、`pda`（离线补传）、`logistics`（装运单起步：建单/绑面单/作废/回单影像）、
`acct`（**最小面**：费用冻结/释放）、`outbound`（**W6 全流程**：API-OUT-01…13）。
未实现：`cs`（合同/账单）、`inbound`、`gate`、`rpt`、开放 API —— 见 §7。

## 2. 技能加载入口（改代码前必读）

| 技能 | 路径 | 何时必读 |
|------|------|---------|
| 后端知识库 | [.agents/skills/admin-backend-knowledge/SKILL.md](.agents/skills/admin-backend-knowledge/SKILL.md) | 改任何后端代码（Po/Dto/Ro/Vo、Mapper、Service、Controller、MapStruct、配置、缓存、定时任务）；执行 `mvn` 构建 |
| 前端知识库 | [.agents/skills/admin-system-knowledge/SKILL.md](.agents/skills/admin-system-knowledge/SKILL.md) | 改前端代码（页面、组件、模型、API、store、路由）时；**前端工程不在本仓库**，见 §5 |

两个技能库是**外部引入的规范**（源自 `singleProject` / `singleProjectForce`），已原样入库，**不得就地修改**；
与本项目的差异一律通过 [docs/standards/STANDARDS-MAPPING.md](docs/standards/STANDARDS-MAPPING.md) 的映射表表达。

## 3. 规范优先级（冲突时按此顺序，不得越级）

```
① 设计文档（项目一方案.md V1.2 / 01-N、02-N …）  ← 对外契约与数据模型的唯一权威
② docs/standards/STANDARDS-MAPPING.md            ← 技能规范到本项目的适配结论
③ .agents/skills/**                              ← 技能规范原文
④ 现有代码                                       ← 现状，偏差处按 ①②③ 实现，勿照抄
```

- **对外契约以设计文档为准**：`ApiResponse`（`code=0` + `traceId`）、`BizException` + 5 位域错误码、
  `deleted 0/1` + `tenant_id`、按域分包 —— 这些与技能规范原文不一致处，按映射表执行。
- **工程纪律以技能规范为准**：分层与 Wrapper 收口、Po/Dto/Ro/Vo 后缀与静态工厂、MapStruct、
  编号步骤注释、常量集中、构造注入、提交规范 —— 全量采纳。
- 若发现映射表未覆盖的新冲突：先在设计文档与映射表里补结论，再写代码；**不要沉默地选一边**。

## 4. 后端固定命令

```bash
mvn clean verify -Dit.skip=true          # 编译 + 单元测试 + 架构守护（无需 Docker/数据库）
mvn -pl wms-archtest -am test            # 只跑规范/边界守护
mvn -pl wms-core -am verify -Dit.skip=false   # 集成测试（需 Docker，或外部 MySQL，见下）
```

**集成测试的两条硬纪律**（本项目已多次踩到）：

1. **IT 与运行库必须分库**（`53.25`）：用 `-Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3306/wms_it_out` 指向
   独立 schema，**不要**指向 `wms_platform`——否则 IT 的清表夹具会把正在跑的服务数据删掉；
2. **改完代码要重启所有用该产物的进程**（`54.15`）：运行期覆盖 jar 会导致 `NoClassDefFoundError`
   这类假故障，曾多次误导归因。

```bash
# 外部 MySQL（无 Docker 时）示例；IT 会按 poc.mysql.* 覆盖数据源
mvn -pl wms-core -am verify -Dit.skip=false \
    -Dpoc.mysql.url="jdbc:mysql://127.0.0.1:3306/wms_it_out?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
    -Dpoc.mysql.user=root -Dpoc.mysql.password=***
```

## 5. 前端（**工程不在本仓库**）

- 前端工程（`Vue 3 + TS + Vite + Element Plus`）位于工作区 `../wms-platform-web`，**本仓库只保留契约与前置包**；
- 动前端代码前**必须先读** `.agents/skills/admin-system-knowledge/` 全套（R1–R12、models-standard、page-standard），
  并按下表完成项目化改写后才允许写页面代码：

| 需改写项 | 规范原文（外部项目） | 本项目取值 |
|---------|-------------------|-----------|
| 响应成功判定 | `code === 200` | `code === 0`（03 §1.2） |
| 请求头 | `Api-Code` | 本项目用 `Idempotency-Key`（写操作必传）+ `X-Trace-Id` |
| 接口文档 | 根目录 `接口文档.md` | `项目详细设计/03-接口定义.md` + 本仓库 `docs/frontend/api-contract.md`（自动生成） |
| 自检脚本 | `pnpm check:standard` / `audit:standard` | 脚本已入库待接；pnpm 已装（12.11.2），待接入 |

- 前端规范自检脚本已随技能入库：`.agents/skills/admin-system-knowledge/scripts/{check-standard,audit-standard}.mjs`。
- **前端当前进度（2026-10-17 实测）**：15 个页面 / 13 条业务路由（login、inventory×2、approvals×2、finance×5、
  master×2、ops、error×2）。**出库域（API-OUT-01…13）没有任何页面/模型/API 封装** ——
  后端出库接口目前只能做**契约级联调**：`node scripts/contract-smoke.mjs`（配合
  `scripts/seed-smoke-users.sql` 种账号，走网关真实登录，25 项断言，见 README §59）。
  真 UI 联调要等出库页面落地，届时按 README §59.2 的 6 条约定与 §59.5 的 5 项未覆盖面复验。

### 5.1 前端开工前置包（**唯一入口**）

→ **[docs/frontend/W5-FRONTEND-KICKOFF.md](docs/frontend/W5-FRONTEND-KICKOFF.md)**

包含：开工阻塞项（pnpm 未装）、接入契约（响应体/请求头/幂等键复用规则/时间数值口径）、
权限与数据范围口径、页面清单（仓库 PC / 商家端 / 复核待办 / 任务运维）、
规范的项目化改写清单（7 项）、联调环境与账号、DoR/DoD 验收清单。

### 5.2 接口契约清单（**自动生成，勿手改**）

→ **[docs/frontend/api-contract.md](docs/frontend/api-contract.md)**（107 个接口：方法 / 路径 / 权限码）

```bash
# 后端加完接口后重跑；CI 会校验它与代码一致（不一致直接失败）
perl scripts/export-api-contract.pl > docs/frontend/api-contract.md
```

**为什么生成而不是手写**：接口清单一旦与代码漂移，前端拿到过期契约的代价是"联调时逐条试错"，
而后端加接口时几乎不可能记得同步一份手写文档。

> 2026-10-16 修复：脚本原先只扫 `wms-core/src/main/java`，P0 模块化拆分后该目录已无 Controller，
> 生成结果为 0 条 → CI 的契约门**每次推送都会失败**。现改为扫描全部 `wms-*/src/main/java`、
> 只收录 `/api/**`、按路径排序（产物确定性），并在 `@XxxMapping` 之后声明权限的写法也能识别。

## 6. 提交规范（技能 conventions.md §3 采纳）

- 提交信息用简洁中文，**聚焦单一目的**；不混合「重构」与「新功能」；
- 涉及规范条目时在提交信息里引用编号（如 `符合 B5、B9`）。

## 7. 剩余 backlog（截至 2026-10-17，诚实登记）

| 缺口 | 影响 | 下一步 |
|------|------|--------|
| `cs` 合同/账单（M4-01/04/05） | 出库取消的"操作费按实际作业量计"只写流水、不生成费用单 | M4 轮次 |
| `acct` 扣减/充值/退款/通道（M3 其余） | 冻结后无法结算扣减；出库详情不展示冻结单 | M3 轮次 |
| `logistics` 取号/轨迹/作废重派（M8） | 打包时首个运单号由现场绑定（`bindWaybill`），非承运商取号 | M8 轮次 |
| `inbound` / `gate` / `rpt` / 开放 API | 装车前无法校验"车辆已入园"；无报表 | M5/M10/M11/M12 |
| 出库异常单表 | `01` §2.1 第 12 行"开异常单"暂无承载表，现以状态流水 + 备注代替 | 待确认后加迁移 |

