# flows.md — 端到端链路与关键机制

> 本文件回答「**这个功能是怎么跑起来的**」：一次请求经过哪些组件、认证与鉴权怎么完成、授权可见性怎么算出来、缓存怎么读写与刷新、接口怎么自动同步、文件怎么上传。
> 规则见 [../SKILL.md](../SKILL.md)；实体语义见 [domain-model.md](domain-model.md)；缓存参数见 [cache-redis.md](cache-redis.md)。

## 1. 请求总链路

```text
HTTP 请求
  └─ RequestInitFilter（Servlet 链第一个：SecurityConfig 显式注册 order=HIGHEST_PRECEDENCE）
       ├─ BuzContextUtil.contextInit()      # 建空的线程上下文（后续写入/读取的前提）
       ├─ BuzContextUtil.setTraceId(uuid)   # 写请求链路 ID
       ├─ chain.doFilter(...) ─────────────┐
       └─ finally: BuzContextUtil.clear()  # 释放上下文（防线程复用残留）
  └─ SecurityConfig（Spring Security 过滤器链，anyRequest().permitAll()）
       └─ AuthFilter（在 UsernamePasswordAuthenticationFilter 之前）
            ├─ TokenUtil.getTokenStr(Authorization 头)   # 去掉 "Bearer " 前缀
            ├─ TokenUtil.parseToken(token) + RbacCacheService.loadUserInfo(...)  # 校验解析 token、装载用户信息
            ├─ BuzContextUtil.requestInit(tokenInfo, userInfo, apiCacheDto)      # 写入登录信息与日志开关
            ├─ 免鉴权白名单分支：BuzContextUtil.requestAnonymousInit()            # 写匿名占位用户
            └─ chain.doFilter(...)                       # 鉴权通过后放行
       └─ Controller（@Validated，参数 @Valid，返回 R<Vo>）
            └─ Service（业务编排、事务、树构建、缓存刷新）
                 └─ Mapper（default Wrapper / XML / @Select）
                      └─ MySQL（MyBatis-Plus，逻辑删除自动追加 del_flag='N'）
                 └─ Cache（jetCache BOTH：本地 caffeine → 远端 Redis → 回源 Mapper）
```

要点：

- **过滤器顺序**：`RequestInitFilter` 必须是 Servlet 过滤器链中的第一个（由 `SecurityConfig#requestInitFilterRegistration` 以 `FilterRegistrationBean` 显式注册，`order = Ordered.HIGHEST_PRECEDENCE = Integer.MIN_VALUE`），先于 Security 链（注册顺序 `-100`，链内含 `AuthFilter`）执行。若顺序颠倒，`BuzContextUtil` 的写入/读取会因上下文未创建而 NPE。`AuthFilter` 的 Servlet 级自动注册已 `setEnabled(false)`，只在 Security 链中执行一次。
- 过滤器链**做接口级鉴权**：`AuthFilter` 解析 token → 超管直接放行 → 按 `Api-Code` 校验接口/路径/角色授权（第 3 节）。
- 请求上下文（`TransmittableThreadLocal`）保存 `TOKEN_INFO` / `USER_INFO` / `OPT_DATE` / `LOG_REQ` / `LOG_RESP` / `TRACE_ID`：`contextInit()` 建空上下文，`requestInit(...)` 写登录信息，白名单请求由 `requestAnonymousInit()` 写匿名占位用户，业务层通过 `BuzContextUtil.getTokenInfo()` / `getUserId()` / `getUserInfo()` 读取当前用户；审计字段（如 `SysDictItemMapper.deleteByDictId`）读取 `getUserId()` / `getOptDate()`。**`auth_check=Y` 的接口在鉴权时已校验 token 与用户信息非空，Service 内可直接取值（未写入的 key 才返回 null）。**
- 逻辑删除由 `mybatis-plus.global-config.db-config` 全局配置 + Po 的 `@TableLogic` 双重声明。

## 2. 认证链路

### 2.1 获取验证码 `GET /commonAuth/code`

```text
AuthController.code()
  ├─ sessionId = UUID（去横线）
  ├─ captcha = CaptchaUtil.generateCaptchaText(4)          # 明文
  ├─ base64  = CaptchaUtil.generateCaptchaImage(captcha)   # 图片
  ├─ Redisson: bucket(AuthConst.captchaKey(sessionId)).set(captcha, 5, MINUTES)
  └─ return CaptchaVo.of(sessionId, base64)
```

> 注意：验证码的写入发生在 **Controller** 层（直接注入 `RedissonClient`），是本项目已知的分层例外。

### 2.2 登录 `POST /backAuth/webLogin`

`AuthServiceImpl.login` 的完整顺序（编号与代码注释一致）：

1. 读配置 `LOGIN_FAIL_MAX`（默认 5）、`LOGIN_LOCK_MINUTES`（默认 10），走 `SysCacheUtil.configCache().getConfigValue(...)`。
2. 校验锁定：`getAtomicLong(AuthConst.loginFailKey(username)).get() >= maxFailCount` → 抛 `BAD_REQUEST`「账号已被锁定 N 分钟」。
3. 校验验证码：`bucket(AuthConst.captchaKey(sessionId)).get()`，**忽略大小写**，校验后**立即 delete**（一次性）。
4. 按用户名查用户（`SysUserMapper.selectByUsername`）；不存在也累加失败计数，抛「用户名或密码错误」（不暴露账号是否存在）。
5. 校验密码：入参明文 MD5 后与库中密文比对；错误累加失败计数。
6. 校验 `enable_flag = Y`。
7. 校验平台授权：`sys_user_platform` 中存在该 user + platform，否则抛「该账号未授权此平台」。
8. 清除失败计数。
9. **`rbacCacheService.updateUserInfo(userId, platformCode)`**：强制查库并覆盖用户信息缓存（不复用旧缓存）。
10. 校验角色列表非空。
11. 单角色 → 直接签发完整 token；多角色 → 返回 `selectRoleList`（token 为「未选角色」态）。

**失败计数用 Lua 原子脚本**（`LuaRedisScript.INCREMENT_FAIL_SCRIPT`）：`INCR` + 首次 `PEXPIRE`，`ARGV` 传 `Long` 毫秒（`TimeUnit.MINUTES.toMillis(...)`）。参考 [cache-redis.md](cache-redis.md) 第 5 节。

### 2.3 选角色 `POST /commonAuth/selectRole`

```text
AuthServiceImpl.selectRole(roleCode)
  ├─ BuzContextUtil.getTokenInfo()                   # 取预颁发 token 中的 userId / platformCode（上下文由 RequestInitFilter 创建、AuthFilter 写入）
  ├─ rbacCacheService.loadUserInfo(userId, platformCode)   # 走缓存（含角色列表）
  ├─ 校验 roleCode 在用户的角色列表内，否则抛「未授权该角色」
  └─ TokenUtil.generateToken(tokenInfo)              # 写入 selectRoleCode，签发完整 token
```

### 2.4 Token 的生成与解析（静态工具 `TokenUtil`）

| 环节 | 实现 |
|------|------|
| 生成 | `TokenUtil.generateToken(tokenInfoDto)` → `SpringUtil.getBean(AuthJwtProp.class)` → `JwtUtil.generateToken(tokenInfoDto.createTokenMap(), authJwtProp)` |
| 解析 | `TokenUtil.parseToken(token)` → `JwtUtil.validateToken` 失败抛 `BuzException(UNAUTHORIZED, "token无效")`，成功 `JwtUtil.parseToken` → `TokenInfoDto.parseToken(...)`（**不再写上下文**，写上下文由 `BuzContextUtil.requestInit` 负责） |
| 配置 | `AuthJwtProp`（`@ConfigurationProperties("auth-jwt")` extends `JwtConfigDto`，HS256）；`application.yml` 的 `auth-jwt.expirationSecond=1296000`（15 天） |
| 取头 | `TokenUtil.getTokenStr(header)`（`common-service/util/TokenUtil`）按 `RbacConst.BEARER.length()` **截断**取 token（未校验前缀是否存在） |

`TokenInfoDto` 字段：`userId`、`platformCode`、`selectRoleCode`（选角色后写入）。

## 3. 鉴权链路（已启用）

`AuthFilter` 按以下顺序处理每个请求：

1. **解析 token 并写入登录信息**：`Authorization` 头 → `TokenUtil.getTokenStr` 去 `Bearer ` 前缀 → 按 `Api-Code` 预取接口缓存（`requestInit` 用它写日志开关，未带时为 null）→ `TokenUtil.parseToken(token)` 校验并解析 → `rbacCacheService.loadUserInfo(userId, platformCode)` 装载用户信息 → `BuzContextUtil.requestInit(tokenInfo, userInfo, apiCacheDto)` 写入上下文；**无 token、解析失败或用户不存在时不写入登录信息**（上下文 Map 由 `RequestInitFilter` 预先创建，不清理，使 getter 返回 null 而非 NPE），按未登录处理。
2. **超级管理员放行**：`tokenInfo.selectRoleCode == RbacConst.SUPER_ADMIN` → 直接放行，**不做任何后续鉴权**（不校验 Api-Code、不校验路径、不看 authCheck）。
3. **未携带 `Api-Code` 请求头**：命中免鉴权白名单（配置 `api-config.whitePaths`，Ant 风格）→ 先 `BuzContextUtil.requestAnonymousInit()` 写匿名占位用户再放行（保证审计字段有操作人）；未命中 → `400 缺少接口编码请求头[Api-Code]`。
4. **携带 `Api-Code`**，依次判断：
   - 上游预取的 `apiCacheDto` 为空 → `400 接口编码不存在`
   - `enable_flag != Y` → `403 接口已停用`
   - 路径不匹配 → `400 接口编码与请求路径不匹配`
   - `auth_check != Y` → 放行
   - `auth_check == Y`：未登录 → `401 认证信息为空`；`getRoleAuthApi(platformCode, selectRoleCode)` 中不含该 apiCode → `403 权限不足`；否则放行

**路径匹配规则**：

| apiStyle | 规则 |
|----------|------|
| `C`（固定路径） | `requestURI.equals(apiPath)` |
| `P`（路径参数） | 先把 `apiPath` 中的 `{xxx}` 占位符替换为 `[^/]+`，再 `requestURI.matches(regex)` |

> ⚠️ `P` 风格**必须先转换占位符**：登记值是 `/backSysApi/find/detail/{id}`，直接当正则会抛 `PatternSyntaxException: Illegal repetition`（`{id}` 不是合法量词）导致接口 500。当前 18 条 `P` 记录全部是这种形态。

**响应方式**：鉴权失败**在过滤器内直接写出** `R.fail(code, msg)` 结构的 JSON 并设置对应 HTTP 状态码——过滤器执行早于 `DispatcherServlet`，此处抛出的异常不会被 `GlobalExceptionHandler` 捕获。

**当前生效范围（重要）**：`sys_api.auth_check` 现有 74 条记录**全部为 `N`**，因此除白名单与超管外，请求只要带对 `Api-Code` 且路径匹配即放行。要让某接口真正受角色权限约束，需把该接口 `auth_check` 改为 `Y`，并确保其所属资源已被角色授权。

### 3.1 前端调用约定

- 业务请求需同时携带 `Authorization: Bearer <token>` 与 `Api-Code: <接口编码>`。
- 未带 `Api-Code` 的请求只允许命中白名单（默认：`/backAuth/webLogin`、`/commonAuth/code`、`/error`）；新增匿名接口时需同步在 `application.yml` 的 `api-config.whitePaths` 中补路径。
- 多角色用户须先调 `POST /commonAuth/selectRole` 换取含 `selectRoleCode` 的完整 token，否则 `auth_check=Y` 的接口会因角色编码为空被判 `403`。

## 4. 资源授权可见性链路

### 4.1 入口

`GET /commonAuth/find/userAuthTree` → `SysResourceServiceImpl.getUserAuthTree()`。

### 4.2 内存侧（用户菜单树）

1. 取 token 中的 `platformCode` + `selectRoleCode`。
2. 若 `selectRoleCode == SUPER_ADMIN` → 直接取平台下 `enable_flag=Y` 的全部资源建树返回。
3. 否则按 `selectRoleCode + platformCode` 反查角色（`SysRoleMapper.selectByRoleCodeAndPlatform`）。
4. 取该角色的资源授权 ID 集合（`sys_role_resource_rel`）。
5. 加载平台下全部启用资源为 `id → 资源` 映射。
6. 对每个已授权 ID 调 `isAncestorChainAuthorized(...)`：递归向上校验父级、祖父级是否在授权集合内（`visited` 去重防环）。
7. 仅保留通过校验的资源，按 `parent_resource_id` 分组建树。

### 4.3 SQL 侧（角色授权接口，供接口级鉴权缓存）

`SysRbacCommonMapper.getSysRoleAuthApi(platformCode, roleCodes)`：

```sql
SELECT t1.role_code, t5.api_code
FROM sys_role t1
  JOIN sys_role_resource_rel t2 ON t1.id = t2.role_id
  JOIN sys_resource t3        ON t2.resource_id = t3.id
  LEFT JOIN sys_resource t3_p      ON t3.parent_resource_id = t3_p.id         -- 父级
  LEFT JOIN sys_role_resource_rel t2_p ON t3_p.id = t2_p.resource_id AND t2_p.role_id = t1.id
  LEFT JOIN sys_resource t3_gp     ON t3_p.parent_resource_id = t3_gp.id      -- 祖父级
  LEFT JOIN sys_role_resource_rel t2_gp ON t3_gp.id = t2_gp.resource_id AND t2_gp.role_id = t1.id
  JOIN sys_resource_api_rel t4 ON t3.id = t4.resource_id
  JOIN sys_api t5              ON t4.api_id = t5.id
WHERE t1.platform_code = #{platformCode}
  AND t1.role_code IN (...)
  AND t1.del_flag='N'
  AND t3.enable_flag='Y' AND t3.platform_code=#{platformCode} AND t3.del_flag='N'
  AND t5.enable_flag='Y' AND t5.del_flag='N'
  AND (t3.parent_resource_id IS NULL OR t2_p.id IS NOT NULL)
  AND (t3_p.parent_resource_id IS NULL OR t2_gp.id IS NOT NULL);
```

**两处必须一致**：内存递归与 SQL 固定 3 层 JOIN 表达的是同一条祖先链规则。改资源层级或授权语义时同步修改，否则菜单可见性与接口鉴权会分叉。

## 5. 用户信息缓存装载链路

```text
RbacCacheService
  ├─ loadUserInfo(userId, platform)     # computeIfAbsent → SysRbacCommonMapper.loadUserInfoById
  └─ updateUserInfo(userId, platform)   # 强制查库 → 非 null 才 put（避免写 null）
```

- 缓存 key 由 `CacheConst.userInfoCacheKey(userId, platformCode)` 生成（返回 `平台编码:用户ID`，前缀由缓存名 `{RBAC}:userInfo:` 提供）。
- `loadUserInfoById` 单条 SQL 同时取出用户基本字段 + 平台编码 + 角色编码集合（`resultMap` 的 `collection` 映射到 `roleCodeList`）。
- 登录走 `updateUserInfo`（强制刷新），业务查询走 `loadUserInfo`（命中即返回）。

## 6. 缓存读写与刷新链路

### 6.1 读（点查穿透）

```text
Service → CacheService 点查方法
  → Cache.computeIfAbsent(key, k -> Mapper 装载)
      ├─ 命中（本地 caffeine / 远端 Redis）→ 直接返回
      └─ 未命中 → 回源数据库 → 写回缓存 → 返回
```

- 接口缓存回源：`SysRbacCommonMapper.selectOneApi(apiCode)`
- 角色授权缓存回源：`SysRbacCommonMapper.getSysRoleAuthApi(platformCode, {roleCode})`
- 用户信息缓存回源：`SysRbacCommonMapper.loadUserInfoById(userId, platformCode)`
- 配置缓存回源：`SysConfigMapper.selectByCode(configCode)`
- 字典项缓存回源：`SysDictItemMapper.loadCacheItem(dictCode, dictItemCode)`
- **列表读取不缓存，直接查库**（字典项 `getAllDictItem*`、配置列表、各业务列表）。

### 6.2 写（刷新/失效 + 延迟兜底）

标准模式（以字典项为例）：

```text
SysDictItemServiceImpl.edit(dto)
  ├─ 落库（insert / updateById）
  ├─ 立即失效：sysDictItemCacheService.removeByDictCodes({dictCode})
  └─ 延迟兜底：micorSchedulePool.schedule(
         () -> sysDictItemCacheService.freshDictItem(dictCode, dictItemCode), 5, SECONDS)
```

| 场景 | 立即动作 | 延迟动作（5s） |
|------|----------|----------------|
| 字典项新增/编辑 | `removeByDictCodes`（整字典失效） | `freshDictItem`（精确回写） |
| 字典项删除 | `removeByDictCodes` | `freshAllDictItemByDictCodes` |
| 字典项启停 | `freshDictItem` | `freshDictItem` |
| 角色启停 / 资源授权 | `freshRoleAuthApi` | `freshRoleAuthApi` |
| 角色删除 | `removeRoleAuthApi` | `freshRoleAuthApi` |
| 配置变更 | `removeConfig` / `freshConfig` | — |

**延迟 5 秒的目的**：① 等事务提交后再读库，避免读到未提交数据；② 让其它实例的本地 caffeine 缓存通过回源覆盖最终一致（`syncLocal` + `broadcastChannel: projectDS`）。

### 6.3 全量与启动初始化

`RbacJob`：

1. `@PostConstruct init()`：`bucket("SYSTEM_RESOURCE_INIT_LOCK").setIfAbsent("", 12h)` 抢标记，抢到才按「配置 → 字典 → API」全量刷新；失败则删标记并抛出。
2. 每日定时：`02:00` 配置（`freshAllConfig`）、`02:01` 字典（`freshAllDictItemByDictCodes`）、`02:02` API（`flushSysApiCache`）。
3. 手动刷新角色权限：`POST /backSysRole/refreshPermission`。

详细参数见 [cache-redis.md](cache-redis.md) 第 3、4 节。

## 7. 接口自动同步链路

`POST /backDs/flushApis` → `DsServiceImpl.flushApis()`（`@Transactional`）：

1. `listSpringApis()` 扫描 `RequestMappingHandlerMapping.getHandlerMethods()`，把每个 `RequestMappingInfo` 展开为「请求方式 + 路径 + 后端方法」；有固定路径取 `getDirectPaths()`，否则取 `getPatterns().getPatternString()`。
2. 查询库中全部接口（`SysApiMapper.selectAll`）。
3. 取白名单 `apiCode`（字典 `WITHOUT_FLUSH_API_CODE` 的启用项）。
4. 库中接口按「是否白名单」拆成两个 `apiPath → List<SysApi>` 映射。
5. 遍历扫描结果：
   - 该路径下已存在白名单接口 → 跳过；
   - 非白名单路径下存在「一致记录」→ 跳过（`isSameApi`：`api_path` 与 `backend_full_class_name` 全等，且库中 `request_type_list` 包含本次扫描的请求方式）；
   - 否则用 `SysApi.ofAuto(...)` 生成新记录（`apiCode`/`apiName` = `自动生成_` + `IdWorker.getIdStr()`，`enableFlag=N`），并加回映射避免同批次重复插入。
6. 批量 `insert(toInsertApis)`。

`GET /backDs/find/apis`（`listSpringApis`）只扫描返回，不写库。

## 8. 文件上传链路

### 8.1 小文件 `POST /file/upload`

`FileServiceImpl.upload(file)`：

1. `defaultBucket()` 取默认 OSS 的 `defaultBucket`（取不到 → `BuzException(ERROR, "默认 OSS 存储桶未配置")`）。
2. `buildObjectKey(原文件名)` = `yyyy/MM/dd` + `/` + UUID（去横线）+ `.扩展名`。
3. `dsOssProxy.putObject(bucket, objectKey, in, contentType)`（`contentType` 缺失时用 `FileConst.DEFAULT_CONTENT_TYPE`）。
4. 返回 `FileUploadVo.of(url, bucket, objectKey, 原文件名, 字节数)`。

> **小文件不写 `sys_file`**：直接透传 OSS 并返回 URL，只有大文件合并成功后才落库（`sys_file` 目前为空表即由此而来）。

### 8.2 大文件分片（断点续传）

```text
1) POST /file/chunk/init   → 校验（>1 片时单片需 >= 5MB）
                             → 任务已存在：复用 uploadId，返回 uploadedChunks（断点续传）
                             → 否则申请 uploadId，写 Redis Hash meta（文件名/扩展名/大小/总分片/uploadId/objectKey/bucket）
                             → 返回 FileChunkInitVo(uploadId, 已上传分片)
2) POST /file/chunk/upload → 校验任务存在；partNumber = chunkIndex + 1（对外 0-based，OSS 1-based）
                             → 非最后一片需 >= 5MB；上传分片并把 etag 写入 parts Hash；meta/parts 续期
3) POST /file/chunk/merge  → 校验分片数 == totalChunks（否则报「已上传 x/y 片」）
                             → 按分片序号升序组装 PartETag → completeMultipartUpload
                             → SysFile.of(fileName, extName, 默认 ossName, objectKey, bytesToKb(大小),
                                          LARGE_FLAG_YES, STATUS_COMPLETED) 落库
                             → 删除 Redis 的 meta 与 parts
```

Redis key：`FileConst.chunkMetaKey(fileMd5)`（Hash meta）、`FileConst.chunkPartsKey(fileMd5)`（Hash partNumber → etag）；过期 `FileConst.CHUNK_EXPIRE_SECONDS`（24 小时）。`bytesToKb` 为**向上取整**。

## 9. 启动初始化链路

```text
App.main
  └─ Spring 容器启动
       ├─ OssConfig       → 构建 DsOssProxy（按 ds.oss.tx-oss 注册 bucket）
       ├─ AuthJwtProp     → @ConfigurationProperties("auth-jwt") 绑定 JWT 配置（TokenUtil 静态取用）
       ├─ ThreadPoolConfig→ micorTaskPool / micorSchedulePool
       ├─ RequestInitFilter / AuthFilter → 注册为 Servlet 过滤器（RequestInitFilter 顺序最前）
       ├─ 各 CacheService @PostConstruct → cacheManager.getOrCreateCache(QuickConfig)
       └─ RbacJob @PostConstruct init() → 分布式标记 + 全量刷新配置/字典/API
```
