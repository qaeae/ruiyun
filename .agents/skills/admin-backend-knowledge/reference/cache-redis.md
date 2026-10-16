# cache-redis.md — 缓存与 Redis 明细

> 本文件回答「**缓存怎么配、key 长什么样、有哪些读写入口、Redis 怎么用**」。
> 强制规则见 [../SKILL.md](../SKILL.md) 第 8、9 节；刷新的代码时序见 [flows.md](flows.md) 第 6 节。

## 1. jetCache 全局配置（`application.yml`）

```yaml
jetcache:
  statIntervalMinutes: 15
  areaInCacheName: false
  local:
    default:
      type: caffeine
      limit: 10000
      keyConvertor: fastjson2
      expireAfterWriteInMillis: 100000
  remote:
    default:
      type: redisson
      keyConvertor: fastjson2
      broadcastChannel: projectDS
      valueEncoder: bean:fastjson2ValueEncoder
      valueDecoder: bean:fastjson2ValueDecoder
```

- 本项目**只用编程式 API**（`CacheManager.getOrCreateCache(QuickConfig)`），不依赖 `@Cached` 注解（`@EnableMethodCache` 虽开启但无注解使用）。
- 缓存一律 `CacheType.BOTH`：本地 caffeine + 远端 Redis；`broadcastChannel: projectDS` 用于本地缓存失效广播。

## 2. `CacheConst` — QuickConfig 与 key

位置：`com.ds.commonService.pojo.sysConst.CacheConst`。

| 成员 | 缓存名（含 hash tag） | 类型 | 本地 | 远端 | 缓存空值 | 其它 |
|------|----------------------|------|------|------|----------|------|
| `SYS_API_CACHE` | `{RBAC}:sysApiCache` | BOTH | 1 天，limit `Integer.MAX_VALUE` | 30 天 | 否 | `penetrationProtect=true`、`syncLocal=true` |
| `SYS_API_ROLE_CACHE` | `{RBAC}:sysApiRoleCache:` | BOTH | 5 分钟，limit 512 | 1 天 | 是 | `syncLocal=true` |
| `USER_INFO_CACHE` | `{RBAC}:userInfo:` | BOTH | 5 分钟，limit 1024 | 30 分钟 | 否 | `penetrationProtect=true` |
| `SYS_CONFIG_CACHE` | `{sysConfig}:` | BOTH | 5 分钟，limit 2048 | 30 天 | 是 | `syncLocal=true` |
| `SYS_DICT_CACHE` | `{sysDict}:` | BOTH | 5 分钟，limit 2048 | 30 天 | 是 | `syncLocal=true` |

key 拼接方法（`static`）：

| 方法 | 返回值 | 是否含前缀 |
|------|--------|-----------|
| `apiCacheKey()` | `"ALL"` | 否（配合缓存名 `{RBAC}:sysApiCache`） |
| `roleAuthApiCacheKey(platformCode, roleCode)` | `{RBAC}:sysApiRoleCache:平台编码:角色编码` | **是（含完整前缀）** |
| `userInfoCacheKey(userId, platformCode)` | `平台编码:用户ID` | 否（前缀由缓存名提供） |
| `dictItemCacheKey(dictCode, dictItemCode)` | `字典编码:字典项编码` | 否（前缀由缓存名提供） |

> 缓存名内嵌 Redis Cluster hash tag（`{RBAC}` / `{sysConfig}` / `{sysDict}`），保证同类 key 落在同一 slot；`roleAuthApiCacheKey` 额外重复拼了一次前缀，属口径不一致（见 [../SKILL.md](../SKILL.md) 现状偏差）。

## 3. 三个缓存 Service

接口在 `common-service`，实现在 `application/service/cache/impl`。

| Service | 读方法 | 写 / 刷新方法 |
|---------|--------|---------------|
| `RbacCacheService` | `getSysApiCache(apiCode)`、`getRoleAuthApi(platformCode, roleCode)`、`loadUserInfo(userId, platform)` | `freshApiCache(Set)`、`removeApiCache(Set)`、`flushSysApiCache()`、`freshRoleAuthApi(platformCode, Set)`、`removeRoleAuthApi(platformCode, Set)`、`updateUserInfo(userId, platform)` |
| `SysConfigCacheService` | `getConfig(configCode[, enableFlag])`、`getConfigValue(configCode[, enableFlag])` | `freshConfig`、`removeConfig`、`freshAllConfig` |
| `SysDictItemCacheService` | `getDictItem`、`getDictItemValue`、`getAllDictItem(Set)`、`getAllDictItemValue(Set)` | `freshDictItem`、`freshAllDictItemByDictCodes`、`removeByDictCodes` |

共同约定：

- 实现类统一 `@PostConstruct` + `cacheManager.getOrCreateCache(CacheConst.XxxQuickConfig)`。
- **点查走缓存、列表直接查库**（`getAllDictItem*` 与配置列表均直查 DB）。
- `enableFlag` 不参与缓存 key，由调用方在内存中过滤；未指定时不过滤。
- 无法注入 Bean 的场景（静态方法 / 工具类 / 框架回调）用 `SysCacheUtil.rbacCache()` / `configCache()` / `dictItemCache()`，取回同一 Bean。
- 未命中回源 SQL：`selectOneApi`、`getSysRoleAuthApi`、`loadUserInfoById`（`SysRbacCommonMapper`）；`selectByCode`（`SysConfigMapper`）；`loadCacheItem`（`SysDictItemMapper`）。

## 4. 全量刷新 `RbacJob`

| 时机 | 动作 |
|------|------|
| 启动 `@PostConstruct init()` | `bucket("SYSTEM_RESOURCE_INIT_LOCK").setIfAbsent("", 12h)` 抢标记 → 抢到则依次 `flushConfig()` → `flushDict()` → `flushApiCache()`；失败则删标记并抛出 |
| 每日 `02:00` | `flushConfig()` → `SysConfigCacheService.freshAllConfig()` |
| 每日 `02:01` | `flushDict()` → `SysDictMapper.selectAllCacheFlushCode()` → `freshAllDictItemByDictCodes(...)` |
| 每日 `02:02` | `flushApiCache()` → `RbacCacheService.flushSysApiCache()` |

手动刷新角色权限：`POST /backSysRole/refreshPermission`。

## 5. Redis / Lua

- 统一 `RedissonClient`（**禁用 `RedisTemplate`**）：字符串 `getBucket`、计数 `getAtomicLong`、Hash `getMap`、分布式标记 `getBucket().setIfAbsent`、脚本 `getScript()`。
- Redisson 配置（`redisson-loc.yaml`）：`codec: JsonJacksonCodec`、`address: redis://127.0.0.1:6379`、`database: 7`。
- **Lua 脚本集中**在 `com.ds.single.pojo.sysConst.LuaRedisScript`，禁止在业务类内联脚本字符串。

调用样例：

```java
redissonClient.getScript().eval(RScript.Mode.READ_WRITE, LuaRedisScript.INCREMENT_FAIL_SCRIPT,
        RScript.ReturnType.INTEGER, Collections.<Object>singletonList(lockKey),
        TimeUnit.MINUTES.toMillis(lockMinutes));
```

参数约定：

- `KEYS[i]` 传 Redis key；`ARGV[i]` 传参数，**时间统一毫秒**。
- **`ARGV` 必须传数值类型（`Long` / `Integer`）**：当前 codec 为 `JsonJacksonCodec`，传 `String` 会被编码成带引号的 `"600000"`，导致 `PEXPIRE` / `EXPIRE` 解析失败。

现有脚本 `INCREMENT_FAIL_SCRIPT`（登录失败计数，`INCR` + 首次 `PEXPIRE`）：

```lua
local count = redis.call('INCR', KEYS[1]);
if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end;
return count;
```

其它 Redis key 来源：

| key | 常量 / 方法 | 用途 |
|-----|-------------|------|
| `login:fail:<username>` | `AuthConst.loginFailKey(username)` | 登录失败计数 |
| `captcha:<sessionId>` | `AuthConst.captchaKey(sessionId)` | 验证码（TTL `AuthConst.CAPTCHA_EXPIRE_MINUTES` = 5 分钟） |
| `file:chunk:<md5>` / `file:chunk:<md5>:parts` | `FileConst.chunkMetaKey` / `chunkPartsKey` | 分片上传任务与分片列表 |
| `SYSTEM_RESOURCE_INIT_LOCK` | `RbacJob` 类内常量 | 启动初始化分布式标记（**应移至常量类**） |
