# architecture.md — 模块、包结构、配置与代码地图

> 本文件回答「**项目由哪些模块组成、代码放在哪、怎么启动、有哪些配置和工具可用、改某功能该看哪些文件**」。
> 编码规则见 [../SKILL.md](../SKILL.md)；实体语义见 [domain-model.md](domain-model.md)；机制流程见 [flows.md](flows.md)。

## 1. 模块与依赖方向

```text
singleProject/                # 父 POM（packaging=pom）
├── common-pojo/              # 共享 POJO 与工具（无 Spring 依赖）
├── common-mapper/            # 跨模块查询 Mapper（声明式 @Select / XML）
├── common-service/           # 共享服务（缓存接口、用户填充、审计上下文）
└── application/              # 主应用（可执行，Spring Boot 入口）
```

依赖方向：`application` → `common-service` → `common-mapper` → `common-pojo`。

- 改动 `common-*` 后必须 `mvn install -pl common-pojo,common-mapper,common-service -DskipTests`，`application` 才能引用到新版本。
- 跨模块共享的类（Dto、常量、工具）放 `common-pojo`；跨模块 SQL 放 `common-mapper`；需要 Spring 容器的共享服务（缓存、用户填充）放 `common-service`。

## 2. 包结构

### 2.1 `common-pojo` → `com.ds.commonPojo`

| 包 | 内容 |
|----|------|
| `pojo.po` | `AllBasePo`、`CreateBasePo` |
| `pojo.dto` | `ApiCacheDto`、`RoleAuthApiDto`、`UserInfoCacheDto`、`UserFilleDto` |
| `pojo.ro` | `SortRo` |
| `pojo.sysConst` | `RbacConst`、`PlatformConst`、`SysConfigConst` |
| `pojo.sysEnum` | `ApiStyleEnum` |
| `pojo` | `MapStructExt`、`UserNameFillable` |
| `util` | `TableDataUtil` |

### 2.2 `common-mapper` → `com.ds.commonMapper`

| 类 | 用途 |
|----|------|
| `SysRbacCommonMapper` | 接口缓存全量/单条查询、角色授权接口查询、用户信息装载 |
| `SysUserCommonMapper` | 审计字段（创建人/更新人）用户名批量查询 |

SQL 在 `common-mapper/src/main/resources/mapper/*.xml`。

### 2.3 `common-service` → `com.ds.commonService`

| 包 | 内容 |
|----|------|
| `cache` | `RbacCacheService`、`SysConfigCacheService`、`SysDictItemCacheService`（接口） |
| `pojo.dto` | `SysConfigCacheDto`、`SysDictItemCacheDto` |
| `pojo.prop` | `AuthJwtProp`（`auth-jwt` 配置绑定） |
| `pojo.sysConst` | `CacheConst` |
| `util` | `SysCacheUtil`、`UserInfoFillUtil`、`BuzContextUtil`（请求上下文）、`TokenUtil`（token 解析/生成） |

### 2.4 `application` → `com.ds.single`

| 包 | 内容 |
|----|------|
| `pojo.po` | `SysApi`、`SysConfig`、`SysDict`、`SysDictItem`、`SysDir`、`SysFile`、`SysResource`、`SysResourceApiRel`、`SysResourceExt`、`SysResourceExtConfigInfo`、`SysRole`、`SysRoleResourceRel`、`SysUser`、`SysUserPlatform`、`SysUserRoleRel` |
| `pojo.dto` | `BatchDeleteDto`、`LoginDto`、`SysApiEditDto`、`SysConfigEditDto`、`SysDictEditDto`、`SysDictItemEditDto`、`SysDirEditDto`、`SysResourceApiRelDto`、`SysResourceEditDto`、`SysResourceExtDto`、`SysRoleEditDto`、`SysUserEditDto`、`SysUserUpdateInfoDto`、`SysUserUpdatePwdDto` |
| `pojo.ro` | `AuthResourceRo`、`AuthRoleRo`、`CommonConfigQueryRo`、`CommonDictItemQueryRo`、`FileChunkInitRo`、`FileChunkMergeRo`、`RefreshPermissionRo`、`SelectRoleRo`、`SysApiQueryRo`、`SysConfigQueryRo`、`SysDictItemQueryRo`、`SysDictQueryRo`、`SysDirQueryRo`、`SysResourceQueryRo`、`SysRoleQueryRo`、`SysUserQueryRo` |
| `pojo.vo` | `CaptchaVo`、`DsSpringApiVo`、`FileChunkInitVo`、`FileUploadVo`、`LoginSelectRoleVo`、`LoginVo`、`SysApiListVo`、`SysConfigListVo`、`SysDictItemListVo`、`SysDictListVo`、`SysDictSelectListVo`、`SysDirNodeVo`、`SysDirSelectNodeVo`、`SysResourceApiOverviewVo`、`SysResourceListVo`、`SysResourceTreeNodeVo`、`SysRoleListVo`、`SysRoleResourceRelVo`、`SysUserListVo`、`SysUserPlatformVo`、`SysUserRoleRelVo` |
| `pojo.sysConst` | `AuthConst`、`DsConst`、`FileConst`、`LuaRedisScript` |
| `pojo.sysEnum` | `ResourceTypeEnum` |
| `pojo.prop` | `OssConfigProp`（`ds.oss` 配置绑定）、`AuthWhiteProp`（`api-config` 免鉴权白名单绑定） |
| `mapper` | `SysApiMapper`、`SysConfigMapper`、`SysDictItemMapper`、`SysDictMapper`、`SysDirMapper`、`SysFileMapper`、`SysResourceApiRelMapper`、`SysResourceExtMapper`、`SysResourceMapper`、`SysRoleMapper`、`SysRoleResourceRelMapper`、`SysUserMapper`、`SysUserPlatformMapper`、`SysUserRoleRelMapper` |
| `mapstruct` | `MapperCentralConfig` + 9 个 `*MS` 映射器（清单见 [pojo-mapper.md](pojo-mapper.md)） |
| `service.buz` / `.impl` | `AuthService`、`DsService`、`FileService`、`SysApiService`、`SysConfigService`、`SysDictItemService`、`SysDictService`、`SysDirService`、`SysResourceService`、`SysRoleService`、`SysUserService` |
| `service.cache.impl` | `RbacCacheServiceImpl`、`SysConfigCacheServiceImpl`、`SysDictItemCacheServiceImpl` |
| `controller.back` | `DsController`、`FileController`、`LoginController`、`SysApiController`、`SysConfigController`、`SysDictController`、`SysDictItemController`、`SysDirController`、`SysResourceController`、`SysRoleController`、`SysUserController` |
| `controller.common` | `AuthController`、`SysCommonController` |
| `config` | `MybatisPlusConfig`、`OssConfig`、`SecurityConfig`、`ThreadPoolConfig`、`filter.RequestInitFilter`、`filter.AuthFilter` |
| `job` | `RbacJob` |
| 根 | `App`（启动类） |

## 3. 启动与配置

### 3.1 启动类 `com.ds.App`

```java
@EnableScheduling
@SpringBootApplication
@EnableMethodCache(basePackages = {"com.ds.commonService.cache"})
public class App {
    public static void main(String[] args) {
        new SpringApplicationBuilder(App.class).registerShutdownHook(true).run(args);
    }
}
```

- `@EnableMethodCache` 虽开启，但项目实际使用**编程式** jetCache（`CacheManager` + `QuickConfig`），未用 `@Cached` 注解。
- `registerShutdownHook(true)` 保证优雅停机时执行缓存/线程池清理。

### 3.2 `application.yml` 配置项

| 配置 | 值 / 说明 |
|------|-----------|
| `spring.application.name` | `singleApp` |
| `spring.redis.redisson.file` | `classpath:redisson-loc.yaml` |
| `spring.servlet.multipart` | `max-file-size: 10MB`、`max-request-size: 2MB` |
| `ds.datasource.pool` | `enabled: true`、`mode: single`；`datasources.master` 为 Hikari（`max-pool-size: 20`、`min-idle: 5`、`connection-timeout: 30000`、`idle-timeout: 600000`、`max-lifetime: 1800000`、`pool-name: MasterPool`） |
| 数据源 URL | `jdbc:mysql://dev.meichis.com:3306/ruoyi`，`root` / `meichis1!` |
| `ds.jet-cache.enabled` / `ds.jetc-cache.json.enabled` | 均为 `true`（注意两个 key 拼写不同，前者 `jet-cache`、后者 `jetc-cache`） |
| `ds.oss.tx-oss.common` | `accessKey` / `secretKey` / `region: ap-nanjing` / `endpoint: https://cos.ap-nanjing.myqcloud.com` / `defaultBucket: common` |
| `jetcache` | 见 [cache-redis.md](cache-redis.md) |
| `mybatis-plus` | `logic-delete-field: del_flag`、`logic-delete-value: Y`、`logic-not-delete-value: N`；`auto-mapping-behavior: full`；日志用 Slf4jImpl |
| `auth-jwt` | `expirationSecond: 1296000`（15 天）、`base64EncodedSecretKey: ds-single-project-jwt-secret-key-2026-long-enough` |
| `api-config.whitePaths` | 免鉴权路径白名单（Ant 风格），绑定 `AuthWhiteProp`（`@ConfigurationProperties("api-config")`）；默认 `/backAuth/webLogin`、`/commonAuth/code`、`/error`。未携带 `Api-Code` 的请求命中才放行 |

### 3.3 `redisson-loc.yaml`

```yaml
codec: !<org.redisson.codec.JsonJacksonCodec> {}
singleServerConfig:
  address: "redis://127.0.0.1:6379"
  database: 7
```

### 3.4 配置类

| 类 | 职责 |
|----|------|
| `MybatisPlusConfig` | 仅注册 `PaginationInnerInterceptor(DbType.MYSQL)`；**无自定义 `MetaObjectHandler`** |
| `OssConfig` | 按 `OssConfigProp.txOss` 注册 `DsOssProxy`，每个 bucket 一个 `S3OssTemplate` |
| `SecurityConfig` | **过滤器编排集中在此**：关闭全部 Security 默认行为、`anyRequest().permitAll()`；`AuthFilter` 前置到 `UsernamePasswordAuthenticationFilter` 之前；用 `FilterRegistrationBean` 显式注册 `RequestInitFilter`（`order = Ordered.HIGHEST_PRECEDENCE`，Servlet 链第一个）并把 `AuthFilter` 的 Servlet 级自动注册 `setEnabled(false)`（避免同一请求执行两遍） |
| `ThreadPoolConfig` | `micorTaskPool`（小任务线程池，TTL、CallerRunsPolicy）、`micorSchedulePool`（延迟队列；名称拼写为 `micor`） |
| `filter.RequestInitFilter` | 请求上下文生命周期：`contextInit()` 建空上下文 → 生成并写入 `traceId` → 放行 → `finally` 中 `clear()`。**由 `SecurityConfig#requestInitFilterRegistration` 以 `FilterRegistrationBean` 注册为 Servlet 链第一个（`Ordered.HIGHEST_PRECEDENCE` = `Integer.MIN_VALUE`），必须先于 Security 链中的 `AuthFilter` 执行**（Security 链注册顺序为 `-100`）；类上不再标 `@Component`，避免重复注册 |
| `filter.AuthFilter` | 接口级鉴权：解析 token 并由 `BuzContextUtil.requestInit` 写入登录信息（上下文由 `RequestInitFilter` 预先创建）→ 超管放行 → 未带 `Api-Code` 走白名单（写匿名占位用户）→ 带 `Api-Code` 校验接口/路径/角色授权；失败时直接写 JSON |

## 4. 代码地图（按功能找代码）

> 改某功能时，从这里定位文件；机制细节见 [flows.md](flows.md)。

| 我想改… | 主要文件 |
|---------|----------|
| 登录 / 验证码 / 选角色 | `controller/back/LoginController`、`controller/common/AuthController`、`service/buz/impl/AuthServiceImpl`、`pojo/dto/LoginDto`、`pojo/ro/SelectRoleRo`、`pojo/vo/LoginVo` |
| JWT 生成 / 解析 / 有效期 | `common-service/util/TokenUtil`（含 `getTokenStr` 去 `Bearer ` 前缀）、`common-service/pojo/prop/AuthJwtProp`、`application.yml` 的 `auth-jwt` |
| 请求上下文（操作人/操作时间/日志开关/traceId） | `common-service/util/BuzContextUtil`、`config/filter/RequestInitFilter`（建上下文 + 写 traceId + 请求结束清理）、`config/filter/AuthFilter`（解析 token 与装载用户信息后调用 `requestInit`） |
| 请求过滤器 / 接口鉴权 | `config/filter/RequestInitFilter`、`config/filter/AuthFilter`、`config/SecurityConfig`、`pojo/prop/AuthWhiteProp`、`application.yml` 的 `api-config.whitePaths` |
| 用户管理 / 改密 / 平台 / 角色授权 | `controller/back/SysUserController`、`service/buz/impl/SysUserServiceImpl`、`pojo/po/SysUser`、`SysUserPlatform`、`SysUserRoleRel` |
| 角色管理 / 资源授权 / 刷新权限 | `controller/back/SysRoleController`、`service/buz/impl/SysRoleServiceImpl`、`mapper/SysRoleResourceRelMapper` |
| 资源树 / 菜单 / 用户授权树 | `controller/back/SysResourceController`、`controller/common/AuthController#userAuthTree`、`service/buz/impl/SysResourceServiceImpl`、`mapper/SysResourceMapper`、`SysResourceExtMapper`、`SysResourceApiRelMapper` |
| 接口（API）管理 / 自动同步 | `controller/back/DsController`、`service/buz/impl/DsServiceImpl`、`controller/back/SysApiController`、`service/buz/impl/SysApiServiceImpl`、`pojo/sysConst/DsConst` |
| 配置 / 字典 / 字典项 | `SysConfigController`+`SysConfigServiceImpl`、`SysDictController`+`SysDictServiceImpl`、`SysDictItemController`+`SysDictItemServiceImpl`、`controller/common/SysCommonController` |
| 缓存策略 / key / 刷新 | `common-service/pojo/sysConst/CacheConst`、`service/cache/impl/*`、`common-service/util/SysCacheUtil`、`job/RbacJob` |
| 接口/角色/用户信息缓存 | `common-service/cache/RbacCacheService`、`service/cache/impl/RbacCacheServiceImpl`、`common-mapper/SysRbacCommonMapper`（+ XML） |
| 审计字段（创建人/更新人名称）回填 | `common-service/util/UserInfoFillUtil`、`common-mapper/SysUserCommonMapper`（+ XML）、`pojo/vo/*ListVo`（实现 `UserNameFillable`） |
| 文件上传 / 分片 | `controller/back/FileController`、`service/buz/impl/FileServiceImpl`、`pojo/sysConst/FileConst`、`config/OssConfig`、`pojo/prop/OssConfigProp`、`pojo/po/SysFile` |
| 数据库字段 / 表结构 | `pojo/po/*`；表结构见 [database.md](database.md) |
| MapStruct 映射 | `mapstruct/*MS`（清单见 [pojo-mapper.md](pojo-mapper.md)） |
| 线程池 / 延迟刷新 | `config/ThreadPoolConfig`（`micorSchedulePool`） |
| 全局异常 / 统一响应 | 框架 `org.ds.dsBaseWeb.exception.GlobalExceptionHandler`、`org.ds.dsBaseCommon.pojo.vo.R` |

## 5. 框架与项目工具类

| 类 | 来源 | 用途 |
|----|------|------|
| `R<T>` | `org.ds.dsBaseCommon.pojo.vo` | 统一响应：`R.ok(data)`、`R.fail(msg)` |
| `TableData<T>` | `org.ds.dsBaseCommon.pojo.vo` | 分页响应包装 |
| `TableDataUtil` | `com.ds.commonPojo.util` | `createPageVo(IPage<T>)` / `createPageVo(List<T>)` |
| `PageQueryRo` | `org.ds.dsBaseCommon.pojo.ro` | 查询 Ro 基类（`pageNum` / `pageSize`，均为 `Long`） |
| `DsBaseMapper<T>` | `org.ds.dsMybatisPlus` | Mapper 基类 |
| `BasePo` | `org.ds.dsBaseCommon.pojo.po` | PO 根接口 |
| `AllBasePo` / `CreateBasePo` | `com.ds.commonPojo.pojo.po` | 审计基类 |
| `ResultCode` / `ErrorCode` / `BuzException` | `org.ds.dsBaseCommon` | 业务异常与错误码 |
| `GlobalExceptionHandler` | `org.ds.dsBaseWeb.exception` | `BuzException` → `R.fail(code, msg)`；未捕获异常兜底 500 |
| `BuzContextUtil` | `com.ds.commonService.util` | 请求上下文（`TransmittableThreadLocal`）。生命周期：`contextInit()`（建空上下文，**必须先调用**）→ `setTraceId` / `requestInit(tokenInfo, userInfo, apiCacheDto)` / `requestAnonymousInit()`（白名单匿名占位）→ `clear()`。读取：`getTokenInfo()` / `getUserId()` / `getUserInfo()` / `getOptDate()` / `getLogReq()` / `getLogResp()` / `getTraceId()`；未写入的 key 返回 `null`，`auth_check=Y` 的接口链路内 `TOKEN_INFO` / `USER_INFO` 必有值 |
| `TokenUtil` | `com.ds.commonService.util` | token 工具：`getTokenStr(header)`（去 `Bearer ` 前缀）、`parseToken(token)`（校验+解析，失败抛 `BuzException(UNAUTHORIZED, "token无效")`）、`generateToken(tokenInfoDto)` |
| `TokenInfoDto` | `org.ds.dsAuthCommon.pojo.dto` | Token 载荷（`userId` / `selectRoleCode` / `platformCode`） |
| `CaptchaUtil` | `org.ds.captcha.util` | `generateCaptchaText(4)`、`generateCaptchaImage(text)` |
| `MapStructExt` | `com.ds.commonPojo.pojo` | `toPojoList(List<P>, Function<P,V>)` |
| `UserNameFillable` | `com.ds.commonPojo.pojo` | 用户名回填标记接口 |
| `SysCacheUtil` | `com.ds.commonService.util` | 静态缓存入口：`rbacCache()` / `configCache()` / `dictItemCache()`（`SpringUtil.getBean`） |
| `UserInfoFillUtil` | `com.ds.commonService.util` | `fillUserNames(List<? extends UserNameFillable>)` |
| `SortRo` | `com.ds.commonPojo.pojo.ro` | 排序入参 |
| `JwtUtil` / `JwtConfigDto` | `org.ds.dsBaseUtil` | JWT 生成校验 / JWT 配置 DTO |
| `ThreadPoolFactory` / `IoThreadPoolConfig` | `org.ds.dsBaseUtil.threadpool` | 线程池工厂 |
| `DsOssProxy` / `OssUploadResult` / `PartETag` | `org.ds.dsOssCommon` | OSS 代理与上传结果 |
| `S3OssConfig` / `S3OssTemplate` | `org.ds.dsS3Oss` | S3 兼容（腾讯 COS）实现 |

### ResultCode 取值

`SUCCESS`、`BAD_REQUEST(400)`、`UNAUTHORIZED(401)`、`FORBIDDEN(403)`、`NOT_FOUND(404)`、`METHOD_NOT_ALLOWED(405)`、`UNSUPPORTED_MEDIA_TYPE(415)`、`ERROR(500)`。
