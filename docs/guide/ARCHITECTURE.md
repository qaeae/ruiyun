# 项目结构与架构

> 面向：要改代码、加模块、加表、起服务的人。
> 读完你应该能回答：**18 个模块各管什么、代码在哪个包、能不能直接调别的域、本机怎么跑起来。**

---

## 1. 模块全景（18 个 Maven 模块）

```
                      ┌──────────────────────────────────────────┐
                      │  可部署服务（各有 main 启动类）             │
                      │  wms-gateway  :8088   网关（路由/身份透传） │
                      │  wms-auth     :8081   认证 + RBAC + 审批    │
                      │  wms-masterdata :8082 主数据                │
                      │  wms-fns      :8084   财务对账/结算         │
                      │  wms-core     :8080   其余全部域（单体）    │
                      └───────────────────┬──────────────────────┘
                                          │ 依赖
        ┌─────────────────────────────────┼──────────────────────────────┐
        │                        业务域模块（按域打包）                     │
        │  wms-mod-au  认证授权(35)  wms-mod-md  主数据(44)               │
        │  wms-mod-iv  库存(41)      wms-mod-outbound 出库(41)            │
        │  wms-mod-fns 财务(66)      wms-mod-acct 资金(11，无 HTTP)       │
        │  wms-mod-logistics 物流(19) wms-mod-ops 运维(23)                │
        │  wms-mod-pda 手持端(16)                                         │
        └─────────────────────────────────┬──────────────────────────────┘
                                          │ 依赖
        ┌─────────────────────────────────┼──────────────────────────────┐
        │  公共层：wms-common（20 个子包，80 类）  wms-kernel（契约：19 类） │
        │  wms-discovery（服务发现）                                      │
        └────────────────────────────────────────────────────────────────┘
                                          
        质量守护：wms-archtest（ArchUnit：分层/边界/权限/命名）
```

括号内是该模块的 Java 文件数（`find src/main/java -name '*.java' | wc -l`）。

**两条依赖铁律**（`wms-archtest` 会强制）：

1. **公共层不得反向依赖业务模块**（`wms-common` / `wms-kernel` 不 import `com.wms.platform.modules.*`）；
2. **域之间不能直接依赖对方的实现**（只允许依赖对方的 `app` 服务接口或 `api` 契约，见 §3）。

## 2. 域内四层分包（每个 `wms-mod-*` 结构一致）

```
wms-mod-outbound/src/main/java/com/wms/platform/modules/outbound/
├── api/                          # 对外契约层
│   ├── OutOrderController.java   #   HTTP 入口，只做参数校验 + 调 app
│   ├── ro/                       #   请求对象（Ro，含 @NotNull/@NotBlank 校验）
│   └── vo/                       #   响应对象（Vo，不含 Po 字段泄漏）
├── app/                          # 应用层（用例编排，@Transactional 在这里）
│   ├── OutboundOrderService.java #   接口（**别的域只能依赖这个**）
│   └── impl/OutboundOrderServiceImpl.java
├── domain/                       # 领域层（纯业务规则，不碰 MyBatis / HTTP）
│   ├── statemachine/             #   状态机（唯一允许改状态的地方）
│   ├── CancelRollbackRule.java   #   取消回滚矩阵
│   ├── CutoffRule.java           #   截单判定
│   └── WeightCheck.java          #   称重偏差校验
├── infrastructure/               # 基础设施实现
│   ├── persistence/              #   Mapper + po + dto  （MyBatis-Plus）
│   └── remote/                   #   跨域/外部适配器（如装运接口）
└── config/                       # 本域装配（Bean、常量）
```

| 层 | 能依赖 | 不能依赖 | 被守护的规则 |
|----|--------|---------|-------------|
| `api` | `app`、`ro/vo` | Mapper、`infrastructure` | `Controller不得直接使用Mapper` / `Controller不得依赖基础设施实现` |
| `app` | `domain`、`infrastructure`、其他域 `app` 接口 | `api`（Controller） | `应用层不得依赖Controller` |
| `domain` | 公共层 | Mapper、`api` | `领域层不得依赖Mapper` / `领域层不得依赖接口层` |
| `infrastructure` | 公共层、`domain` | `api` | `Mapper的包位与命名` |

**POJO 后缀约定**（`StandardsGuardTest` 强制包的落位）：
`Po`（表映射）· `Dto`（内部传输）· `Ro`（请求）· `Vo`（响应）· `Qo`（查询）· `Bo`（业务对象）。

## 3. 跨域怎么调（最容易写错的地方）

| 场景 | 正确做法 | 禁止 |
|------|---------|------|
| 读别的域的数据（如出库要查主数据） | 依赖对方 **`app` 服务接口**（如 `MasterDataQueryService`） | `import` 对方 Mapper / 直接写对方表的 SQL |
| 让别的域做事（如冻结运费、取号） | 定义**端口接口**放在本域 `infrastructure/remote`，实现注入对方 `app` 服务 | 在事务里同步调用外部 HTTP 去改价（会让事务长期待网络） |
| 通知别的域（非强一致） | `pf_outbox` 发件箱 + MQ（`EventTopics`） | 在一个本地事务里跨库写 |
| 组合多个域 | 在 `app` 层编排，每个域的写各自进事务；**不做分布式事务** | 试图用 XA/Seata 把跨域包成一个大事务 |

实测过的两个端口：`FeeFreezePort`（→ `wms-mod-acct`）、`ShipmentPort`（→ `wms-mod-logistics`）。

## 4. 五个服务与网关路由

| 服务 | 端口 | 职责 | 网关路由（`wms-gateway/application.yml`） |
|------|------|------|------------------------------------------|
| `wms-gateway` | 8088 | 统一入口、JWT 验签、身份头透传、限流 | — |
| `wms-auth` | 8081 | 登录/刷新/身份、RBAC 授权、审批流 | `/api/v1/auth/**` |
| `wms-masterdata` | 8082 | 仓库/库位/商家/商品/设备 | `/api/v1/master/**` |
| `wms-fns` | 8084 | 对账、结算、价目、勾稽、垫付 | `/api/v1/finance/**`、`/api/v1/pricing/**`、`/api/v1/reconcile-bills/**` |
| `wms-core` | 8080 | 库存、出库、物流、运维、PDA 等 | `/api/**`（兜底，**必须放在最后**，否则会吃掉前面所有路由） |

> 路由顺序是硬约束：Gateway 按声明顺序匹配，`/api/**` 在前会让 auth/masterdata/fns 全部失效。

**按域拆服务而不是一上来微服务**：当前是"模块化单体 + 少量独立服务"，出库/库存/物流仍在 `wms-core` 里同进程调用，
既保留了域边界（ArchUnit 守着），又避免了分布式事务。需要拆时按模块平移即可（模块之间本来就没有实现级依赖）。

## 5. 公共层能力（别自己再造一遍）

`wms-common` 的 20 个子包，按用途分：

| 子包 | 提供什么 | 典型类 |
|------|---------|--------|
| `api` | 统一响应体、业务异常、错误码 | `ApiResponse` `BizException` `ErrorCode`（在 kernel） |
| `security` | 当前用户、租户、权限判定、断言 | `SecurityContext` `PermissionCodes` |
| `context` | 租户/追踪上下文 | `TenantContext` `TraceContext` |
| `idempotent` | 幂等判定（replay / mismatch / in-progress） | `IdempotencyService` `RequestHasher` |
| `id` | 雪花 ID | `SnowflakeIdGenerator` |
| `mapstruct` | 集合映射扩展 | `MapStructExt` |
| `cache` | jetCache / Redisson 封装与失效 | — |
| `datasource` | 多租户与读写路由 | — |
| `messaging` | 发件箱、MQ 发送 | `OutboxPublisher` |
| `device` | PDA 设备登记与冲突 | — |
| `storage` | 文件上传（面单、凭证） | — |
| `payment` `approval` | 支付通道、审批回调契约 | `ApprovalActionHandler` |
| `trace` `health` `config` `constant` `gateway` `remote` `nacos` | 链路、健康检查、外呼客户端等 | `HeaderConst` `TraceConst` |

## 6. 数据模型（52 张表，按前缀分域）

| 前缀 | 张数 | 含义 | 关键表 |
|------|------|------|--------|
| `au_` | 7 | 认证授权 | `au_user` `au_role` `au_permission` `au_user_role` `au_role_permission` `au_user_warehouse` `au_approval` |
| `md_` | 7 | 主数据 | `md_warehouse` `md_zone` `md_location` `md_merchant` `md_sku` `md_sku_unit_conv` `md_device` |
| `iv_` | 5 | 库存 | `iv_sku`（三态）`iv_location`（库位实物/分配）`iv_movement`（流水）`iv_stocktake` `iv_stocktake_item` |
| `out_` | 2 | 出库 | `out_order` `out_order_item` |
| `in_` | 2 | 入库 | `in_asn` `in_asn_item`（**仅建表，未实现**） |
| `cs_` | 5 | 合同/空间 | `cs_contract` `cs_price_list` `cs_price_item` `cs_space_quota` `cs_rate_snapshot`（**仅建表**） |
| `lg_` | 3 | 物流 | `lg_shipment` `lg_shipment_track` `lg_shipment_proof` |
| `fn_` | 8 | 财务 | `fn_rate_card` `fn_rate_item` `fn_reconcile_bill` `fn_reconcile_item` `fn_settle_batch` `fn_advance_payment` `fn_daily_reconciliation` `fn_fee_order` |
| `ac_` | 4 | 资金 | `ac_merchant_account` `ac_fee_freeze` `ac_ledger` `ac_channel_order` |
| `pf_` | 8 | 平台公共 | `pf_idempotent` `pf_biz_status_log` `pf_outbox` `pf_audit_log` `pf_scheduled_job` `pf_job_run` `pf_gate_record` `pf_pda_conflict` |

**三条必须记住的关系**：

```
iv_sku(实物=可用+预留)  ──sum(location.physical)──▶  iv_location(按 库位×SKU)
        ▲                                                  ▲
        │ 建单 reserved+ / 装车 physical-                    │ 派发 allocated+ / 拣货 -
      out_order ──每行明细──▶ out_order_item ──lineNo 是后续所有作业动作的定位键
        │
        └── 每次状态迁移写一条 pf_biz_status_log（含操作人/设备/traceId/幂等键）
```

公共字段约定（所有表）：`id`（雪花）`tenant_id` `create_time` `update_time` `create_by` `update_by` `deleted`(0/1)。
**逻辑删除而非物理删除**；查询必须带 `tenant_id`（`TenantContext` 提供）。

## 7. 本地运行

### 7.1 前置

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | `mvn -v` 确认 |
| Maven | 3.9+ | |
| MySQL | 8.x | 库名 `wms_platform`（或验证库 `wms_docker`） |
| Redis / RabbitMQ | 任意近期版本 | Nacos 可选（不启用时用 `WMS_CORE_URI` 绝对地址） |
| Node + pnpm | Node 20+/pnpm 12 | 前端在 `web/` |

### 7.2 最短路径（不用 Docker）

```bash
# 1) 建库（Flyway 启动时自动建表，无需手工 DDL）
mysql -uroot -p -e "CREATE DATABASE wms_platform CHARACTER SET utf8mb4"

# 2) 起服务（5 个进程，各自一个终端；或 IDE 里直接跑 *Application）
mvn -pl wms-core -am spring-boot:run
mvn -pl wms-auth -am spring-boot:run
mvn -pl wms-masterdata -am spring-boot:run
mvn -pl wms-fns -am spring-boot:run
mvn -pl wms-gateway -am spring-boot:run

# 3) 起前端（走网关 8088）
cd web && pnpm install && VITE_API_TARGET=http://127.0.0.1:8088 pnpm dev
```

**注意**：vite 默认只绑 `[::1]`，用 `http://localhost:5173` 访问，别用 `127.0.0.1`。

### 7.3 造演示数据（铺满 9 个出库状态）

```bash
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wms_docker < scripts/seed-demo-master.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wms_docker < scripts/seed-smoke-users.sql
node scripts/seed-demo-outbound.mjs --base http://127.0.0.1:28088
```

详见根 README §62。

### 7.4 构建与测试

```bash
mvn clean verify -Dit.skip=true          # 编译 + 单测 + 架构守护（不需要 Docker）
mvn -pl wms-archtest -am test            # 只跑分层/边界/权限守护
mvn -pl wms-core -am verify -Dit.skip=false   # 集成测试（需要 Docker / 真库）

cd web && pnpm exec vue-tsc --noEmit     # 前端类型检查
cd web && pnpm check:standard            # 前端规范自检（R1–R12）
cd web && pnpm check:api                 # 前端调用 vs 后端契约（107 条）
```

## 8. 代码地图（"我要改 X，去哪找"）

| 我要… | 去哪 |
|-------|------|
| 加一个接口 | 域内 `api/` 加 Controller 方法（必须 `@RequiresPermission`）→ `app/` 接口 + 实现 → `ro/`、`vo/` |
| 改状态流转规则 | `域/domain/statemachine/`（**唯一允许改 status 的地方**） |
| 改取消回滚行为 | `wms-mod-outbound/domain/CancelRollbackRule` |
| 加库存动作 | `wms-mod-iv/app/InventoryService`（三态与两级账必须同时维护） |
| 调别域数据 | 依赖对方 `app` 服务接口，不要碰 Mapper |
| 加错误码 | `wms-kernel/.../ErrorCode.java`（按域分段，见 `API.md §5`） |
| 加权限码 | `wms-kernel/.../PermissionCodes.java` + 给接口加注解 + 授权数据 |
| 加表/改字段 | `wms-core/src/main/resources/db/migration/V___*.sql` + `po/` + `Mapper` |
| 加定时任务 | `pf_scheduled_job` 登记 + `wms-mod-ops` 监控页可见 |
| 加配置项 | 各服务 `application.yml` 的 `wms.*` 下，配默认值，不要硬编码 |

## 9. 架构守护（改完自己先跑）

`mvn -pl wms-archtest -am test` 会检查 13 条规则，最常被踩的几条：

| 规则 | 违规时的典型症状 |
|------|-----------------|
| `公共层不得反向依赖业务模块` | 在 `wms-common` 里 import 了 `modules.*` |
| `Controller不得直接使用Mapper` | Controller 里为了省事直接查库 |
| `领域层不得依赖Mapper` | 领域规则里写了 SQL/Wrapper |
| `条件构造器只能出现在Mapper` | Service 里 `new LambdaQueryWrapper<>()` |
| `接口必须声明权限或公开原因` | 新接口忘了 `@RequiresPermission` |
| `Po/Dto/Ro 必须落在对应包` | 把 Ro 放在 Controller 同级 |

> 还有一条**反向经验**：状态只能由状态机改。曾经在 `app` 层直接 `po.setStatus(...)`，
> 被 ArchUnit 拦下——否则"谁改了状态"将无法追溯，状态轨迹也会失真。
