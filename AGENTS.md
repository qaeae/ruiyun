# AGENTS.md — 交互约定与技能加载入口

> 本文件只保留**交互约定、技能加载入口、规范优先级**。
> 项目知识（架构、业务、表结构、流程）在设计文档与技能库中，不在此重复。

## 1. 项目一句话

智能化仓库平台（空间租赁 + 代发货 + 物流代结算），模块化单体：`wms-common` / `wms-core` / `wms-archtest`。

## 2. 技能加载入口（改代码前必读）

| 技能 | 路径 | 何时必读 |
|------|------|---------|
| 后端知识库 | [.agents/skills/admin-backend-knowledge/SKILL.md](.agents/skills/admin-backend-knowledge/SKILL.md) | 改任何后端代码（Po/Dto/Ro/Vo、Mapper、Service、Controller、MapStruct、配置、缓存、定时任务）；执行 `mvn` 构建 |
| 前端知识库 | [.agents/skills/admin-system-knowledge/SKILL.md](.agents/skills/admin-system-knowledge/SKILL.md) | 改 `src/`（页面、组件、模型、API、store、路由）时；**前端工程尚未建立，见 §5** |

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
mvn clean verify -Dit.skip=true          # 编译 + 单元测试 + 架构守护（无需 Docker）
mvn -pl wms-archtest -am test            # 只跑规范/边界守护
mvn -pl wms-core -am verify -Dit.skip=false   # 集成测试（需 Docker）
```

## 5. 前端：尚未建工程（**W5 开工强制前置**）

- 前端工程（`Vue 3 + TS + Vite + Element Plus`）计划在 **W5（2026-11-16）** 开工；
- **开工前必须先读** `.agents/skills/admin-system-knowledge/` 全套（PROJECT-STANDARD 的 R1–R12、
  models-standard、page-standard），并按下表完成两处项目化改写后才允许写页面代码：

| 需改写项 | 规范原文（外部项目） | 本项目取值 |
|---------|-------------------|-----------|
| 响应成功判定 | `code === 200` | `code === 0`（03 §1.2） |
| 请求头 | `Api-Code` | 本项目用 `Idempotency-Key`（写操作必传）+ `X-Trace-Id` |
| 接口文档 | 根目录 `接口文档.md` | `项目一方案-详细设计/03-接口定义.md` |
| 自检脚本 | `pnpm check:standard` / `audit:standard` | 脚本已入库待接；本机**未安装 pnpm**，需先安装 |

- 前端规范自检脚本已随技能入库：`.agents/skills/admin-system-knowledge/scripts/{check-standard,audit-standard}.mjs`。

### 5.1 前端开工前置包（**唯一入口**）

→ **[docs/frontend/W5-FRONTEND-KICKOFF.md](docs/frontend/W5-FRONTEND-KICKOFF.md)**

包含：开工阻塞项（pnpm 未装）、接入契约（响应体/请求头/幂等键复用规则/时间数值口径）、
权限与数据范围口径、页面清单（仓库 PC / 商家端 / 复核待办 / 任务运维）、
规范的项目化改写清单（7 项）、联调环境与账号、DoR/DoD 验收清单。

### 5.2 接口契约清单（**自动生成，勿手改**）

→ **[docs/frontend/api-contract.md](docs/frontend/api-contract.md)**（87 个接口：方法 / 路径 / 权限码）

```bash
# 后端加完接口后重跑；CI 会校验它与代码一致（不一致直接失败）
perl scripts/export-api-contract.pl > docs/frontend/api-contract.md
```

**为什么生成而不是手写**：接口清单一旦与代码漂移，前端拿到过期契约的代价是"联调时逐条试错"，
而后端加接口时几乎不可能记得同步一份手写文档。
## 6. 提交规范（技能 conventions.md §3 采纳）

- 提交信息用简洁中文，**聚焦单一目的**；不混合「重构」与「新功能」；
- 涉及规范条目时在提交信息里引用编号（如 `符合 B5、B9`）。
