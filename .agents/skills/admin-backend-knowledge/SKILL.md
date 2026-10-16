---
name: admin-backend-knowledge
description: singleProject 后端项目权威知识库（规范 + 项目知识）：多模块 Maven 结构、代码地图、关键机制（登录/JWT/鉴权/授权可见性/缓存刷新/接口同步/文件上传）、领域模型与术语、RBAC 授权语义、Controller/Service/Mapper 分层、POJO 与静态工厂、MapStruct、异常、常量枚举、注释、jetCache/Redisson 缓存一致性、数据库表结构与字段约定、构建与自检清单。当修改或新增 application、common-pojo、common-mapper、common-service 下代码（Po/Dto/Ro/Vo、Mapper、Service、Controller、MapStruct、配置、过滤器、定时任务、缓存），处理 RBAC 权限与接口鉴权，给表加改字段，调整缓存刷新失效，执行 mvn 构建，或回答本项目「功能怎么跑」「代码在哪」「架构/数据流/表结构/规范」时，先读本技能，再按需查 reference/ 专题文件。
---

# 后端系统知识库

singleProject 后端项目的权威知识库。`AGENTS.md` 只保留交互约定与技能加载入口。

**本文件 = 强制规则与判断标准**；**专题细节（清单、模板、表结构、链路、参数）= `reference/` 下的专题文件**。两者不重复：本文件不罗列清单，专题文件不重复规则。

## 何时使用

- 必须读本文件：修改或新增 `application/`、`common-pojo`、`common-mapper`、`common-service` 下任何代码；新增/调整 Po、Dto、Ro、Vo、Mapper、Service、Controller、MapStruct、配置、过滤器、定时任务、缓存。
- 必须读本文件：涉及 RBAC 权限、资源树、角色/用户授权、接口鉴权、`sys_api` 同步；涉及数据库表结构与字段约定；涉及 Redis / Redisson / jetCache 刷新与失效。
- 必须读本文件：执行 `mvn` 构建；回答项目结构、模块依赖、数据流、缓存相关问题。
- 继续读 `reference/`：需要**具体清单 / 模板 / 链路 / 参数**时，按下面的索引选择文件。
- 核对表结构或业务数据：**直接连库查询**，不要靠猜；连接方式见 [reference/database.md](reference/database.md) 第 1 节。

## 专题索引（`reference/`）

| 文件 | 回答什么问题 | 何时读 |
|------|--------------|--------|
| [architecture.md](reference/architecture.md) | 项目由哪些模块/包组成、怎么启动、有哪些配置、**改某功能该看哪些文件**（代码地图）、有哪些框架与工具类 | 定位代码、改配置、找工具类 |
| [flows.md](reference/flows.md) | **功能是怎么跑起来的**：请求总链路、认证/JWT、鉴权、授权可见性、缓存读写与刷新、接口自动同步、文件上传、启动初始化 | 理解机制、排查链路问题 |
| [domain-model.md](reference/domain-model.md) | 实体关系、术语表、RBAC 授权语义、字段取值口径、种子数据约定 | 弄清业务语义、判断字段含义 |
| [pojo-mapper.md](reference/pojo-mapper.md) | POJO/Mapper/MapStruct 的**模板**与**实际清单**、静态工厂清单、审计基类、用户信息填充步骤 | 新增/修改 Po、Dto、Vo、Mapper、映射器 |
| [api.md](reference/api.md) | REST 路径约定、**控制器端点清单**、入参出参对照、接口形态例外 | 新增/调整接口、查端点 |
| [database.md](reference/database.md) | **14 张表结构与列**、连库方式、字段与建表约定、系统字典内容、数据规模 | 改表、加字段、核对数据 |
| [cache-redis.md](reference/cache-redis.md) | jetCache 配置、`CacheConst` 的 QuickConfig 与 key、三个缓存 Service 的读写方法、`RbacJob`、Redisson/Lua 约定 | 新增/调整缓存、排查缓存问题 |
| [conventions.md](reference/conventions.md) | 构建命令与验证顺序、代码风格、提交规范、**提交前自检清单** | 编译、提交前自查 |

## 项目概述

- 技术栈：JDK 17 + Spring Boot 3 + MyBatis-Plus + Redis(RedissonClient) + jetCache + jakarta.validation + Lombok + Hutool + MapStruct。
- 多模块（按层拆分）：`common-pojo` → `common-mapper` → `common-service` → `application`。
- 基础包：业务 `com.ds.single`；共享 `com.ds.commonPojo` / `com.ds.commonMapper` / `com.ds.commonService`；框架 `org.ds.dsBaseCommon` / `org.ds.dsMybatisPlus` / `org.ds.dsAuthCommon` / `org.ds.dsBaseWeb`。
- 启动类 `com.ds.App`；主配置 `application/src/main/resources/application.yml`。
- 详细模块与包结构见 [reference/architecture.md](reference/architecture.md) 第 1、2 节。

## 构建与命令

```bash
export JAVA_HOME="D:/program/openjdk/j17" && export PATH="$JAVA_HOME/bin:$PATH"

mvn install -pl common-pojo,common-mapper,common-service -DskipTests   # 改动 common-* 后必做
mvn compile -pl application
```

Maven 路径 `/d/program/maven/bin/mvn`，无 Maven Wrapper。完整的验证顺序与提交前自检清单见 [reference/conventions.md](reference/conventions.md) 第 1、4 节。

## 强制规则

### 1. 分层：Controller → Service → Mapper

- Controller 只做参数校验与调用 Service，返回 Vo。
- Service 只调用 Mapper 与其它 Service；**禁止导入/使用 `Wrappers` / `LambdaQueryWrapper` / `QueryWrapper`**；负责事务（`@Transactional(rollbackFor = Exception.class)`）、树构建、主子表关联、启停切换。
- Mapper 继承 `DsBaseMapper<T>` 并加 `@Mapper`；**所有 Wrapper / SQL 只允许写在 `default` 方法中**，或写成 XML / `@Select` 声明式方法。
- 注入统一 `@RequiredArgsConstructor` + `private final`。

```java
// 正确：Wrapper 写在 Mapper 的 default 方法中
default SysUser selectByUsername(String username) {
    return selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
}

// 错误：Wrapper 出现在 Service 中
sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
```

**例外（允许，勿「修正」）**：`controller/common` 下的通用只读接口可越过 Service 直接使用缓存组件——`SysCommonController` 可直接调 `SysCacheUtil.dictItemCache()` / `configCache()` 并直接返回缓存 Dto；`AuthController.getUserInfo` 直接返回 `UserInfoCacheDto`（清单见 [reference/api.md](reference/api.md) 第 4 节）。除此之外的 Controller 必须走 Service 且返回 Vo。

### 2. 接口契约（Controller）

- 类上 `@Validated` + `@RestController` + `@RequestMapping`；请求体参数 `@Valid @RequestBody`，查询参数 `@Valid`。
- 返回 `R.ok(data)` / `R.fail(msg)`；请求体用 Ro/Dto，**禁止用 `Map` 接收或返回**（上述通用只读接口例外）。
- 分页统一 `TableDataUtil.createPageVo(IPage)`。
- 路径与端点命名见 [reference/api.md](reference/api.md) 第 1、2 节。

### 3. POJO 与静态工厂

- 后缀与包：`Po`→`pojo.po`、`Dto`→`pojo.dto`、`Ro`→`pojo.ro`、`Vo`→`pojo.vo`。
- Po 必须 `@TableName(value="表名", autoResultMap=true)`；逻辑删除列加 `@TableLogic`；JSON 列加 `@TableField(typeHandler = JacksonTypeHandler.class)`；**禁止 `@TableField(exist=false)`**。
- **禁止 `new Xxx()` 后连续 `setXxx()`**，一律用类内静态工厂 `Xxx.of(...)`；已有工厂见 [reference/pojo-mapper.md](reference/pojo-mapper.md) 第 4 节。
- **Vo 有两种落点，加字段时影响不同**：
  - **继承 Po 型**（`SysApiListVo`、`SysConfigListVo`、`SysDictListVo`、`SysDictItemListVo`、`SysRoleListVo`、`SysUserListVo`、`SysResourceListVo`、`SysDirNodeVo` 等）：`extends XxxPo`，需要回填审计人名称的再 `implements UserNameFillable`；给 Po 加字段后自动继承、MapStruct 自动映射，**无需改 Vo 与映射器**。
  - **独立声明型**（`SysResourceTreeNodeVo`、`SysDictSelectListVo`、`SysDirSelectNodeVo`、`SysResourceApiOverviewVo`，以及 `LoginVo` / `CaptchaVo` / `FileUploadVo` 等非实体响应）：**不继承 Po**，自行声明字段——**给对应 Po 加字段时必须手工同步这些 Vo**。
- 编辑入参 `XxxEditDto` 也必须补同名字段，否则前端提交不上来。
- 清单与映射关系见 [reference/pojo-mapper.md](reference/pojo-mapper.md) 第 5 节。
- 集合构造用 `CollectionUtil.newArrayList()`，不用 `List.of()`。
- 校验用 `jakarta.validation`（`@NotBlank` / `@NotEmpty` / `@NotNull`），提示信息用中文。

### 4. MapStruct 映射

- 映射器类名必须以 `MS` 结尾、必须 `extends MapStructExt`、统一 `@Mapper(config = MapperCentralConfig.class)`（`IGNORE` 未映射属性 + 禁用 builder 的策略集中在 `MapperCentralConfig`，不要各映射器重复声明）。
- 单例 `INSTANCE = Mappers.getMapper(XxxMS.class)`。
- Dto↔Po、Po↔Vo 一律走 MapStruct，**禁止手写 Bean 拷贝**。
- 批量拷贝必须 `MapStructExt.toPojoList(list, INSTANCE::toXxx)`；**禁止** for 循环逐条 `add`，**禁止** 自己写 `list.stream().map(...).collect(...)`（该实现已收敛在 `toPojoList` 内）。
- **禁止**在映射器中声明 `List<Xxx> toXxxList(List<Xxx>)` 这类集合方法。
- 现有映射器与方向见 [reference/pojo-mapper.md](reference/pojo-mapper.md) 第 8 节。

### 5. 异常处理

- 统一 `throw new BuzException(ResultCode.XXX, "中文消息")`，**必须同时给状态码与消息**。
- **禁止** `throw new RuntimeException(...)`，也禁止抛 `IllegalStateException` / `OssException` 等**无状态码**的运行时异常。
- 状态码：`BAD_REQUEST(400)` 参数/业务校验失败、`UNAUTHORIZED(401)` 未认证、`FORBIDDEN(403)` 无权限、`NOT_FOUND(404)` 不存在、`ERROR(500)` 服务端/配置错误。完整取值见 [reference/architecture.md](reference/architecture.md) 第 5 节。
- Controller **不要** `catch (RuntimeException e) { return R.fail(e.getMessage()); }`（`R.fail(String)` 固定 500，会丢状态码）；直接抛，交 `GlobalExceptionHandler` 转 `R.fail(code, msg)`。
- 参考实现：`AuthServiceImpl`、`SysUserServiceImpl.updatePwd` / `resetPwd`、`SysDictItemServiceImpl.edit`、`SysRoleServiceImpl.deleteBatch`。

### 6. 常量与枚举

**放置规则**（按作用范围就近放置）：

| 作用范围 | 位置 |
|----------|------|
| 跨模块共享（RBAC / 平台 / 配置编码） | `common-pojo` → `com.ds.commonPojo.pojo.sysConst` |
| 缓存 / 服务层（缓存名与 key） | `common-service` → `com.ds.commonService.pojo.sysConst` |
| 应用私有（登录 / 接口同步 / 文件 / 脚本） | `application` → `com.ds.single.pojo.sysConst` |
| 通用 `Y`/`N` | 框架 `org.ds.dsBaseCommon.sysConst.YesNoConst`，**项目内不得再定义** |
| 有限取值 | `pojo.sysEnum`（`ResourceTypeEnum`、`ApiStyleEnum`） |
| 与某 Po 强绑定的字段取值 | 定义在**该 Po 类内**（如 `SysFile.LARGE_FLAG_YES` / `STATUS_COMPLETED`） |

**抽取与使用流程（先盘点 → 能复用必须复用 → 确实没有才新建）**：

1. **盘点**：先在 `pojo.sysConst`（三个模块）、`pojo.sysEnum`、对应 Po、相关工具类内搜索是否已有「值 + 语义」相同的常量（只值同、语义不同不算复用）。
2. **复用**：通用标识 → `YesNoConst`；平台 → `PlatformConst`；配置编码 → `SysConfigConst`；API 风格 → `ApiStyleEnum`；资源类型 → `ResourceTypeEnum`；RBAC → `RbacConst`；缓存 → `CacheConst`；登录/验证码 → `AuthConst`；接口同步 → `DsConst`；文件 → `FileConst`；验证码生成 → `CaptchaUtil`。已有工具类能力（如 `CaptchaUtil.generateCaptchaImage`）也属复用范畴，禁止重复实现。
3. **新建**：确实没有才按领域建 `interface XxxConst`（一个领域一个接口，不混装无关常量）。**仅服务于单个类内部实现**的常量留在该类内、不外提（示例：验证码字符集、图片 Data URI 前缀留在 `CaptchaUtil`）。
4. **使用**：业务层统一 `XxxConst.XXX` 静态引用；需要拼接的 key 必须调用常量类的 `static xxxKey(...)`，禁止调用方自己拼字符串。
5. **禁止**：在 Service / Controller / Mapper / 工具类中散落 `private static final` 魔法值；内联字面量（`"Y"`、`"P"`、`"DEFAULT_PWD"`、`"login:fail:"`）；同一常量在多处重复定义。
6. **维护**：改值只改常量类一处。

### 7. 注释

- **类 / 接口 / 方法写 javadoc**（含 `@param` / `@return`）；**字段与常量逐项写中文注释**。不得出现无注释的类、公开方法或 Po 字段。
- **Service 业务方法体内必须写编号步骤注释**（本规范核心，强制）：`// 1. xxx`、`// 2. xxx`，一步一行，紧贴对应代码上方。
  - 查询类至少覆盖：`查询` → `转 Vo` → `回填审计人名称` → `组装返回`。
  - 写操作必须覆盖：`入参转换` → `校验（不存在时抛 BuzException）` → `落库` → `缓存刷新`。
  - 全量覆盖语义（子表 / 授权关系）必须写明「先删后插」。
  - 缓存刷新必须写明「先同步刷新/失效一次，再延迟 5 秒兜底刷新一次」。
  - 递归、批量回填等复杂私有方法补 javadoc + 行内步骤注释。
- 参考实现：`SysDictItemServiceImpl`、`SysDictItemCacheServiceImpl`、`SysConfigCacheServiceImpl`、`SysResourceServiceImpl`、`SysUserServiceImpl`、`AuthServiceImpl`、`FileServiceImpl`。

### 8. 缓存一致性

- **jetCache 一律编程式**：`CacheManager.getOrCreateCache(CacheConst 的 QuickConfig)` + `@PostConstruct` 初始化，不用 `@Cached` 注解；缓存统一 `CacheType.BOTH`；key 一律走 `CacheConst` 的 `static` 拼接方法；新增缓存先在 `CacheConst` 定义 `QuickConfig` 与 key 方法。
- **一致性模式（写操作强制）**：**立即刷新/失效一次 + `micorSchedulePool` 延迟 5 秒兜底刷新一次**；全量刷新接入 `RbacJob`。
- 刷新与失效怎么选：
  - 数据**已删除 / 已停用** → 立即 `removeXxx`（失效），再延迟兜底刷新；
  - 数据**需立即对新值生效**（编辑、授权、启停） → 立即 `freshXxx`（回源覆盖写），再延迟兜底刷新。
- 点查走缓存、列表直接查库。
- **无法注入 Bean 的场景**（静态方法 / 工具类 / 框架回调）用 `SysCacheUtil.rbacCache()` / `configCache()` / `dictItemCache()`，取回的是同一个 Bean，不会产生两套缓存。
- 延迟 5 秒的意图：等事务提交后再回源，并让其它实例本地缓存最终一致。
- 具体参数、key 拼接返回值、各缓存 Service 的读写方法见 [reference/cache-redis.md](reference/cache-redis.md) 第 2、3 节；代码时序见 [reference/flows.md](reference/flows.md) 第 6 节。

### 9. Redis / Redisson / Lua

- **必须用 `RedissonClient`，禁止 `RedisTemplate`**：字符串 `getBucket`、计数 `getAtomicLong`、Hash `getMap`、分布式标记 `getBucket().setIfAbsent`。
- **多命令原子操作必须写成 Lua 脚本**（如「计数 + 设过期」「判断 + 更新」），经 `redissonClient.getScript()`（`RScript`）执行；**脚本统一集中到 `com.ds.single.pojo.sysConst.LuaRedisScript`，禁止在 Service / 工具类内联脚本字符串**。
- `KEYS` 传 Redis key；`ARGV` 传参数，**时间统一毫秒**，且**必须传数值类型（`Long`/`Integer`）——不要传 `String`**（`JsonJacksonCodec` 会把 String 编码成带引号的 `"600000"`，导致 `PEXPIRE`/`EXPIRE` 解析失败）。
- 调用样例、现有脚本与全部 Redis key 来源见 [reference/cache-redis.md](reference/cache-redis.md) 第 5 节。

### 10. RBAC 授权可见性

- 授权链路、术语与实体关系见 [reference/domain-model.md](reference/domain-model.md) 第 2 节。
- **可见性规则（关键）**：角色只返回**祖先链上每一级都被授权**的资源；父级未授权则子级绝不返回。
- 该校验有**两处落点，必须保持一致**：
  - 内存侧 `SysResourceServiceImpl.isAncestorChainAuthorized`（递归，`getUserAuthTree` 用）；
  - SQL 侧 `SysRbacCommonMapper.getSysRoleAuthApi`（固定 3 层 LEFT JOIN）。
- 改资源层级或授权 SQL 时两处同步修改，并注意性能（固定深度 LEFT JOIN 优于递归 CTE）；两处链路细节见 [reference/flows.md](reference/flows.md) 第 4 节。
- **超管例外**：`RbacConst.SUPER_ADMIN` 直接返回平台下全部启用资源，不查授权关系。
- 新增/调整权限缓存时，刷新入口与 key 维度见 [reference/cache-redis.md](reference/cache-redis.md) 第 3、4 节。

### 11. 数据库字段约定

- 逻辑删除 `del_flag`：`N` 未删、`Y` 已删，Po 加 `@TableLogic`；**`sys_user`、`sys_file` 与 `sys_*_rel` 关联表没有 `del_flag`**（物理删除），不要给它们加。
- 启用标识 `enable_flag`：`Y` / `N`。
- 关联表 `sys_*_rel` 继承 `CreateBasePo`（仅创建人 / 创建时间），无 `del_flag`。
- **仓库内没有 SQL 迁移文件**：加列需人工在库上执行 DDL，再同步 Po 字段。
- **给已有表 / 实体加字段的标准步骤**：① 库上加列 → ② 对应 `Po` 加字段（带中文注释）→ ③ 编辑入参 `XxxEditDto` 补同名字段 → ④ 继承 Po 的 `ListVo` / `NodeVo` **无需改**（`*MSImpl` 自动映射）；**独立声明型 Vo（如 `SysResourceTreeNodeVo`、`SysDictSelectListVo`）必须手工补字段** → ⑤ `mvn compile -pl application` 后核对 `target/generated-sources`。
- 表结构、列清单与字典内容见 [reference/database.md](reference/database.md) 第 2、4 节。

## 常见任务速查

- **新增标准管理模块**：`po`（`@TableName` + `@TableLogic`）→ `dto`/`ro`/`vo`（ListVo 继承 Po + `UserNameFillable`）→ `mapper`（`default` 方法，`pageList` / `list` 统一 `orderByDesc(createTime)`）→ `mapstruct`（`*MS`，继承 `MapStructExt`）→ `service`（接口 + impl，写编号步骤注释）→ `controller`（`/backSysXxx` + REST 路径）。模板见 [reference/pojo-mapper.md](reference/pojo-mapper.md)。
- **新增主子表编辑**：Dto 聚合主表 + 子表 List；Service 内 `@Transactional`，子表「先删后插」；若涉及授权缓存，按第 8 节模式刷新。
- **新增/调整缓存**：先在 `CacheConst` 定义 `QuickConfig` 与 key 方法 → 实现类 `@PostConstruct` + `getOrCreateCache` → 写操作后「立即刷新/失效 + 延迟 5 秒兜底」→ 需要全量刷新则接入 `RbacJob`。
- **给已有表/实体加字段**：见第 11 节步骤。
- **新增接口后同步到 `sys_api`**：调 `POST /backDs/flushApis`；只插入缺失接口，白名单见 `DsConst.WITHOUT_FLUSH_API_CODE`。流程见 [reference/flows.md](reference/flows.md) 第 7 节。
- **排查某个功能怎么跑**：查 [reference/flows.md](reference/flows.md)。
- **定位某功能涉及哪些文件**：查 [reference/architecture.md](reference/architecture.md) 第 4 节代码地图。
- **新增业务异常**：`throw new BuzException(ResultCode.X, "中文消息")`，不要引入无状态码异常。
- **提交前自查**：逐条过 [reference/conventions.md](reference/conventions.md) 第 4 节清单。

## 代码现状偏差（现状，勿当规范模仿）

以下为**当前代码与上述规范不一致**之处。改动相关代码时应按规范实现，**不要照抄现状**：

| 位置 | 偏差 |
|------|------|
| `RbacJob` | ① `SYSTEM_RESOURCE_INIT_LOCK` 以类内 `private static final` 承载 Redis key（应移到常量类）；② `init()` 失败时抛 `RuntimeException`（应用 `BuzException`） |
| `RbacCacheServiceImpl.freshApiCache` | `updateApiMap` 非空时只做 `putAll`，`removeList` 分支被跳过——同批次「有新增有删除」时旧 key 不会被移除 |
| `SysRoleServiceImpl.edit` | 角色编辑后未刷新角色授权缓存（仅 `enableOpt` / `authResource` / `deleteBatch` 有刷新） |
| `SysUserServiceImpl.edit` / `updateInfo` | 用户平台、资料变更后未失效用户信息缓存 `userInfoCacheKey` |
| `SysResourceServiceImpl.edit` | 资源变更后未刷新受影响的角色授权缓存 |
| `SysRoleServiceImpl.authResource`、`SysUserServiceImpl.authRole` / `edit`、`SysResourceServiceImpl.edit` / `deleteBatch` | 用 `forEach` 逐条 `insert`，未用批量插入 |
| 审计字段填充 | 整个框架无 `MetaObjectHandler`，`AllBasePo` / `CreateBasePo` 的 `@TableField(fill = ...)` 不会自动填充。审计字段由 `BuzContextUtil`（由 `RequestInitFilter` 每请求 `contextInit()` 建上下文、请求结束 `clear()`，`AuthFilter` 写入登录信息，`SysDictItemMapper.deleteByDictId` 读取 `getUserId()` / `getOptDate()`）+ 其它写操作手动 `set` 维护。**上下文可用性**：上下文 Map 每请求必建（`RequestInitFilter` 由 `SecurityConfig` 以 `FilterRegistrationBean` 注册为 Servlet 链第一个，`order = Ordered.HIGHEST_PRECEDENCE`，先于 Security 链的 `-100`），未写入的 key getter 返回 `null`；`auth_check=Y` 的接口在 `AuthFilter.actionAuth` 中已校验 token 与用户信息非空，进入 Service 后可直接取值；`auth_check=N` 的接口不带 token 时无登录信息，依赖上下文的接口应配置 `auth_check=Y`；白名单接口由 `requestAnonymousInit()` 写匿名占位用户（userId=`-1`） |
| `TokenUtil.getTokenStr` | 按 `RbacConst.BEARER.length()` 直接 `substring`，未校验 `Authorization` 是否真以 `Bearer ` 开头，非标准头会截错 |
| `SysDictItemCacheServiceImpl.getDictItem` | javadoc 写「首次访问装载该字典的全部字典项」，实现实为**按单条 key 装载**（`loadCacheItem(dictCode, dictItemCode)`） |
| `ApiStyleEnum` | javadoc 枚举注释写成「目录 / 页面」（应为「普通 / 正则」）；与 `sys_api.api_style` 列注释（`C` 固定路径 / `P` 路径参数 / `R` 正则）口径不一致 |
| `ResourceTypeEnum` | `code` 为 `D`/`P`/`B`，而库中存的是字典项编码 `DIR`/`PAGE`/`BUTTON`；枚举当前未被业务代码使用 |
| `AuthController.code()` | 在 Controller 内直接注入 `RedissonClient` 写验证码缓存（越过 Service） |
| `CacheConst` | `roleAuthApiCacheKey` 返回含完整前缀的 key，而 `userInfoCacheKey` / `dictItemCacheKey` 返回不含前缀的业务键，口径不统一；且前缀与缓存名重复拼接 |
| `FileServiceImpl.upload` | 实际只透传 OSS，**不写 `sys_file`**（表结构却按「文件上传记录」设计）；且 `FileUploadVo` 的大小字段是**字节**，而落库的 `sys_file.file_size` 是 **KB**，同一语义两种单位 |
| `SysFileMapper` | 空 Mapper，无任何方法；`sys_file` 当前无查询入口 |
| `DsController` | 类上缺 `@Validated`（其余 12 个 Controller 均有） |
| `SysDictServiceImpl.edit` | 第 92 行声明了 `dictCodes`（注释称「新旧编码一起批量失效」）但**从未使用**，且方法内**完全没有缓存刷新**——注释所述意图未实现 |
| `SysResourceServiceImpl.edit` | 用 `dto.getExtensions().stream().map(ext -> {...}).collect(...)` 手工做 Dto→Po 拷贝，违反「批量拷贝必须用 `MapStructExt.toPojoList`」 |
| `AuthResourceRo.resourceIds` / `AuthRoleRo.roleIds` | 仅 `@NotBlank` 校验 id，**列表字段无 `@NotNull`/`@NotEmpty`**；Service 直接 `forEach` → 传 `null` 时 NPE（应为 400）。取消全部授权需传空数组而非 null |
| `SysDirServiceImpl` | 类 javadoc 称「按 `parentDirId` 组织为树」，但 `list` 实际返回**平铺列表**（`SysDirNodeVo` 也无 `children` 字段）；`deleteBatch` 不级联清理子目录与 `sys_api.dir_id` 引用 |
| `SysConfigServiceImpl` / `SysDictServiceImpl` / `SysDirServiceImpl` | 部分写方法（`SysConfigServiceImpl.deleteBatch`/`enableOpt`、`SysDictServiceImpl.edit`、`SysDirServiceImpl.edit`/`deleteBatch`）缺 `@Transactional`，与 `SysUserServiceImpl`、`SysRoleServiceImpl` 的写法不一致 |
| `selectByIds` / `selectBatchIds` | 项目内混用（MyBatis-Plus 3.5.9 中两者等价，`selectBatchIds` 为 default 别名），建议统一为 `selectByIds` |
