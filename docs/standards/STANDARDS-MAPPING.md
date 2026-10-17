# 规范落地映射表（STANDARDS-MAPPING）

> 版本：v1.0（2026-10-14）
> 输入：`.agents/skills/admin-backend-knowledge/`（后端规范）、`.agents/skills/admin-system-knowledge/`（前端规范）
> 定位：**技能规范 → 本项目**的逐条适配结论。冲突时以设计文档为准，本表给出落地口径。
> 编号：后端 `B1–B16`、前端 `F1–F12`。代码评审 / 提交信息**直接引用编号**（如「符合 B1、B5」）。

---

## 一、优先级与冲突处理

```
① 设计文档（项目一方案.md V1.2 / 01-N、02-N …）  ← 对外契约与数据模型的唯一权威
② 本映射表                                       ← 适配结论
③ .agents/skills/**                              ← 规范原文
④ 现有代码                                       ← 现状（偏差处按 ①②③ 实现，勿照抄）
```

判断口径：
- **对外契约类**（响应体、错误码、异常类型名、表结构、模块划分）→ 设计文档优先，规范做**适配**；
- **工程纪律类**（分层收口、命名后缀、静态工厂、映射、注释、常量、注入、提交）→ 规范优先，**全量采纳**；
- 新增冲突：先补设计文档与本表结论，再写代码。

---

## 二、后端强制规则（B1–B16）

| 编号 | 规则 | 规范出处 | 本项目落地 | 强制方式 |
|:---:|------|---------|-----------|---------|
| **B1** | 分层：Controller → Service → Mapper；**SQL/Wrapper 只允许写在 Mapper 的 `default` 方法或 XML/`@Select`**，Service 内禁止出现 `Wrappers`/`LambdaQueryWrapper`/`QueryWrapper` | 后端 SKILL §1 | 包映射：Controller→`modules.<域>.api`；Service→`modules.<域>.app`（`impl` 子包）；Mapper→`modules.<域>.infrastructure.mapper`；**MapStruct 映射器→`modules.<域>.infrastructure.mapstruct`**（放 api 会违反「api 不得依赖 infrastructure」，因为它依赖 Po） | **ArchUnit**（Wrapper 收口 / Mapper 包位 / app 不得依赖 Controller）+ 评审 |
| **B2** | 接口契约：类上 `@Validated`+`@RestController`+`@RequestMapping`；请求体 `@Valid @RequestBody`；分页统一；**禁止用 `Map` 收发请求体** | 后端 SKILL §2 | 响应统一 `ApiResponse`（**适配**，见 §三）；分页：设备档案等小数据量接口不分页（已注明理由），大数据量接口待定义 `PageQuery`/`PageResult` | 评审 |
| **B3** | POJO 后缀与包：`Po`/`Dto`/`Ro`/`Vo`；Po 必须 `@TableName(autoResultMap=true)`；逻辑删除列加 `@TableLogic`；**禁止 `@TableField(exist=false)`** | 后端 SKILL §3 | 后缀保留；包路径（适配）：`infrastructure.po` / `api.dto` / `api.ro` / `api.vo`。⚠️ **Vo 不继承 Po**（规范里有"Vo 继承 Po"的用法）：本项目 Po 在 infrastructure 层，api 不得依赖它 → 改为 MapStruct 显式映射，**代价是给 Po 加字段要同步 Vo**（已写入 `DeviceVo` 类注释） | **ArchUnit**（命名与包位，大小写不敏感） |
| **B4** | **禁止 `new Xxx()` + 连续 `setXxx()`**，一律用类内静态工厂 `Xxx.of(...)` | 后端 SKILL §3 | 全量采纳 | 评审 + 现有代码已整改 |
| **B5** | 异常：一律 `throw new BizException(ErrorCode.X, "中文消息")`（**带状态码 + 消息**）；**禁止** `RuntimeException` / `IllegalStateException` / `IllegalArgumentException` 等无状态码异常 | 后端 SKILL §5 | 类型名适配为 `BizException`；码为 5 位域码（非 400/500） | **ArchUnit**（禁构造无状态码异常） |
| **B6** | 常量集中：按领域建 `interface XxxConst`；禁止散落 `private static final` 魔法值；key 拼接走常量类的 `static` 方法；新建前先盘点复用 | 后端 SKILL §6 | 包位：`com.wms.platform.common.constant`（跨模块）、`modules.<域>.constant`（域内） | 评审 |
| **B7** | 注释：类/接口/方法 javadoc（含 `@param`/`@return`）；字段逐项中文注释；**Service 业务方法体内必须有编号步骤注释 `// 1. xxx`** | 后端 SKILL §7 | 全量采纳；查询类至少含「查询→转 Vo→回填→组装」，写操作至少含「转换→校验→落库→缓存刷新」 | 评审 |
| **B8** | MapStruct：映射器类名以 `MS` 结尾、`extends MapStructExt`、`@Mapper(config = MapperCentralConfig.class)`、`INSTANCE = Mappers.getMapper(...)`；批量用 `MapStructExt.toPojoList(list, INSTANCE::toXxx)`；**禁止**映射器声明集合方法；禁止手写 Bean 拷贝 | 后端 SKILL §4 | 采纳；已在 `wms-common` 提供 `MapStructExt` + `MapperCentralConfig` | **ArchUnit**（`*MS` 禁 `*List` 方法）+ 评审 |
| **B9** | 注入：`@RequiredArgsConstructor` + `private final`；**禁止字段 `@Autowired`** | 后端 SKILL §1、conventions §2 | 采纳；配置值用**构造参数** `@Value` 或 `@ConfigurationProperties`（均属构造注入） | **ArchUnit**（禁字段 `@Autowired`） |
| **B10** | 集合与校验：需要可变集合时用 `CollUtil.newArrayList()`/`newHashSet()`（Hutool）；校验用 `jakarta.validation`，提示信息中文 | conventions §2 | 采纳（**适配**：只读字面量集合保留 `List.of`/`Map.of`，理由见 §五） | 评审 |
| **B11** | 缓存与 Redis：jetCache 编程式 + `CacheConst` 收口 + key 方法；写操作「立即刷新/失效 + 延迟 5 秒兜底」；必须 `RedissonClient`，禁 `RedisTemplate`；多命令原子操作用 Lua 且脚本集中 | 后端 SKILL §8、§9 | **缓期落地**（一期无缓存需求，jetCache 不在依赖内）。一期规则：新增缓存前先在 `CacheConst` 定义 key 与刷新策略；禁裸 `RedisTemplate` | 评审（二期接入 jetCache 后加 CI 项） |
| **B12** | 数据库变更：库上执行 DDL 后同步 Po | 后端 SKILL §11 | **适配为更强约束**：DDL 由 `01-数据模型DDL与状态机.md` 抽取生成 Flyway 迁移（`V1__baseline.sql` 禁止手改），新增变更走 `V2__*.sql`；Po 字段与迁移脚本必须同步 | 评审 + 迁移脚本评审 |
| **B13** | 代码风格：4 空格缩进；UTF-8；**LF 换行**；import 不用 `*` 通配 | conventions §2 | 全量采纳 | 评审（后续可加 checkstyle） |
| **B14** | 提交规范：简洁中文、聚焦单一目的、不混合重构与新功能、引用相关 Issue | conventions §3 | 采纳；规范相关改动在提交信息里引用 `B*` 编号 | 评审 |
| **B15** | 状态只能由状态机修改；关键操作留痕（操作人/时间/设备/前后值） | 后端 SKILL §10 + 设计文档 | 已有 ArchUnit 规则（禁绕过状态机 `setStatus`）；审计表 `pf_audit_log` | **ArchUnit** + 评审 |
| **B16** | 红线（本项目特有，优先级高于规范原文）：资金/库存流水不可删除；PII 不入日志与列表响应；禁止硬编码密钥；**不允许「必须手工改库才能恢复」的场景** | 设计文档 §14 + 04 §一 | 全量强制 | 评审 + 集成测试断言 |

---

## 三、对外契约适配表（规范原文 → 本项目）

| 规范原文 | 本项目取值 | 适配说明 |
|---------|-----------|---------|
| `R<T>`，`R.ok()` / `R.fail(msg)`，前端判 `code === 200` | `ApiResponse<T>`，`ok()` / `fail(ErrorCode)`，**成功码 `code = 0`**，附 `traceId` | 形态一致（统一包装 + 静态工厂），仅成功码与字段不同；前端判定改为 `code === 0`（03 §1.2 定稿） |
| `BuzException(ResultCode.X)`，码 400/401/403/404/500 | `BizException(ErrorCode.X)`，**5 位域码**（1xxxx 通用…8xxxx 权限）；业务失败 HTTP 200，状态机冲突/幂等冲突 409 | 同形态（携带码 + 中文消息）；域码更细，便于按域告警与前端分流 |
| `throw new BuzException(...)` 必带码 | 同 | 已一致，仅类名不同 |
| 逻辑删除 `del_flag` `Y`/`N` | `deleted` `0`/`1`（+ 全局 `logic-delete-field`） | 01 §1.1 已定；`@TableLogic` 仍按 B3 显式标注 |
| `@TableId(ASSIGN_ID)`（MyBatis-Plus 雪花） | `@TableId(INPUT)` + 自研 `SnowflakeIdGenerator`（epoch 2026、worker 可配、`timestampOf` 可反解） | 保留自研：多实例 worker 显式配置、可测、可在 ID 中反解写入时间 |
| 按层拆模块 `common-pojo/mapper/service/application` | 按域分包的模块化单体（`wms-common` + `wms-core/modules/<域>/{api,app,domain,infrastructure}`） | 02 §5.2 M1 已定并固化在 ArchUnit；**不采纳按层拆模块**（理由见 §五） |
| 「仓库内没有 SQL 迁移文件，加列需人工执行 DDL」 | Flyway 迁移由设计文档抽取 | 采用更严做法（可追溯、可重放、CI 可校验） |
| `Po`/`Dto`/`Ro`/`Vo` 后缀 | 同 | 采纳，包位按域分层 |
| `YesNoConst`（`Y`/`N`） | `FlagConst`（`0`/`1`）+ 语义化常量 | 值体系不同，不引入 `Y/N` 常量，避免与 `deleted` 语义打架 |
| Hutool `CollectionUtil.newArrayList()` 替代 `List.of()` | 可变集合用 `CollUtil`；**只读字面量集合保留 `List.of`/`Map.of`** | 规范意图是"需要可变集合时别用不可变工厂"；只读字面量用不可变工厂更安全，故适配（§五 有理由） |
| `micorSchedulePool` 延迟 5 秒兜底刷新 | 二期随缓存落地 | 一期无缓存 |

---

## 四、前端强制规则（F1–F12，等同规范 R1–R12）

> **状态：缓期执行。** 前端工程计划 W5（2026-11-16）开工，开工前必须先读前端技能全套并完成 §四.2 的项目化改写。

| 编号 | 规则（摘要） | 本项目化改写 |
|:---:|-------------|-------------|
| F1 | 请求载荷必须由模型转换方法产生（`toParams()`/`toFormData()`），禁内联字面量传 API | 采纳 |
| F2 | 接口/类/常量统一放 `src/models/`，后缀 Qo/Vo/Bo/Dto；`.vue` 禁 `interface`/`class` | 采纳 |
| F3 | `fill()` 用 mapia；数字字段 `Number(x) \|\| 0`；`toParams()` 仅非空字段 | 采纳 |
| F4 | 每次请求传 `apiCode`；字段以后端接口文档为准 | **改写**：后端无 `apiCode` 体系；写操作传 `Idempotency-Key`，链路用 `X-Trace-Id`；接口文档 = `03-接口定义.md` |
| F5 | 页面 `loading = ref(true)` + 根容器 `v-loading` | 采纳 |
| F6 | 写操作用 `withMask(task, text?)` 包裹 | 采纳 |
| F7 | 模板禁业务字面量；字典内容走 `dictStore` | 采纳 |
| F8 | 字典值属性只声明 `string`；字面量类型命名后引用 | 采纳 |
| F9 | 状态/方法 JSDoc + 方法体 `// 1.` 步骤注释 | 采纳（与后端 B7 同源） |
| F10 | `componentMap` 注册、启动预注册、按钮 `hasPermission` | 采纳 |
| F11 | 字典批量加载、查询条件保留、平台切换刷新、图标库 | 采纳 |
| F12 | 页面书写：SFC 顺序、script 四分区、骨架类名、方法顺序 | 采纳 |

### 四.2 前端开工前置（W5 硬性门禁）

1. 读 `.agents/skills/admin-system-knowledge/` 全套（PROJECT-STANDARD + models-standard + page-standard + reference）；
2. 安装 pnpm（本机当前**未安装**）；
3. 按 §四 表格完成 F4 改写，并把「成功码 `code === 0`」写入 `src/utils/request.ts`；
4. 接通自检脚本：`.agents/skills/admin-system-knowledge/scripts/{check-standard,audit-standard}.mjs` →
   `pnpm check:standard` / `pnpm audit:standard`；
5. 模板复制起点：`templates/list-page.vue`、`templates/model-module.ts`。

> **2026-10-14 更新（前置包已产出）**：以上 5 项已整理为可执行清单，见
> [docs/frontend/W5-FRONTEND-KICKOFF.md](../frontend/W5-FRONTEND-KICKOFF.md)（含 7 项改写表、接入契约、
> 页面清单、DoR/DoD）。第 3 项的改写范围从"F4"扩展为 **7 项**——除成功码与请求头外，还包含
> **`Idempotency-Key` 的复用规则**（重试必须复用同一个键，否则幂等形同虚设）。

---

## 五、不采纳 / 延后清单（含理由）

| 项 | 结论 | 理由 |
|----|------|------|
| 按层拆模块（`common-pojo`/`common-mapper`/`common-service`/`application`） | **不采纳** | 02 §5.2 已定「模块化单体按域分包」并由 ArchUnit 固化；按层拆会让"域内高内聚"退化、跨域调用绕过状态机。技能的做法对外部项目成立，但与本项目 12 个业务域的组织方式冲突，返工面覆盖全部已交付代码 |
| `R<T>` + `code=200` | **不采纳** | 03 §1.2 定稿且已实现；改成功码会波及前端契约、外部渠道对接、6 项 MockMvc 断言 |
| `del_flag` `Y`/`N` | **不采纳** | 01 的 28 张表 DDL 已实测通过（含 CHECK 约束与唯一键）；`Y/N` 与 `deleted 0/1` 是等价的实现细节，改值无收益 |
| jetCache | **延后至二期** | 一期无缓存需求；jetCache 不在本机依赖缓存内。一期先落「`CacheConst` 收口 + 禁裸 RedisTemplate」纪律（B11），二期接入时再加 CI 项 |
| `apiCode` 请求头 | **不采纳（F4 改写）** | 本项目幂等与追踪由 `Idempotency-Key` + `X-Trace-Id` 承担，`apiCode` 会与 RBAC 网关设计重叠 |
| `YesNoConst`（`Y`/`N`） | **不采纳** | 本项目标记位统一 `0/1`（`deleted`、`is_forbidden` 等），引入第二套值体系只会制造转换错误 |
| Hutool 全面替代 `List.of`/`Map.of` | **部分适配** | 采纳其意图（可变集合用 `CollUtil`）；只读字面量保留不可变工厂，避免"本可不可变却被写成可变" |
| 技能内的项目知识（14 张 `sys_*` 表、`/backSysXxx` 路径、`Controller` 端点清单） | **不适用** | 属于外部项目的业务事实，本项目以 01/03 为准；技能库中该部分仅作**规范形态**参考 |

---

## 六、CI 强制项 ↔ 规则编号对照

| ArchUnit 规则（`wms-archtest`） | 对应编号 |
|-------------------------------|---------|
| `wms-common` 不得反向依赖业务模块 | 架构基线 |
| api 层不得依赖 infrastructure、Controller 不得使用 Mapper | B1 / B2 |
| `..mapper..` / `..config..` 之外不得出现 `Wrappers` 与 `com.baomidou.mybatisplus.core.conditions.*` | **B1** |
| Mapper 必须在 `..mapper..` 且以 `Mapper` 结尾、继承 `BaseMapper` | **B1 / B3** |
| `*Po` / `*Dto` / `*Ro` / `*Vo` 必须落在 `..po..` / `..dto..` / `..ro..` / `..vo..`（**大小写不敏感**，防 `XxxDTO` 绕过） | **B3** |
| 禁止 `RuntimeException` / `IllegalStateException` / `IllegalArgumentException` | **B5**（豁免 3 处，见下） |
| 禁止字段 `@Autowired`（必须构造注入） | **B9** |
| `*MS` 映射器不得声明含 `List` 的集合方法 | **B8** |
| 禁止 `System.out`/`System.err` | B7 / B13 |
| 禁止直接依赖 `RedisTemplate` | **B11** |
| 状态只能由状态机修改（禁绕过 `setStatus`） | **B15** |
| 业务域之间不得形成循环依赖 | 架构基线 |
| `ArchUnitSetupTest`：守护规则可见性 / 包命名可见，防「假绿」 | 规范可信性 |
| app 层不得依赖 `@RestController`（原「app 不得依赖 api」） | **B1**（2026-10-14 收窄，见 §七） |
| domain 层不得依赖 `..modules..api..`（新增，更严） | **B1 / B3** |
| `setStatus` 规则收窄为「对 Po 调用」（原禁止任何 `setStatus`） | **B15**（2026-10-14 收窄，见 §七） |

**B5 规则的 3 处豁免与 2 条实现约束（实测踩出，勿改回）**：

| 项 | 说明 |
|----|------|
| 豁免 `..common.api..` | `BizException` 自身必须继承 `RuntimeException` |
| 豁免 `..mapstruct..` | MapStruct `@MapperConfig` 注解元数据默认值引用了 `IllegalArgumentException`（框架产物） |
| 豁免 `..mapper..` | Mapper 的 lambda 方法引用会生成合成方法 `$deserializeLambda$`，其中 JDK 自行构造 `IllegalArgumentException` |
| 必须拆成多条独立规则 | `noClasses().should(A).orShould(B)` 实测**漏检**（抛 A 不报） |
| 必须用 `dependOnClassesThat` | `callConstructor(...)` 实测**抓不到** `new RuntimeException("...")`，会变成"永远通过" |

> 上述每一条规则都用"探针类"做过**双向验证**：探针在场必须变红、删除后必须变绿。新增规则请沿用该验证方式。
>
> **实践补充**：捕获异常时只捕获 `BizException`（不要 `catch (RuntimeException)`）。否则
> ①B5 规则会判定为依赖 `RuntimeException`；②更要紧的是"数据库连不上"会被伪装成"业务失败"，把运维带向错误方向。

---

## 七、落地过程发现并修正的问题（2026-10-14，设备网关任务）

写代码时才暴露的问题，逐条记录，避免后续重犯：

| # | 问题 | 影响 | 处置 |
|:--:|------|------|------|
| 1 | **`md_device` 跨文档缺口**：`03` §1.1 要求设备绑定校验、`03` API-MD-05 有设备档案接口，但 `01` §3.9 只有一行摘要、没有建表语句 | 服务端无法实现绑定校验；有人可能自己"顺手建一张表"，与设计漂移 | 在 `01` 新增 §3.10 完整 DDL（含字段口径说明）并抽取为 `V2__md_device.sql`；本机 MySQL 8.4.9 实测 29 张业务表 + Flyway 元数据表、`uk_md_device` 生效 |
| 2 | **`SkeletonIT` 的表数断言从未被执行且算错**：断言"库内总表数 = 28"，未排除 Flyway 自己的 `flyway_schema_history`；且该测试只支持 Testcontainers，本机无 Docker 所以一直跳过 | 一旦 CI 有 Docker 就会红；且"28"这个数字与 V2 之后的实际不符 | 断言改为**排除 Flyway 元数据表**并更新为 29（= V1 28 + V2 1）；用本机一次性 MySQL + Flyway 实测确认（30 = 29 + 1） |
| 3 | **条码前缀用枚举名匹配**：库位枚举是 `LOCATION`，而现场前缀是 `LOC-` | 库位码永远识别不出来（静默失败，不报错，只表现为"扫了没反应"） | `BarcodeType` 增加显式 `prefix` 字段；单测 `shouldRecognizePrefixes` 固定该行为。同类缺陷外部规范也记录过（枚举 code 与库中字典值不一致） |
| 4 | **两条 ArchUnit 规则过宽导致误报**：①"禁止任何 `setStatus` 调用"误报 MapStruct 映射 `Vo.setStatus` 与 `Po.of` 装配自身状态；②"禁止依赖 `RuntimeException`"误报 `catch (RuntimeException)`（拦截器把任意异常转 403） | 误报会逼人 `allowEmptyShould`/关规则，反而让防线消失 | ①收窄为"对 Po 调用 `setStatus`"且豁免 `..po..` 自身；②拦截器改为只 `catch (BizException)`（语义也更正确：查库失败不该伪装成"设备未登记"） |
| 5 | **`@DisplayName` 里用 ASCII 双引号**（中文文案中夹 `"..."`） | 编译失败，且报错位置指向行首，排查费时 | 统一改用中文引号 `「」`；已在本文件 §七 与 README 的坑清单登记 |
| 6 | **启动期循环依赖**：`PlatformInfrastructureConfig` 注入 `DeviceService`（业务服务），而 `DeviceServiceImpl` 需要由该 config 定义的 `SnowflakeIdGenerator` | **应用根本起不来**；126 项单元测试全绿也没发现（它们不启动 Spring 上下文） | 拆出 `WebInterceptorConfig` 只做 Web 装配；**基础设施配置禁止反向依赖业务模块**（已写入类注释）。不要用 `@Lazy` 把环藏起来 |
| 7 | **`RequestHasher` 的 ObjectMapper 未注册 `JavaTimeModule`** | 任何**带时间字段的写接口**在幂等哈希时抛异常 → 500；报错"哈希序列化失败"与时间毫无关联，极难排查 | 改为 `new ObjectMapper().findAndRegisterModules()` |
| 8 | **时间格式与 `03` §七 示例不一致**：文档示例为 `2026-12-15 09:31:02.120`（空格分隔），Jackson 默认只认 ISO 的 `T` | 完全照文档实现的客户端**全部解析失败**（体感像接口 500） | 新增 `JsonTimeConfig`：反序列化宽容接收（空格或 `T`、0~9 位小数），序列化统一输出 `yyyy-MM-dd HH:mm:ss.SSS`。注意 `spring.jackson.date-format` 对 `LocalDateTime` **无效** |
| 9 | **报文不可解析返回 500**（`HttpMessageNotReadableException` 落到兜底分支） | PDA 离线队列判定为"服务端故障"并**无限重试**，静默拖住一个仓库的作业 | `GlobalExceptionHandler` 新增映射 → `400 + 10001` |
| 10 | **`01` §6 的"待人工处理任务"无表承载**：`03` API-PDA-03 要求 `GET /pda/conflicts`，但 `pf_` 段没有任务表 | 冲突无法落工作流台账（审计日志是追加型，表达不了"待处理→已处理"） | 新增 `pf_pda_conflict`（`01` §3.11 + `V3__pf_pda_conflict.sql`），`uk_pda_idem` 防待办刷屏 |
| 11 | **主数据 DDL 缺口**：`01` §3.9 只有摘要行、没有建表语句，而库存接口（API-IV-*）与 PDA 错扫判定都需要"编码→内部 ID"解析 | 库存/现场作业无法实现；容易各自"顺手建表"导致设计漂移 | 补 `01` §3.12 + Flyway `V4__md_master_data.sql`（5 张表）；本机实测 35 张业务表 |
| 12 | **我的代码违反了自己定的规则**：接口层 VO 直接引用 infra 的 Po（`LocationStockVo.of(IvLocationPo)`） | ArchUnit 报 **20 处 `api → infrastructure` 违规**；若放过，接口层会开始顺手改 Po，影响所有读写路径 | 应用层定义 `LocationStockView`/`MovementView`，接口层只见视图。**规则的价值在于会在我走神时拦住我** |
| 13 | **`SkeletonIT` 从未执行过**（Docker-only），改用外部 MySQL 后立刻暴露它自身 3 个缺陷：硬编码 `table_schema='wms_platform'`、`assertThat(Optional).contains()` 是值相等而非子串、用例不自清理（持久库残留幂等记录） | 一条"最需要被执行"的建表数断言长期形同虚设；Testcontainers 全新库掩盖了自清理问题 | 改用 `DATABASE()`；`orElseThrow()` 后再断言；加 `@BeforeEach` 清理 `IT-%`。**能跑的 IT 才算 IT** |

| 14 | **`pda` 域 3 套跨域实现**（`SyncStockMapper`/`SyncMovementMapper`+`SyncMovementPo`/`StockSnapshotRow`）直接读写 `iv_*` | 装车出库只扣 SKU 级、不扣库位级 → `01` §4 不变量 2 日终必报差异；且存在两套库存 SQL 各自演进的风险 | **已删除**，改调 `iv.app`（`InventoryService`）；编码解析改调 `md.app`（`MasterDataQueryService`）。仅剩 `SyncBizStatusMapper`（只读）待 W7 替换 |
| 15 | **幂等表 TTL 与离线补传时长不匹配**：`pf_idempotent` 7 天过期后，超过 7 天的补传会被当成新请求**再扣一次库存** | 长假期/设备损坏后才找回的作业会重复扣减 | 新增 `InventoryService.hasApplied(idemKey)`（查 `uk_movement_idem`），上层据此回执 `duplicated` |
| 16 | **我第二次违反自己定的规则**：Service 里写 `Wrappers.lambdaUpdate()`；Service 里对 Po 调 `setStatus(...)` | ArchUnit 两条规则分别报错；若放过，B1「SQL 收口」与 `01` §1.2「状态只能由状态机改」都会被"照着抄"破坏 | 状态更新收口到 `Mapper.updateStatus(...)`；状态下沉到 Po 静态工厂（`LocationPo.of`/`SkuPo.of`） |
| 17 | **`au_*` 全缺失**：`03` 定义了 API-AUTH-01~06，`01` 基线 DDL 里没有任何用户/角色/权限表 | "谁在操作"只能靠请求头自证 → `pf_audit_log` 记的是伪造值、库存调整无门槛，审计与越权防护同时失效 | 补 `01` §3.14 + Flyway `V6__rbac.sql`（6 张表）；本机实测 42 张业务表 |
| 18 | **登录接口与幂等拦截器冲突**：`/api/**` 写接口一律要求 `Idempotency-Key`，而登录发生时客户端还没有会话、无从生成业务幂等键 | 登录直接 400；若强行要求，前端只会随便塞 UUID，把幂等防线变成形式主义 | 把 `/auth/login`、`/auth/refresh`、`/auth/me` 加入豁免（三者不产生业务副作用），管理类写接口照旧要求幂等键 |
| 19 | **我第三次栽在 B1（Wrapper 收口）**：`RbacAdminServiceImpl` 里 4 处 `Wrappers`；另有 1 处 B5（抛 `IllegalStateException`） | ArchUnit 分别报错；前两次也是同类问题（iv、md 域各一次） | Wrapper 收口为 Mapper 方法（`deleteByRole`/`deleteAny`/`selectByIds`）；异常改 `BizException`。**把"顺手写 Wrappers"固化成习惯前，规则会一直拦** |
| 20 | **ArchUnit 自身的一条误报**：含 lambda 的类被 javac 合成 `$deserializeLambda$`，其中 **JDK 自己构造 `IllegalArgumentException`** | B5 规则在非 mapper 类上随机报错（实测命中 `IdempotentMapper`、`RbacAdminServiceImpl`）；若因此把整个类加白名单，会连真实违规一起放过 | 按方法名精确豁免；并新增探针测试证明"该豁免仍必要"（探针同时防"假绿"） |
| 21 | **`iv_stocktake` 全缺失**：`03` 定义了 API-IV-03，`01` §4 与 `02` M6 都把盘点列为库存差异的唯一合法来源，但基线 DDL 无盘点表 | 账实差异没有合法出口 → 要么靠 DBA 手工改库（无审计、无复核），要么差异永久挂着 | 补 `01` §3.15 + Flyway `V7__iv_stocktake.sql`（2 张表）；本机实测 44 张业务表 |
| 22 | **我第一版用 ThreadLocal 透传仓库**（`StocktakeServiceImpl.approve` 定位库存账时缺商家） | 隐式依赖执行顺序、并发下会串数据；且属于"绕过模型缺陷"的写法 | 根因是明细表缺字段 → 给 `iv_stocktake_item` 加 `merchant_id`，让明细自足。**需要 ThreadLocal 传数据时，通常是模型缺字段** |
| 23 | **领域规则的"空值"落不进表结构**：`ReconcileDiffRule` 在"分母异常/平台无费用"分支返回 `null` 的 `diffAmount/diffRate`，而 `01` §3.6 规定这些列 NOT NULL；且"平台无该运单费用"是合法业务状态 | 若简单地把 null 改成 0 或改表为可空：① 用 NULL 会让 `SUM(platform_fee)` 忽略该行 → **汇总里把漏计费抹平**（异常消失）；② 改 DDL 等于动已评审的基线 | 落库归一为 0，异常语义由 `diff_type`（平台无对应费用）与 `remark` 承载，**让异常显眼**而非掩盖。见 README §22.5 |
| 24 | **"生产禁 MOCK"只写在注释里，没有代码强制**：`04` §4 与 `WmsProperties.Payment.Channel.mode` 的注释都写明生产必须 REAL，但 `StartupSecurityCheck` 此前只检查了密钥，**没有检查资金通道模式** | 带 MOCK 上线会出现"以为在真扣款、其实只打日志"的形态，而对账单显示"已结算"——资金系统里最危险的假绿 | 新增第 4 条启动检查：prod + mode≠REAL → 启动失败；REAL 模式下无适配器也 fail-fast（不回退到模拟）。**只写在注释里的红线等于没有红线** |
| 25 | **限额判定用"已发生"口径 → 单笔巨款绕过限额**：`AdvancePaymentRule` 按 `已有累计 > 2000` 判，则新商家单笔垫付 2500 顺利通过（下一笔才拦），第一笔就已超额放账 | 限额形同虚设；且这类漏洞**只会在"单笔巨款"这种测试里暴露**，普通用例看着完全正常 | 登记时改按**预判口径**（已有累计 + 本笔、已有笔数 + 1）；`checkLimit` 保留已发生口径作为预检并在注释中说明二者语义不同（预检是参考，登记是权威）。见 README §24.4 |
| 26 | `LogisticsShipmentSeamMapper`：`fns` 跨域**只读** `lg_shipment`（`lg` 域 W7 才落地，而兜底扣减必须知道"哪些运单已揽收满 7 天未回传"） | 跨域直读会让"谁在改这张表"变得模糊；但比"先不做兜底扣减"更可接受 | 只允许 SELECT（**跨域写入禁止**：回写 `fee_back_at`/`actual_fee` 属 `lg` 域职责）；`lg` 域 app 落地后改调 `ShipmentQueryService` 并删除本类（W7 回收） |
| 27 | **`Layer` 状态扩展**：`01` 的 `fn_daily_reconciliation.status` 注释只列了「一致/有差异/已处理」，实际需要第 4 个值「待接入」（缺通道/承运商侧数据时**根本没法对账**） | 若把"缺数据"当成"有差异"，K2 未接入期间**每天都会报假差异**，差异清单会被噪音淹没，真差异出现时没人看 | 保留 DDL 不变（VARCHAR 无 CHECK，可扩展），在 `DailyReconcileRule` 显式实现四态并在 README §26.2 说明理由。**"没法对账"≠"有差异"** |
| 28 | **第五次 ArchUnit 拦截：对 Po 调 `setStatus`**（`DailyReconciliationServiceImpl`） | 违反 `01` §1.2「状态只能由状态机改」；且金额+差额+笔数+状态本应一起变，散着 set 容易出现"改了金额忘了改状态" | 下沉为 Po 的语义方法 `applyResult(platformAmount, channelAmount, diffAmount, diffCount, status, detailJson, operatorId)`——语义方法比逐个 setter 更不容易漏项。累计拦截统计见 README §26.6 |
| 29 | **`au_approval` 全缺失**：`02` M2-05（复核矩阵）、`03` API-AUTH-04/05、`04` §六 都要求双人复核，`V6__rbac.sql` 自己也写了"待后续切片补"，但表一直没建 | 复核无法落地 → 高危操作（库存调整/结算下发/账单冲正）只能单人执行，制度上"必须两人"在代码里无对应约束 | 补 `01` §3.16 + Flyway `V8__au_approval.sql`；本机实测 45 张业务表 |
| 30 | **指纹校验的两个坑（均由真实库 IT 抓出）**：① 直接对原始 JSON 字符串算 SHA-256 → MySQL `JSON` 列**存取会重排键/去空白**，读回后重算必然不同 → 校验"恒定阻断"；② 以为 `ORDER_MAP_ENTRIES_BY_KEYS` 能排序 → 该配置只对 `java.util.Map` 生效，对 `JsonNode` **无效**，仍然不一致 | 两种失效都不会在本地单测暴露（单测里字符串从不变化）；后果是要么"永远批不过"（业务停摆），要么为了能过而**放宽校验**（等于放弃防改内容） | `JsonNode → TreeMap/List` 递归转纯 Java 对象再序列化，指纹只取决于内容本身。**结论：哈希/指纹类校验的输入必须先规范化**（见 README §28.6） |
| 31 | **复核点放在接口入口层**（高危写接口改为"提交审批"），服务层保持可直接调用 | 内网直调服务可绕过复核 | 明确口径并登记：服务层不能强制复核（审批通过后的执行本身要调服务，会死循环）；一期靠"服务不对外暴露 + 调用方仅审批回调/定时任务"约束，二期由网络边界保证（`01` §3.16） |
| 32 | **`pf_scheduled_job`/`pf_job_run` 全缺失**：`01` §1.2 三处与 `04` §5.3 都依赖定时任务，但没有任何任务表 | 只能写裸 `@Scheduled`：多实例各跑一遍（互相覆盖结论、告警重复）、崩溃后锁不放、运维无法回答"上次结果与失败原因" | 补 `01` §3.17 + `V9`（任务定义+租约、执行历史）。**租约保证"不重叠"不保证"只跑一次"，因此任务必须幂等**（见 README §30.3） |
| 33 | **`ac_channel_order` 从未落进迁移脚本**：`04` §4.6 写了「对 01 的增补，需 DDL 评审」并给出完整 DDL，但迁移里一直没有 | 通道补查任务直接报 `bad SQL grammar`（表不存在）——**文档里写了、脚本里没有的表，只有真去跑那条 SQL 才会暴露** | 补 `V10`（DDL 取自 `04` §4.6 原文）。**启示：凡"接口/方案文档里出现的表"，都要能被一条真实 SQL 验证到** |
| 34 | **改了已应用过的迁移脚本 → Flyway 校验和不匹配**（`V9` 改了 `status` 的 NOT NULL 默认值） | `SkeletonIT` 起不来：`Migrations have failed validation`；若不修，会变成"某些环境莫名起不来" | 开发期临时库：删历史行让脚本重放（或 `flyway repair`）；**生产永不改历史脚本，只新增迁移**。已写入 README 坑 5，并要求 CI 保留 `flyway validate` |
| 35 | **IT 用 `RENAME TABLE` 注入故障会污染共享库**：运行被中断时 `lg_shipment` 留在 `_bak`，后续 IT 全部莫名全红 | 一次中断就能让整个集成测试套件失去可信度，且原因极难联想到"上个测试改了表名" | 加 `@BeforeEach` 自愈（检测残留自动恢复）；并在注释中固化原则：**IT 共享同一个库，任何改结构的动作都可能污染其他测试** |
| 36 | **接口契约靠手抄**：给前端的接口清单原本只能从 `03-接口定义.md` 手抄，而代码里的 87 个接口随迭代持续增加 | 清单必然与代码漂移；前端拿到过期契约的代价是**联调时逐条试错**，而且后端加接口时几乎不可能记得同步 | 新增 `scripts/export-api-contract.pl`：从 Controller 注解**生成** `docs/frontend/api-contract.md`（方法/路径/权限码），并在 CI 里 `diff` 校验——**用机器保证不漂移**。本次自校验：导出 87 = 代码中 87，路径全部带 `/api/v1` |
| 37 | **前端工程从零搭建**（前置包已产出但工程不存在，且本机未装 pnpm） | 前端无法开始；若直接照外部规范写，会立刻踩 `code === 200`、`apiCode`、幂等键每次新生成三个坑 | 装 pnpm 12.11.2 + 建 `wms-platform-web/`（Vue3+TS+Vite+Element Plus）；三条硬约定**落成代码**：`code===0`、幂等键按「方法+URL+负载指纹」复用、401 跳登录/403 只提示（见 README §34） |
| 38 | **规范自检脚本与项目口径冲突**：原 `check-standard.mjs` 检的写方法名是外部项目的（`edit/deleteBatch/...`），且不检查 `apiCode`/成功码 | 直接跑会既漏检（本项目写方法名不同）又误报；前端规范形同虚设 | 改写为 `wms-platform-web/scripts/check-standard.mjs`：适配本项目写方法名 + 新增 3 条 F4 检查（禁 `code===200`、禁 `apiCode`、禁手写 `Idempotency-Key`）。**当场抓出我自己 3 个真违规 + 1 个脚本误报**，并做双向探针验证 |
| 39 | **pnpm 12 依赖构建脚本白名单键名变更**：`onlyBuiltDependencies`（旧名）写了**不生效也不报错** | `pnpm install` 永远以 `ERR_PNPM_IGNORED_BUILDS` 结束；写成数组还会报 `expected mapping start` | 键名为 **`allowBuilds`（映射形态）**，写进 `pnpm-workspace.yaml`；只放行 `esbuild`/`vue-demi`（默认拒绝是安全默认值）。已记入前端 README 坑表 |
| 40 | **前端页面只做了 4 个**（登录/库存/复核），财务、主数据、运维页面缺失 | 后端已就绪的能力没有界面入口：对账差异无法处理、结算无法下发、任务失败只能看日志 | 补齐 **12 个页面**（对账/结算/垫付/日终勾稽/兜底扣减/主数据 5 页签/设备/任务运维），路由与菜单按权限分组过滤（**空分组不显示**） |
| 41 | **前端看不到的界面口径容易被做错**：把"待接入"当差异、"跳过"当失败、"触发限额"当失败、"已扣过"当未扣 | 运维/财务会去排查不存在的故障，甚至手工重复扣款 | 每个页面在类注释里写明口径，并在界面文案上区分（README 页面清单 + 各页 §口径） |
| 42 | **路径/动词写错类型检查发现不了**（路径只是字符串），本地不联调就看不出来 | 联调时逐个试错；生产才暴露 | 新增 `pnpm check:api`：把 `src/models/*.ts` 的调用与后端契约清单逐条比对（本轮 44 处调用 / 87 个契约全部命中），并做探针验证（改错路径即被检出） |
| 43 | **R7 模板中文三元**：本轮新写的 5 个页面共 11 处违规（`row.status === '启用' ? '停用' : '启用'`） | 业务字面量散落在模板里，改文案要全项目搜；状态判断与展示混在一起 | 全部收敛为 `models` 里的映射函数（`toggleLabel`/`jobEnabledLabel`/`forbiddenLabel`/`pickableLabel`/`yesNoLabel`/`deductedLabel`）。**自检脚本再次拦住我自己** |
| 44 | **雪花 ID 序列化为 JSON 数字**：19 位整数超出 JS `Number.MAX_SAFE_INTEGER` | 前端 `JSON.parse` 丢精度（实测 `…376` → `…380`），拿错的 ID 回传 → 后端"角色不存在"；**表现是"刚创建却说找不到"，根本联想不到精度** | `JsonTimeConfig` 注册 `Long → 字符串`（`int`/`long` 计数保持数字，否则前端比较静默失效）；`03` §1.1.1 定稿 JSON 类型口径 |
| 45 | **令牌载荷被同一 JSON 口径波及**（改动引入的回归） | 载荷 `uid` 变字符串，解析端 `(Number)` 强转 → `ClassCastException` → **所有已登录用户一起掉线** | 令牌使用**专用 ObjectMapper**（内部格式不该受 HTTP 契约影响）+ 读取端宽容数字/字符串两种形态。**教训：全局 JSON 口径变更会波及复用同一 ObjectMapper 的非 HTTP 用途** |
| 46 | **审批"一事一单"两条失效路径**：① 结算下发传占位 `bizId=0` → 所有批次共用一个对象，**第二次付款永远被拒**；② 库存调整传 `bizId=null`，而 `selectByBiz` 对 null 直接返回 null → **完全没有防重**，可提交两张单**都通过即重复扣减** | ① 付款流程只能走一次（业务停摆）；② 重复扣款（资金安全） | 去掉占位值、应用层按 `bizNo` 判重，并补 **`V11` 唯一键 `uk_approval_biz_no(tenant,type,biz_no)`**。**并发下"先查再插"会双双通过，唯一键是最后防线** |
| 47 | **前端对库存接口的假设错误**：以为是"分页列表 + 可选筛选" | 类型检查发现不了（路径只是字符串），界面做出来是错的（真实契约：必填三键、返回单个三态） | 页面重构为"定位 SKU → 三态 + **两级账校验** + 库位分布 + 流水"；并在 `inventory.ts` 里写明真实契约 |
| 48 | **注释里写 `**正常**/停用` 意外闭合块注释**（`**/` 即注释结束符） | 其后的中文被当成代码解析，`vue-tsc` 报一片 "Invalid character"，**报错位置与真因相距很远** | 改写为 `**正常** 与 停用/黑名单`；已全项目排查（仅此一处）。**写 JSDoc 时避免 `**` 紧跟 `/`** |
| 49 | **架构选型的再决策**：原设计（`项目一方案.md` §554/D19、`01` 第 6 行）明确为**模块化单体**，代码侧也确实没有 Spring Cloud/Nacos/Feign；用户于 2026-10-14 选择「真拆微服务（分期）」 | 若直接改代码会**推翻一个已经验证过的选型**（单体共库强一致正是资金/库存正确性的来源）；若含糊其辞又无法推进 | 先出方案不动代码：`项目一方案-详细设计/11-微服务拆分方案（分期）.md`（现状数据、拆分边界、**一致性设计**、Nacos 配置设计、6–9 周分期、风险与"不做"清单）。**P0（多模块化）/P0.5（接 Nacos）/P1–P4 逐期确认** |
| 50 | **P0 多模块化**：原 3 个 Maven 模块（common/core/archtest）无法在**编译期**表达领域边界（只能靠 ArchUnit 运行时扫描，而 ArchUnit 自身就出现过"看不到类 = 假绿"） | 边界靠约定与扫描维持，随人员变动会腐化 | 拆为 **10 个模块**（7 个领域模块 + 装配 + common + archtest），**Maven 依赖图即服务边界**；包名不变以压小改动面。**双向探针**：md 引用未声明的 iv → 编译失败；移除 → 成功。实测 189 单测 + 60 IT + 28 步 E2E 全绿（对外行为零变化） |
| 51 | **唯一的一处循环依赖**：`AuthServiceImpl`(au) 使用 `wms-core` 的 `WmsProperties` → 拆模块后形成 `au → core → au` | Maven 直接拒绝（正是多模块化的价值：把架构里唯一的环一次暴露） | `WmsProperties` 移入 `wms-common`（`com.wms.platform.common.config`），修 4 处 import |
| 52 | **Nacos 依赖选型**：最新 `spring-cloud-alibaba-dependencies` 是 2023.0.3.3（对应 Spring Cloud 2023.0.x，仅支持 Boot 3.2/3.3），本项目是 **Boot 3.5.15** | 只用"标准做法"会引入不受支持的组合（Bean 冲突/启动失败且无官方支持路径） | 只引 **`nacos-client` 2.5.1**（与服务端 v2.5.4 同 2.5.x 线），自行实现"启动前拉配置 + 启动后注册"（~200 行）。**零版本耦合**，行为可解释；代价是失去 Spring Cloud 的自动刷新（一期不需要） |
| 53 | **配置拉取的日志全部丢失**（真实踩坑） | Nacos 配置**确实生效**（令牌有效期跟着 Nacos 变），但日志里一条 Nacos 记录都没有 → 运维无法判断配置中心是否连通 | 根因：`EnvironmentPostProcessor` 运行在日志系统初始化**之前**。改为把结论写成属性标记，由 `NacosStartupReporter` 在 `ApplicationReadyEvent` 汇报 |
| 54 | **降级时谎报"已生效"**（比 53 更危险） | Nacos 停机后日志仍报"配置中心已生效"——nacos-client **静默回退本地快照**，`getConfig` 照样返回内容；运维看不出正在跑**可能过期**的配置 | 拉取前用 Nacos 只读探针**独立探测可达性**；不可达时状态标记为「已加载（服务端不可达，值可能来自本地快照）」并按 **ERROR** 告警。**教训：报告与事实不符，比没有报告更危险** |
| 55 | **机器上已有的 wms 微服务栈与 Nacos**：`wms-gateway`/`wms-core`/`wms-job`/`wms-logistics` 容器 + 4 份 Nacos 配置（9 天前，另一套技术栈：rocketmq/redis/波次拣货） | 若直接复用/覆盖，会破坏非本次交付的资产，也无法判断哪套配置生效 | **只读勘察 + 新增不覆盖**：`docker commit` 旧容器为快照后，用**新容器** `wms-nacos-p05` 发布端口；我们自己的配置发布为 `wms-platform.yml`，旧 4 份原样保留（README §40.6 记录处置过程） |
| 56 | **网关是 WebFlux，不能依赖带 starter-web 的 wms-common** | 直接依赖会报 "Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway"，网关起不来；若不抽内核，则要么网关重复实现 Nacos/错误码，要么放弃网关 | 抽出 **`wms-kernel`**（无 Servlet）：错误码/统一响应/常量/Nacos/令牌编解码；`wms-common` 改为依赖 kernel 并保留 Servlet 侧能力。连带修正：`BizException` 需要 `spring-web`（只为 `HttpStatus`，不含 Servlet）；令牌编解码从 `au` 域移到内核（网关也要验签） |
| 57 | **身份头伪造 = 典型越权漏洞** | 网关注入 `X-User-Id/X-Tenant-Id` 后，若业务服务无条件信任，调用方直连服务端口或经网关塞同名头即可**冒充超管** | 双保险：网关侧**清除**客户端同名头并注入真实值 + 内部凭据 `X-Gateway-Token`；业务侧 `GatewayHeaderGuardFilter` 校验内部凭据（不匹配 → **403**），未配置凭据时**剥掉**身份头放行。逐条实测（直连伪造 → 403；经网关伪造 → 200） |
| 58 | **审批单号生成器会碰撞**（P1 联调抓到，500 + `Duplicate entry ... uk_approval_no`） | `"AP"+yyyyMMdd+(雪花ID % 10000)` 取模 1 万 → 按生日悖论，一天约 120 张单碰撞概率 ≈ 50%；单号不能靠概率 | 改为 `36 进制(雪花ID)`（**双射 → 全局唯一**，且随时间递增）；**并区分唯一键冲突语义**（撞单号 → 明确报"单号生成冲突"，不再误报"业务对象已存在审批记录"）；补回归测试（相差 10000 不碰撞 + 5 万 ID 零碰撞） |
| 59 | **网关模块漏绑 `repackage`** | 构建"成功"但产物只有 15KB 普通 jar，启动报 "Unable to access jarfile ...-boot.jar" | 显式绑定 `spring-boot-maven-plugin` 的 `repackage` execution（只写 `<configuration><classifier>` 不生效），与 wms-core 保持一致 |
| 60 | **拆服务时装配类归属错误会导致"接口裸奔"**：原 `WebInterceptorConfig` 同时依赖 au（鉴权）与 md（设备校验），若留在 wms-core，拆出的服务就没有任何拦截器 | 拆出去的服务该要幂等键的不要了、该鉴权的不鉴权了——**这种"拆完但不安全"比不拆更危险** | 装配按"谁能提供服务"下沉：幂等→common、鉴权→au 域、设备→md 域、资金→fns 域、调度→ops 域；每个服务用 `scanBasePackages` 显式声明范围（扫根包会把无关模块 Bean 拉进来并因缺少依赖而启动失败） |
| 61 | **显式 `@MapperScan` 会让 MyBatis-Plus 自动扫描失效** | 只加 `@MapperScan("...idempotent.mapper")` 后，**wms-core 全部 60 个 IT 因上下文起不来而失败**（其它域 Mapper 全部未注册） | 改为 `@MapperScan(basePackages="com.wms.platform", annotationClass=Mapper.class)`：既覆盖所有模块，又不会把 `InventoryService` 这类业务接口误注册为 Mapper（`@MapperScan` 不带注解过滤时会扫描**所有**接口） |
| 62 | **Nacos DataId 撞名**：网关默认拉 `wms-gateway.yml`，而同机上另一个项目的 Nacos 里恰好有同名配置（含 `spring.data.redis.host: redis`） | 网关读到了别人的配置 → 每个请求 10 秒 DNS 重试（`Failed to resolve 'redis'`），表现为"E2E 卡住" | DataId 统一加 `wms-platform-` 前缀（core/gateway/auth/masterdata 四份）；延迟从 **10.05s 恢复到 0.011s**。**教训：配置中心的 DataId 是全局命名空间，同名即覆盖，起名前必须带项目前缀** |
| 63 | **鉴权被 au 域"绑架"**：`AuthorizationInterceptor` 在 au 域且依赖 `AuthService`，导致拆出的 masterdata 必须连带扫 au 才能鉴权；权限码在 au 域导致 md/iv/fns/ops 4 个模块被迫依赖 au | 服务边界无法收敛（"拆了但互相牵扯"）；`md` 为了一个常量依赖整个权限域 | 拦截器移到 `wms-common`（只依赖共享内核的令牌编解码器——`AuthService.verify` 本就是纯验签、不查库）；`PermissionCodes` 移到 `wms-kernel`；`md`/`ops` 彻底解除对 au 的依赖。**实测**：直连伪造头 → 403、正确内部凭据 → 200、越权仍 403、28 步 E2E 全过 |
| 64 | **网关与服务各自硬编码身份头字面量**（`X-User-Id` 等各写一份） | 任何一处拼写/大小写差异都表现为"鉴权失效或一直 403"，**编译期完全发现不了** | 头名常量收敛到共享内核 `HeaderConst`（网关与服务引同一字面量）；网关注入前先按清单**清除**客户端同名头，再注入完整身份声明（含权限码/角色/时间），使服务无需验签与查库 |
| 65 | **拆出的服务没有健康端点**（`auth:8081`/`masterdata:8082` 的 `/api/v1/health` 返回 500） | 探针、负载均衡健康检查、K8s liveness/readiness 都依赖健康端点；没有它 = 实例无法被编排系统正确管理（**这类缺口不会在功能测试里暴露**，只有逐个服务验证端点才会发现） | `HealthController` 下沉到 `wms-common`（所有 Servlet 服务自动拥有，返回各自的 `application` 名）；网关另写 `/healthz`——**不能放在 `/api/**` 下**，否则会被路由接管、探针会误判 |
| 66 | **`@ConditionalOnMissingBean` 在普通 `@Configuration` 里不可靠** | 两种执行策略同时注册 → 启动报 "required a single bean, but 2 were found"。该注解只在**自动配置类**（最后执行、顺序确定）里可靠 | 改为**按配置值互斥**：`@ConditionalOnProperty(havingValue="NONE", matchIfMissing=true)` 与 `havingValue="RABBIT"`；两种模式显式写在代码里 |
| 67 | **yml 配置插错层级 → 静默失效**：wms-core 的 `spring.rabbitmq.*` 落到顶层 `server:` 块下 | YAML 靠缩进决定归属，插错**不报错**；症状是连默认端口 5672 被拒（`Connection refused`），而另一个顺序不同的服务却正常——极易误判为"网络/密码问题" | 按 `spring:` 块的**真实边界**（下一个顶格键）插入并校验；同批修掉 3 个文件里误产生的重复顶层 `spring:` 键 |
| 68 | **所有服务共享一个队列 → 非执行方抢走消息并送进死信** | 消费者 Bean 放在 `wms-common` 且只按 `mode=RABBIT` 装配 ⇒ 每个带 AMQP 的服务都进竞争组；`wms-auth` 抢到结算事件却无对应执行器 → 异常 → 重试耗尽 → DLQ。**症状是"某类审批随机不生效"，取决于谁先抢到消息** | 新增 `wms.messaging.consumer-enabled`（只有承担执行职责的服务打开）+ **启动自检**：开了消费却无任何 `ApprovalActionHandler` → 直接拒绝启动。根治方向：每域订阅自己的队列与路由键（P3 拆 iv/fns 时落地） |
| 69 | **SQL 直插 XXL-JOB 任务后不触发，且没有任何报错** | `xxl_job_info.trigger_status` 默认 **0 = 停止**，调度只扫 `trigger_status = 1`；通过管理台/API 建任务会自动置为运行，**直插 SQL 不会** | 直插后显式 `trigger_status=1`；并把「元数据表的小列宽/默认值语义」列为接入第三方调度中心的前置检查项（同批还有 `xxl_job_group.title varchar(12)` 导致中文标题报错） |
| 70 | **配置文件被静默破坏**：用 shell 双引号包 `node -e "..."` 写 yml，`${...}` 被 shell 内插、`\n` 被吞，多处配置压成一行 | 启动时才报 `mapping values are not allowed here`（YAML 缩进/转义错误不在编辑期暴露），与 §44 的「插错层级」同源 | 配置文件一律用编辑工具写（不经 shell 展开）；写完后做「顶层键唯一 + 可解析」校验 |
| 71 | **所有服务共享一个队列 → 非执行方抢走消息并送进死信**（P2.1 实测，P3.1 根治） | 消费者 Bean 放在公共层且只按模式装配 ⇒ 每个带 AMQP 的服务都进竞争组；`wms-auth` 抢到结算事件却无对应执行器 → 重试耗尽 → DLQ。**症状是"某类审批随机不生效"，取决于谁先抢到消息** | 改为**队列归属跟随模块归属**：每域一个队列 + 每域 DLQ，消费者代码写在该域模块内（`IvApprovalEventConsumer`/`FnsApprovalEventConsumer`），路由键由 `ApprovalBizType.domain` 推导并随事件落库（V12 加 `routing_key`）。**实测**：4 个服务都开 RABBIT 模式，但 iv/fns 队列各只有 core 贡献的 1 个消费者；删除 `consumer-enabled` 开关——配错的可能性被结构性消除 |
| 72 | **最终一致的链路被"立即断言"误判为缺陷**：E2E 27/28，批次状态断言失败，但 1 秒后库里已是「已下发」 | 事件化执行是最终一致（亚秒级），测试按强一致假设立即断言 → 竞态。**若把竞态当缺陷去"修业务代码"，会引入真正的错误** | E2E 增加 `waitFor()` 轮询助手（15s 上限）用于两条事件相关断言；两种模式都兼容（NONE 模式首次即成立）。**规范：断言最终一致行为必须轮询；轮询也等不到才是真缺陷** |
| 73 | **Redis 端口默认 6379，而本项目约定 6380** | 6380 本就是为避让同机另一个项目的 6379 而选；core 的默认值没跟着改 → 开启缓存后键会写进**别人项目的 Redis 实例**（与「DataId 撞名读到别人配置」同源的环境风险） | core/auth/masterdata 三处默认统一为 6380，并给 auth/masterdata 补上 Redis 配置块 |
| 74 | **缓存失效若在事务内执行，会被并发回填成旧值** | 事务未提交时另一个请求读到旧数据并写回缓存 → "失效了又立刻被填回旧值"，缓存留下错误结论且不会自愈 | 失效注册到 `TransactionSynchronization#afterCommit`（数据真正可见之后再清），并用 `MasterDataCacheIT` 把"变更即失效"固化为回归测试 |
| 75 | **计费规则"静默按 0 元"是最危险的失败方式** | 无价目表或区域/重量未覆盖时返回 0，会让系统**持续漏计费且报表上看不出来**（没有异常、没有告警，只有长期少收钱） | 引擎显式区分三种失败并给错误码：无价目表 `40006`、区域未覆盖/重量未覆盖 `40007`；另有"草稿不计费、已启用版本拒绝改价、同时只有一个启用版本"三条约束；单测对每条失败路径单独断言 |
| 76 | **重量区间用闭区间会让相邻档在边界重叠** | "首重≤1kg"与"1–3kg"在 1kg 处同时命中，结果取决于代码比较顺序 → 计费争议且难以复现 | 统一为**半开区间 [from, to)**，并在 DB 上加 `CHECK (weight_to > weight_from)` 兜底；单测专门断言"恰好 3.0000kg 走 [3,∞) 而非 [0,3)" |
| 77 | **traceId 只在请求线程里，跨 MQ 后就断了** | 发件箱补投由**定时任务线程**执行、消费由 **AMQP 容器线程**执行，MDC 里没有原始请求的 traceId → "这笔审批的补投/消费属于哪次请求"只能靠时间戳猜 | `pf_outbox` 增 `trace_id`（V14，写入时记录）；发布时放进消息头 `X-Trace-Id`；消费时恢复到 MDC 并在结束后清理。**实测**：复核请求 traceId 与消费线程日志中的 traceId 完全一致 |
| 78 | **网关访问日志在 DEBUG 级 → 全链路第一环搜不到** | 网关是入口，其访问日志默认不可见；实测"同一 traceId 在网关日志出现 0 次"，链路首环成空白 | 网关访问日志提到 INFO（每请求一行，含方法/路径/耗时/状态/traceId）——这正是网关日志的常规做法 |
| 79 | **不存在的 URL 返回「系统错误 19999」** | Spring Boot 3 的 404（`NoResourceFoundException`）未单独处理，落到兜底分支被当作系统错误：调用方以为服务崩了、错误率告警被误导 | 单独处理 → **HTTP 404 + 10001「接口不存在，请检查请求路径」**（P4 验证时实测发现） |
| 80 | **对象键若以单号命名，同一单第二次上传会覆盖第一次的证据** | 回单是可补拍/多次上传的（白天、夜间、破损补拍），用单号做文件名 → 第二次直接覆盖第一次（对象存储对同键就是覆盖语义），举证材料静默丢失 | 对象键加入唯一片段：`proofs/{tenant}/{yyyyMMdd}/{单号}/{用途}-{唯一片段}.{ext}`；单测专门断言"同单两次上传键不同" |
| 81 | **上传接口不做类型白名单 = 任意文件上传漏洞** | 只校验大小不校验类型时，可上传 .jsp/.exe 等；一旦目录可解析就变成 RCE 入口 | 扩展名白名单（jpg/jpeg/png/webp/pdf）+ 20MB 上限；本地实现另加**目录穿越防护**（对象键越出根目录即拒绝）——两条都用测试锁住 |
| 82 | **本地存储模式返回预签名 URL 会让前端拿到 404 地址** | 本地磁盘没有"直接对外发 URL"的能力，若接口照常返回一个拼接的 URL，前端点开必 404，且原因难查 | `FileStoragePort.supportsPresignedUrl()` 显式暴露能力差异：本地模式返回 `downloadUrl=null` + 明确提示"改走应用内下载接口"；单测断言本地模式的该行为 |
| 83 | **JVM 服务不配 startupProbe 会被 liveness 杀掉 → 重启循环** | JVM 冷启动数十秒；liveness 从容器一启动就开始计时，早期必然失败 → K8s 反复重启，表现为"服务永远起不来"（在本地直接 java -jar 却能跑起来，极易误判为"镜像有问题"） | 所有服务加 `startupProbe`（failureThreshold 30 × 5s = 150s），启动成功后才交给 liveness；readiness 用 Actuator/业务健康（与 P4 监控同源） |
| 84 | **滚动更新不配优雅停机 → 必然出现一批 502** | 容器收到 SIGTERM 即退出时，K8s 可能还没把它从 Service Endpoints 摘除，这几百毫秒内的请求全打到已死进程 | `terminationGracePeriodSeconds: 30` + PreStop `sleep 10`（先摘流量再退出）+ `maxUnavailable: 0`（先起新实例再停旧实例） |
| 85 | **读写分离若"默认读走从库"会造成资金差错与"提交后查不到"** | 复制是异步的（毫秒~秒级）：写后立即读会读到旧值（用户以为提交失败→重复提交）；更严重的是用从库读到的库存/冻结额做**业务判断** → 基于过期数据做决策 | 反转为**安全默认**：不标注一律走主库，只有显式 `@ReadOnly`（仅 7 处浏览类查询）才走从库；`@ReadOnly` 的类注释里写明"绝不应标注"的场景清单 |
| 86 | **路由切面若不先于事务切面，切换数据源完全无效** | 事务一开启，连接已绑定到线程；此后改 ThreadLocal 路由不会被使用 → "看起来配好了、其实读的还是主库"，且**没有任何报错** | 切面 `@Order(HIGHEST_PRECEDENCE + 10)`（事务切面默认 `LOWEST_PRECEDENCE`）→ 路由先于事务生效；IT 用"停复制+写主库"实验证明真的分流了 |
| 87 | **MySQL 容器 `--super-read-only` 写在启动参数会阻断自身初始化** | 容器 entrypoint 要初始化 datadir，此时已被置为超级只读 → 初始化失败、容器 Exit 1（实测） | 从库容器先正常启动完成初始化，再 `SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;` 并建立复制 |
| 88 | **两个类各自用 `@ConditionalOnProperty` 判断同一开关，改前缀时漏改一个 → 切面从未注册** | 配置类改成 `spring.datasource.replica` 与 yml 对齐，切面仍是 `wms.datasource.replica` → 路由数据源装好了（日志里主/从 URL 都在，看起来正常），但 `@ReadOnly` 通知不执行 → **所有读静默落主库且无任何报错**。定位靠给切面加 `@PostConstruct` 装配自检日志 | 切面改为**始终注册**（未配从库时路由键指向主库、`@ReadOnly` 安全退化）→ 从设计上消除"两个类条件必须一致"的隐患；并保留装配日志作为永久自检 |
| 89 | **`@ConditionalOnProperty` 把"属性存在但为空串"判为满足条件** | yml 写 `${DB_REPLICA_URL:}` 默认空串 → 条件满足 → 用**空 URL** 建从库池 → 未配从库的环境（CI/其它 IT）里所有 `@ReadOnly` 查询连接失败（`jdbcUrl is required with driverClassName`）。**由全量测试抓到** | 去掉条件判断，改为**按值判空**（`StringUtils.hasText`）：空串与未配置同等对待，语义唯一 |
| 90 | **IT 用旧命名空间注册属性 → 从库池从未创建，断言失败** | IT 注册 `wms.datasource.replica.url` 而配置读 `spring.datasource.replica.url` → 静默走"未配从库"分支 → 分流实验永远读到主库（看起来像功能缺陷，实为测试配置错） | IT 统一为 `spring.datasource.replica.*`；**并从日志核对 `wms-replica` 池是否真的创建**（本次就是靠这一条定位的） |
| 91 | **复制服务骨架时残留旧标识符 → 新服务所有接口 404（不报错）** | `@SpringBootApplication(scanBasePackages)` 里带着上一个服务的模块包（`modules.md`）→ 自己模块的 Controller 从未被扫描，启动全绿但接口全 404 | P3.4 踩坑记录 + 检查清单（README §53.5）：`artifactId`/`mainClass`/`scanBasePackages`/yml 四处名称/中间件环境变量，必须逐项改；新服务先过"启动+健康+一条自己模块接口 200"三件套 |
| 92 | **pom 的 `<mainClass>` 复制旧值 → jar 启动即 ClassNotFoundException** | repackage 出的可执行包指向不存在的类（旧的 `MasterDataApplication`），表现为"构建成功但一跑就崩" | 同上清单第 ② 项；已在 README §53.5 固化 |
| 93 | **"服务名在 Nacos 列表里"不等于"实例可被发现"** | `wms-fns` 出现在 service list，但 `instance/list` 无健康实例 → 网关 `lb://wms-fns` 返回 **503**；只看服务列表会误判为"注册成功" | 拆分验证必须查 **instance/list**（或直接看网关能否路由成功）；本轮因未收敛而**回退网关路由**，保证环境不挂——并如实登记"未完成"而不是留半拆状态 |
| 94 | **"启动成功 + 健康 200" 可能是假绿：Bean 一个都没加载** | `scanBasePackages` 写成别的模块包 → 自己模块的 Bean 完全没加载，因此"没报缺依赖"；等把扫描包改对，立刻暴露缺 `InventoryService` → 上下文启动失败。**差点据此以为服务已就绪** | 新拆服务必须过"**启动 + 健康 + 一条自己模块的接口 200**"三件套（README §53.5/§53.6）；"启动成功"单独不构成证据 |
| 95 | **拆服务的正确顺序：出边先 HTTP 化，再启动服务** | 原计划把 `fns→iv`、`fns→au` 的 HTTP 化放在最后；实际它们是 fns **能否启动**的前置（缺 Bean 直接启动失败）→ 顺序必须是"先改出边 → 起服务 → 迁流量 → 再从单体移除代码" | README §53.7 固化为拆分顺序；本次因此**未收敛而回退**，而不是留一个起不来的服务 + 指向它的路由 |
| 96 | **服务间调用会撞上"接口受保护 + 必须带幂等键"** | 服务间没有用户令牌：被调接口的 `@RequiresPermission` 会 401、写接口缺 `Idempotency-Key` 会 400 —— 这在"进程内调用"时根本不存在，拆分后才出现 | 复用本仓库已有机制：带 `X-Gateway-Token` + 身份头（`GatewayHeaderGuardFilter` 信任并注入 `TenantContext`）+ 由"业务单号+载荷哈希"派生 `Idempotency-Key`；失败必须让业务失败（不许静默跳过） |
| 97 | **服务间调用缺"服务发现客户端" → `Service Instance cannot be null, serviceId: wms-core`** | 此前**没有任何服务去调别的服务**（只有网关路由），所以"服务 pom 不声明服务发现"这个缺口一直没暴露；拆分后第一次出边调用就 500 | 给"会调用别的服务"的模块补服务发现依赖；并把"出边调用三关"写进拆分检查清单：① Bean 齐（否则起不来）② 有 discovery client（否则解析不到实例）③ 带内部令牌 + 幂等键（否则 401/400） |
| 98 | **能启动 ≠ 能把请求发出去** | fns 启动成功、健康 200、自己的接口 200，但一次出边调用就 500——"启动成功"只证明依赖注入齐了 | 拆服务的验收必须是**一条真实的跨服务调用成功**（判据：调用方日志有发起记录 + 被调方日志有收到记录 + 业务返回码 0），而不是"服务起来了" |
| 99 | **跨服务调用未设超时：RestClient 默认无限等待** | 远端一慢，调用方请求线程被**永久占用**，且（若在事务内）还持着本地事务与行锁不放 → 后续请求排队等锁、成片失败（实测：一次卡住后每次重试都 `Lock wait timeout exceeded`） | 统一在 `RemoteClientConfig` 给跨服务 RestClient 设 connect 2s / read 10s；把"出边调用必须设超时"列为检查清单第 ④ 关 |
| 100 | **在本地事务内做跨服务调用（分布式反模式）** | fns 对账在事务里写本地表（持锁）→ 未提交就调 core 的 HTTP → core 的查询等锁、fns 等 core → **互相等待**；被卡死的请求把锁一直占着，导致后续所有重试连锁失败 | 检查清单第 ⑤ 关：**先提交本地事务，再做远程调用**（或把远程读移到事务外），远程结果写回另一个事务；禁止"持锁等远端" |
| 101 | **在"被自己搞坏的环境"里继续归因，会把环境问题误判成代码缺陷** | 前几轮反复失败的请求把 core 拖垮（线程/连接被占满），于是同一接口"经服务调用 10s 超时"、甚至 **curl 也 20s 无响应**；一度归因到 HTTP 客户端、负载均衡、事务持锁（全错） | 先重启/清理脏状态（含遗留 DB 事务）再做一轮对照实验；本轮靠"curl 也挂"才识别出是环境问题。**归因前先确认被测环境是健康的** |
| 102 | **服务间调用只带身份、不带权限 → 403（`stock:invariant:check`）** | 内部令牌路径认可身份（否则 401），但 `@RequiresPermission` 仍要校验权限，而服务间调用没有"该身份的权限集" → 403；拆服务后第一次真实业务调用就撞上 | 按来源分两类：**用户触发**转发原 `Authorization` 令牌（权限来自用户、审计归到人）；**后台任务**用独立服务账号 + 最小权限。**绝不**给内部路径开"跳过权限"的口子 |
| 103 | **服务间调用的"身份"要按来源分两类，且权限必须能传导** | 只带内部令牌 + 身份头时：身份被认可、**权限集为空** → 被 `@RequiresPermission` 403（实测需要 `stock:invariant:check`）。若简单粗暴地"内部路径跳过权限"，等于给内部调用开万能钥匙 | `RemoteCallSupport` 按来源选凭据：**用户触发 → 转发原 `Authorization` 令牌**（权限随用户、审计归于人）；**后台任务 → 独立服务账号令牌**（最小权限）；都没配则**明确 403**（安全优先，不静默放行）。实测：`code=0`，四层含 INVENTORY（即跨服务调用成功） |
| 104 | **测试循环把服务拖入脏状态 → 误判为代码缺陷（同一坑第三次）** | 反复调用会失败的接口（对账/下发等）会留下挂起请求与未提交事务；被调服务随后对**直连**请求长时间无响应（连 curl 都挂），客户端表现为 `Request cancelled`。本轮因此先后错怪：HTTP 客户端实现、负载均衡、事务持锁、请求体序列化 | 固化纪律：**每轮归因前①清理遗留 DB 事务②重启被调服务**；判据：**"经网关 200 但直连挂住" ⇒ 先查目标实例状态**，而不是先改客户端 |
| 105 | **服务间调用必须让"身份与权限"可传导（最终结论）** | 只带内部令牌+身份头 → 权限集为空 → 403；转发用户令牌后 fns→core、fns→auth 全部 `code=0` | `RemoteCallSupport` 按来源选凭据（用户触发转发 Authorization；后台任务用服务账号；都没配则明确 403）。**实测**：网关 4 条路由 + fns 生产模式 → E2E 28/28 |
| 106 | **"主代码编译通过"≠"拆干净"：测试代码仍引用被拆走的类** | 从 `wms-core` 移除 `wms-mod-fns` 后主代码正常，但 `wms-core/src/test` 的财务 IT（`DailyReconciliationIT` 等）仍 import fns 类 → **测试编译失败**；而且 `pom.xml` **注释**里出现过模块名会骗过 grep 判断（两次误判依赖是否已移除） | 模块收敛必须跑 `mvn -DskipTests install`（**会编译测试**）而不是 `-Dmaven.test.skip`；判断依赖用 `grep -n artifactId` 或直接打印文件，**不要 grep 模块名**（注释会命中）。前置动作：先把对应 IT 与测试基础设施迁到目标模块 |
| 107 | **把"后台任务改服务调用"与"服务账号签发"绑在一起，否则是换个方式坏** | 后台任务没有用户请求上下文 → 无用户令牌可转发 → 未配服务账号时被调方按权限校验**必然 403**；这与"用户在页面上点一下"的调用路径完全不同 | 顺序：先做**服务令牌签发/轮换**（内部凭据换短时令牌，`svc-*` 最小权限），再把任务改成 HTTP；客户端 `FnsJobClient` 已是成品，只差接线 |
| 108 | **IT"静默不跑"：模块缺少 failsafe 配置时 `verify` 照样 BUILD SUCCESS** | `DailyReconciliationIT` 等 4 个 IT 迁到 `wms-fns` 后，第一轮 `verify` **显示成功**，实际**只跑了 32 个单测、IT 一个都没执行**（failsafe 配置只写在 `wms-core/pom.xml`）。第二层问题：IT 在 `com.wms.platform.integration` 包、应用类在 `com.wms.platform.fns` → `Unable to find a @SpringBootConfiguration` | 跨模块迁移测试时必须**核对"IT 真的跑了"**（看 failsafe 的 `Tests run` 行，不看 BUILD SUCCESS）；IT 显式声明 `@SpringBootTest(classes = XxxApplication.class)`；failsafe 配置随 IT 一起迁移 |
| 109 | **拆服务后 IT 内的跨服务调用必须打桩** | `DailyReconciliationIT` 在 fns 上下文里跑对账，现在会真去调 core → 测试环境无注册中心 → `Service Instance cannot be null, serviceId: wms-core`（4 个用例失败） | IT 只验证**本服务逻辑**，跨服务客户端用 `@MockitoBean`/`@TestConfiguration` 桩掉；远端契约由对端 IT 或契约测试保证。**不要**为了跑 IT 而在测试里起注册中心 |
| 110 | **模块回退造成"过渡态"：同一段逻辑同时存在进程内与跨服务两条路径，测试环境只满足前者** | 为解测试编译错误把 `wms-mod-fns` 回退进 core 后，core 里的**进程内**对账也会走 `InventoryInvariantClient`（HTTP）→ core 测试上下文无服务发现 → `ScheduledJobIT` 任务执行"失败"（可复现，expect 成功 got 失败） | 过渡期要么给相关 IT 加桩（`@MockitoBean` 跨服务客户端），要么尽快完成"移除模块 + 任务改 HTTP + 服务令牌"；**不要把回退当成完成的替代品** |
| 111 | **重构时把"远程调用"放在"入参校验"之前，改变了错误语义** | `DailyReconciliationServiceImpl.run` 为提高"远程调用在事务外"把远程读取放到了方法最前 → `run(null,null)` 不再报"业务日期不能为空"，而是先去打远端（由 IT `inputValidation` 抓出） | 顺序铁律：**入参校验 → 本地准备 → 远程调用 → 事务写入**。远程调用再"早"也不能早于校验 |
| 112 | **把跨服务客户端放 `infrastructure` 并被控制器直接注入 → 违反"api 不得依赖 infrastructure"** | 业务与 IT 全绿，**唯独 ArchUnit 守卫 FAILURE（3 处）**；若只看测试结果会以为可以交付 | 客户端移到 `modules.<域>.app.remote`（`api → app` 合法）；**"业务测试全绿"≠"可交付"**，守卫/契约类横切质量必须同跑；分层规则的真正价值是拦住"api 绑死具体实现" |
| 113 | **后台任务的跨服务调用缺"服务自己的身份"**（P3.4 收尾的设计结论） | 用户触发可转发用户令牌 ✓；但后台任务没有请求上下文 → 退化路径权限集为空 → 403。**不能**用"内部路径跳过权限校验"糊过去（那是万能钥匙） | 三段式：① auth 新增 `POST /api/v1/auth/service-token`（`@PublicApi` + 自行校验内部凭据，签发配置的服务账号令牌，TTL 30 分钟）② `common.ServiceTokenProvider`（缓存 + 单飞 + 取不到就明确失败）③ `RemoteCallSupport` 无上下文时用它；账号权限走 RBAC（最小权限、可审计、可撤） |
| 114 | **授权接口"报成功但没生效"（静默无效）** | `POST /auth/users/{id}/roles {"roleCodes":["SVC_OPS"]}` 返回 `code=0`，但用户 `roleCodes` 仍为空 → 实际未授权；表现为后续每笔调用 403「需要权限 …」，而运维以为已授权 | 授权接口必须**校验解析结果**：解析到的角色数 ≠ 请求的 code 数时**抛 10001 并列出未找到的 code**；补 IT 用例锁住"挂不存在的角色必须报错"。**原则：写接口不允许"声称成功但没做事"** |
| 115 | **"整批替换"接口漏传字段 = 静默撤销全部** | `GrantRo.codes` 无 `@NotNull`：用错字段名（`roleCodes`）调用 → `codes=null` → 当成空集合 → **清空该主体全部授权**，且返回 `code=0`。库里一行未写，排查时极易误判为"授权没生效" | ① `codes` 加 `@NotNull`（保留"显式空数组=撤销全部"）② 控制器加 `@Valid`——否则所有校验注解都是摆设（**实测**：加 `@NotNull` 后仍返回 200，正是缺 `@Valid` 的证据）。原则：**入口必须能拒绝非法输入** |
| 116 | **校验注解没有 `@Valid` 撑着 = 摆设** | `GrantRo` 上原本就有 `@NotNull warehouseIds`，但控制器用 `@RequestBody` 而非 `@Valid @RequestBody` → 从不生效 | 所有带校验注解的 Ro 在控制器入口都必须 `@Valid`；建议加一条 ArchUnit 规则：**含 `jakarta.validation` 注解的 Ro，其控制器参数必须带 `@Valid`**（可自动拦住这一类问题） |
| 117 | **一个 Ro 被多个语义不同的接口共用，且其中一个字段无条件 `@NotNull`** | `GrantRo(codes, warehouseIds)` 同时服务"角色权限""用户角色""用户仓库"三个接口；`warehouseIds` 的 `@NotNull`（在仓库授权里是对的）导致**另两个接口必须塞无关字段**才能过校验，否则 10001 | **拆 Ro**：`GrantCodesRo`（码集合）与 `GrantWarehousesRo`（仓库集合）。一个 Ro 承载多种语义 = 把某接口的必填项泄漏成所有接口的必填项 |
| 118 | **"校验注解 + `@Valid`"缺一不可（实证）** | 只加 `@NotNull` 时漏传字段仍返回 `code=0`（静默撤销全部）；补 `@Valid` 后立即变成 10001 拒绝。**同一份代码，加不加 `@Valid` 是"安全"与"危险"的区别** | 所有带校验注解的 Ro，控制器入口必须 `@Valid`；建议加 ArchUnit 规则自动拦住漏写（含校验注解的 Ro → 参数必须有 `@Valid`） |
| 119 | **"整批替换"未处理逻辑删除残留 → 撞唯一键 → 19999** | `grantRolePermissions` 先逻辑删旧行再插入，但唯一键 `uk_au_role_perm` **不含 `deleted`** → 重插同一对 (角色,权限) 与残留行冲突 → `DuplicateKeyException` → 整批回滚（表现为"补权限永远不生效 + 系统错误"）。`assignUserWarehouses` 同样缺处理；**`assignUserRoles` 早已正确处理**（插前 `countAny`+`deleteAny` 物理清理） | 照抄兄弟方法：插前物理清理残留；**更彻底**：唯一键纳入 `deleted`（或墓碑值方案），否则每加一个"整批替换"接口都会再踩一次。另：撞唯一键应返回 **409/明确码**，不是 `19999 系统错误`（别把可预期冲突伪装成系统故障） |
| 120 | **`@TableLogic` 让"清理残留"也变成逻辑删除，且让 count 只看未删行** | 想照抄"插前 countAny+deleteAny 清残留"来修"整批替换撞唯一键"，结果**两次都无效**：① `countAny` 用 `selectCount` + `@TableLogic` → 只统计未删行，而逻辑删已把行标成 deleted=1 → 恒返回 0；② 即使把 `deleteAny` 改成原生 `@Delete`，**清理动作在逻辑删之后**，顺序决定了它看不到残留 | **关系表"整批替换"必须：物理删除（原生 `@Delete`）+ 删除在该批插入之前**；不要用"逐条判断残留"的写法。根治建议：唯一键纳入 `deleted`（或墓碑值）。**实测证据**：改成整批物理清空后 `code=0`、行数 2→3 |
| 121 | **⭐ 在进程运行期间覆盖它的 jar → 诡异类加载失败（NoClassDefFoundError / Request cancelled / 请求挂住）** | JVM 懒加载类：`mvn install` 覆盖了正在运行服务使用的 jar 后，**尚未加载的类在首次使用时找不到** → 报 `NoClassDefFoundError: org/springframework/web/client/IntrospectingClientHttpResponse`，或表现为 `Request cancelled`、请求挂住。**看起来像网络/LB/远端故障，其实是自己换了 jar** | **改代码后必须重启所有使用该产物的进程**；只想验证某服务时**不要**顺手 `mvn install` 整个 reactor（会覆盖其它在跑服务的 jar）；长期运行的服务用**独立目录/带时间戳的产物**启动。本轮多次误判（HTTP 客户端、负载均衡、事务、远端脏状态）至少一部分源于此 |
| 122 | **跨服务依赖抽"端口 + 测试替身 Bean"比 `@MockitoBean` 可靠** | 任务改成 HTTP 调财务后，IT 里用 `@MockitoBean` **没拦到**调用（任务真的去换服务令牌 → 报"未配置内部凭据"）；换成**接口 `FnsTaskPort` + `@TestConfiguration` 的 `@Primary` 替身 Bean** 后立即转绿 | 拆分后让调用方**依赖端口接口**，测试注入**显式替身 Bean**（可配一个 `failNextXxx` 静态开关注入远端故障）；不要把"能不能拦到调用"赌在 Mockito 的注入/重置时序上 |
| 123 | **`mvn -pl <模块>` 不带 `-am` 会用本地仓库的旧制品** | 刚给 `wms-mod-ops` 加了新接口，`mvn -pl wms-core verify` 报 `symbol: class FnsTaskPort`（找不到）——因为 ops 从本地仓库解析，用的是上次 `install` 的旧 jar | 验证单模块改动必须带 **`-am`**（或先 `mvn install` 依赖模块）；这与"运行期覆盖 jar"是同一类"新旧产物混用"问题 |
---

## 八、现有代码偏差登记（现状，勿模仿）

| 位置 | 偏差 | 处理 |
|------|------|------|
| `modules.health.api.HealthController#check` | 骨架自检接口，非真实业务接口 | W3 实现真实接口后删除（已在代码注释标明） |
| `HealthController` | 曾用 `Map.of` 收发请求/响应体 | **已按 B2/B3 整改为 `HealthCheckRo` + `HealthVo`/`HealthCheckVo`** |
| `modules.au.api.support.AuthorizationInterceptor` | （已收口，2026-10-14 W4）权限校验覆盖全部接口：58/58 接口均有 `@RequiresPermission` 或 `@PublicApi`，ArchUnit 白名单清空 | 过渡期策略已结束。规则收紧后用 `ProbeController` 探针做双向验证（在场必红/删除必绿），证明不是"没匹配到任何方法"的假绿（见 README §20.1/§20.2） |
| `WebInterceptorConfig.AUTH_PRE_SESSION_EXEMPT_PATHS` | 登录/刷新/`me` 不要求 `Idempotency-Key` | 会话建立前无法生成业务幂等键；三者无业务副作用。管理类写接口照旧要求（见 §七 第 18 行） |
| HTTP 401/403 口径 | `03` 只定义了业务码 `40300`，未定义 HTTP 状态码 | 本项目口径：未认证 → 401 + 40300；已认证缺权限 → 403 + 40300。**待补进 `03` §1.3** |
| `modules.health` | 该域不是业务域（02 的 M1–M12 之外） | 仅保留 `/health` 供探针；其余内容 W3 迁出或删除 |
| `IdempotentRecord` → `IdempotentPo` | 曾用 `new + setXxx()` 构造、散落在 `idempotent` 包、缺 `@TableLogic` | **已按 B1/B3/B4 整改**：静态工厂 + `idempotent/po` 与 `idempotent/mapper` 分包 + 显式 `@TableLogic` |
| `SnowflakeIdGenerator` / `PiiCipher` / `RequestHasher` | 曾抛 `IllegalArgumentException`/`IllegalStateException` | **已按 B5 整改为 `BizException`** |
| `DbIdempotentService` | 曾在 Service 中使用 `LambdaQueryWrapper` | **已按 B1 整改为 Mapper `default` 方法** |
| `ApiResponse.TRACE_ID_KEY` / `TraceIdFilter.TRACE_HEADER` / `IdempotencyInterceptor.MAX_KEY_LENGTH` | 跨类共享常量散落在各自类内 | **已按 B6 收敛到 `HeaderConst` / `TraceConst`** |
| `PlatformInfrastructureConfig` | 曾用字段 `@Value` 注入配置 | **已按 B9 整改为 `WmsProperties`（`@ConfigurationProperties`）+ 构造注入** |
| `modules.pda.infrastructure.mapper.SyncBizStatusMapper` | **仅剩的跨域缝隙**：只读查询 `out_order` / `in_asn` 状态（W1 时 `outbound`/`inbound` 尚无 app 层） | **W7 出库/入库核心落地后删除**，改调各域服务。同一批的另 3 个（`SyncStockMapper` / `SyncMovementMapper` / `SyncMovementPo`）已于 2026-10-14 清理，见 §七 第 14 行 |
| `modules.pda.app.impl.PdaSyncServiceImpl#resolveShipLocation` | 装车出库的库位靠**推导**（`03` 的 LOAD 报文不带库位，而 `01` §4 不变量 2 要求出库必须落到具体库位） | **W7 改为取出库单的分配记录**；当前多条候选时转人工冲突（**不猜**，猜错会让另一个库位凭空多出实物） |
| 单测中的 `List.of` / `Set.of` | 测试代码使用不可变工厂 | 按 §五 适配条**保留**：测试不参与运行期契约，且 JUnit/AssertJ 生态默认用法 |

## 九、W6 出库域落地时新增的结论（2026-10-17）

> 按 AGENTS.md §3 的纪律：**发现映射表未覆盖的冲突时，先在这里补结论，再写代码**。
> 以下每条都是"设计文档没写死、但实现必须选一边"的地方。

| 编号 | 议题 | 结论 | 理由 |
|------|------|------|------|
| B17 | 状态机的物理位置 | 放在 `modules.<域>.domain.statemachine`，**允许它依赖本域 Po**（只此一个包豁免） | ArchUnit 规则「状态字段只能由状态机修改」把豁免包硬编码为该包；反过来把状态机挪去 app/infrastructure 会让规则直接红。这是既有规则的本意，不是新开的口子 |
| B18 | 跨域读数据 | 跨域只走对方 `app` 接口；**本域需要但对方没提供的读能力，优先给对方加一个语义方法**（本例：`iv.reservedRemaining`），而不是在本域直连对方表 | `PdaSyncServiceImpl#resolveShipLocation` 的跨域缝隙就是这么长出来的（见 §八）；加方法比加缝隙便宜 |
| B19 | 状态迁移落库方式 | 一律用 **条件更新**（`WHERE status = 迁移前`），不使用 `updateById` + 乐观锁 | 本仓库**未注册** `MybatisPlusInterceptor`（`@Version` 会静默失效），`updateById` 在并发下会丢失更新（"点了取消但单子还是出库了"） |
| B20 | 分页实现 | 手写 `LIMIT/OFFSET`（Mapper `default` 方法内），不用 `selectPage` | 同上：分页拦截器未注册，`selectPage` 会返回全表且**不报错** |
| B21 | 幂等键的组织 | `pf_idempotent` 键 = `动作前缀 + ':' + Idempotency-Key 头值`；状态流水键 = `<bizId>+<EVENT>` | 同一个头值若被跨动作复用（先取消后装车），不加前缀会让第二次动作被判成重复而**静默不执行** |
| B22 | 建单类接口的"试算" | 预估价由调用方传入，**不在业务事务内远程调价目表** | `53.12` 已证伪事务内远程调用；试算与建单拆成两步契约（先调 `/pricing/estimate`，再带价建单） |
| B23 | 权限码登记 | 新增权限码**必须同时加入** `PermissionCodes.ALL` | 否则 `of()` 会拒绝授予（症状：配了权限仍 403，且配置界面显示成功）。本轮补齐了此前遗漏的 3 个（`PRICING_ESTIMATE`、`LOGISTICS_PROOF_*`） |
| B24 | 生成物的确定性 | 生成式文档（`docs/frontend/api-contract.md`）必须**按稳定键排序**后输出 | 顺序随扫描顺序变化会让 CI diff 红一大片，真实漂移反而被淹没 |

