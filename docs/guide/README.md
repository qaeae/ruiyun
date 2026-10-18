# 新人从这里开始（docs/guide 导航）

> 这份文档面向**第一次接触本项目的人**：先花 5 分钟弄清"这是什么、代码长什么样、接口怎么调"，
> 再动手改代码。要查"某个功能为什么这么设计"的历史，去根目录 [README](../../README.md)（按时间记录，§1–§62）。

## 1. 五步上手（约 30 分钟）

| 步骤 | 读什么 | 产出 |
|------|--------|------|
| ① 业务 | [PRODUCT.md](PRODUCT.md) | 知道三类业务、角色、一条单据怎么流转 |
| ② 结构 | [ARCHITECTURE.md](ARCHITECTURE.md) | 知道模块/分层/谁能调谁、表怎么分 |
| ③ 接口 | [API.md](API.md) | 会用统一契约调一个接口，看得懂错误码 |
| ④ 跑起来 | [ARCHITECTURE.md §7 本地运行](ARCHITECTURE.md#7-本地运行) | 本机起 5 个服务 + 前端，能登录 |
| ⑤ 改代码 | [AGENTS.md](../../AGENTS.md) + 两个技能库 | 知道改前必读哪些规范、提交怎么切 |

跑通后再看根 [README](../../README.md) 会轻松很多——那里是"为什么这么改"的决策记录（踩坑、证伪、取舍）。

## 2. 文档地图

| 文档 | 内容 | 什么时候看 |
|------|------|-----------|
| [PRODUCT.md](PRODUCT.md) | 产品说明书：定位、三类业务、角色权限、业务闭环、领域实现状态、核心业务规则、术语表 | 任何改动之前；与产品/业务方对齐口径时 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 项目结构：18 个 Maven 模块、四层分包、跨域调用规则、5 个服务与网关路由、数据模型、配置项、本地运行、代码地图 | 定位代码、加模块/加表/加配置 |
| [API.md](API.md) | 接口说明：统一响应体与成功码、幂等键、鉴权与 401/403、错误码分段、107 个接口的分域清单、**出库域逐接口详解与状态机** | 前后端联调、加接口、排错误码 |
| [../frontend/api-contract.md](../frontend/api-contract.md) | **自动生成**的接口清单（方法/路径/权限码） | 查"某条路径是否存在"，不要手改 |
| [../frontend/W5-FRONTEND-KICKOFF.md](../frontend/W5-FRONTEND-KICKOFF.md) | 前端接入契约、页面清单、DoR/DoD | 写前端页面 |
| [../standards/STANDARDS-MAPPING.md](../standards/STANDARDS-MAPPING.md) | 外部技能规范 → 本项目的适配结论（B1–B24） | 规范原文与本项目不一致时，以这里为准 |
| [../../AGENTS.md](../../AGENTS.md) | 交互约定、技能加载入口、规范优先级、固定命令、剩余 backlog | 开工第一天必读 |
| [../../README.md](../../README.md) | 按时间记录的交付日志（§1–§62）：每个决策的原因、证伪、实测数据 | 想知道"为什么是这样" |

## 3. 按角色的最短路径

| 你是 | 先读 | 重点关注 |
|------|------|---------|
| 后端开发 | PRODUCT → ARCHITECTURE → API | 四层分包与跨域规则、`app` 服务接口、错误码、幂等、状态机 |
| 前端开发 | PRODUCT → API → W5-FRONTEND-KICKOFF | 响应成功判定 `code===0`、`Idempotency-Key`、分页结构、雪花 ID 是字符串 |
| 测试 | PRODUCT → API（出库域详解）→ README §62 | 9 个状态怎么造、哪些动作不可逆、错误码对照 |
| 运维/实施 | ARCHITECTURE §6–§7 → README §56–§58 | 5 个服务端口、必需环境变量、Docker 编排、SkyWalking 接入 |
| 产品/业务 | PRODUCT | 领域实现进度（哪些能用、哪些还没做）、核心规则（截单/复核/取消矩阵） |

## 4. 三条最容易踩的坑（先记住，能省半天）

1. **成功判定是 `code === 0`，不是 HTTP 200**。HTTP 200 + `code≠0` 是业务失败（`API.md §2`）。
2. **写接口必须带 `Idempotency-Key`**，且"同一次操作重试要复用同一个键"；键的粒度要覆盖"同一动作的不同目标"（`API.md §3`）。
3. **雪花 ID 是字符串**（19 位整数超出 JS 安全范围），前后端都别转 Number；后端 `Long` 已统一序列化为字符串（`API.md §2.4`）。

## 5. 文档维护约定

- `docs/guide/` 下的文档**描述现状**，随代码一起改；改接口/加模块/加表时同步更新对应小节。
- 根 `README.md` 是**历史记录**，只追加不回头改（编号小节，便于引用）。
- `docs/frontend/api-contract.md` **自动生成**，永远不要手改：

```bash
perl scripts/export-api-contract.pl > docs/frontend/api-contract.md
```

- 发现文档与代码不一致，以**代码 + 设计文档（`项目详细设计/`）**为准，并回来修文档。
