# api.md — REST 接口清单与约定

> 本文件回答「**有哪些接口、路径叫什么、入参出参用什么类型**」。
> 接口自动同步的**流程**见 [flows.md](flows.md) 第 7 节；`sys_api` 表结构见 [database.md](database.md)。

## 1. 路径约定

| 操作 | HTTP | 路径 |
|------|:----:|------|
| 编辑（新增/修改） | POST | `/edit` |
| 分页列表 | GET | `/find/pageList` |
| 列表 | GET | `/find/list` |
| 树形列表 | GET | `/find/treeList` |
| 详情 | GET | `/find/detail/{id}` |
| 批量删除 | DELETE | `/delete/batch` |
| 启用/停用 | POST | `/enableOpt/{id}` |
| 资源/角色授权 | POST | `/authResource`、`/authRole` |

约定：

- 后台管理接口基础路径 `/backSysXxx`；通用（前台/公共）接口 `/commonXxx`。
- 分页返回 `R<TableData<XxxListVo>>`；其它返回 `R<Void>` 或 `R<XxxVo>`。
- 批量删除入参统一 `BatchDeleteDto`（含 `idList`）。

### 1.1 请求头要求（接口鉴权已启用）

| 请求头 | 必填性 | 说明 |
|--------|--------|------|
| `Authorization` | 业务接口必填 | 值形如 `Bearer <token>`；由 `POST /backAuth/webLogin` + `POST /commonAuth/selectRole` 获取 |
| `Api-Code` | 业务接口必填 | 接口编码（`sys_api.api_code`），鉴权时据此定位接口配置并校验路径与角色授权 |

- **未携带 `Api-Code` 的请求**：只有命中免鉴权白名单（`api-config.whitePaths`）才放行，否则返回 `400 缺少接口编码请求头[Api-Code]`。
- **角色为 `SUPER_ADMIN`** 时跳过全部鉴权，无需携带 `Api-Code`。
- 新增匿名接口（无需登录即可访问）时，需在 `application.yml` 的 `api-config.whitePaths` 中补充路径。
- 鉴权完整流程与失败码见 [flows.md](flows.md) 第 3 节。

## 2. 控制器端点清单

| 控制器 | 基础路径 | 端点 |
|--------|----------|------|
| `LoginController` | `/backAuth` | `POST /webLogin` |
| `AuthController`（通用） | `/commonAuth` | `GET /getUserInfo`、`POST /selectRole`、`GET /code`、`GET /find/userAuthTree` |
| `SysCommonController`（通用） | `/commonSys` | `GET /find/dictList`、`/find/dictItemList`、`/find/configValue`、`/find/dirSelectList`、`/find/apiSelectList` |
| `SysUserController` | `/backSysUser` | `edit`、`updatePwd`、`updateInfo`、`resetPwd/{userId}`、`find/bgPageList`、`find/fgPageList`、`find/bgList`、`find/fgList`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}`、`authRole`、`find/userRoles/{userId}`、`find/userPlatforms/{userId}` |
| `SysRoleController` | `/backSysRole` | `edit`、`find/pageList`、`find/list`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}`、`authResource`、`find/roleResources/{roleId}`、`refreshPermission` |
| `SysResourceController` | `/backSysResource` | `edit`、`find/list`、`find/treeList`、`find/simpleTreeList`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}` |
| `SysApiController` | `/backSysApi` | `edit`、`find/pageList`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}` |
| `SysConfigController` | `/backSysConfig` | `edit`、`find/pageList`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}` |
| `SysDictController` | `/backSysDict` | `edit`、`find/pageList`、`delete/batch`、`find/detail/{id}` |
| `SysDictItemController` | `/backSysDictItem` | `edit`、`find/pageList`、`delete/batch`、`find/detail/{id}`、`enableOpt/{id}` |
| `SysDirController` | `/backSysDir` | `edit`、`find/list`、`delete/batch`、`find/detail/{id}` |
| `DsController` | `/backDs` | `POST /flushApis`、`GET /find/apis` |
| `FileController` | `/file` | `POST /upload`、`POST /chunk/init`、`POST /chunk/upload`、`POST /chunk/merge` |

## 3. 入参 / 出参对照

| 端点 | 请求体 / 参数 | 返回 |
|------|---------------|------|
| `POST /backAuth/webLogin` | `LoginDto`（用户名、密码、验证码、sessionId、平台编码） | `R<LoginVo>` |
| `POST /commonAuth/selectRole` | `SelectRoleRo` | `R<LoginVo>` |
| `GET /commonAuth/code` | — | `R<CaptchaVo>` |
| `GET /commonAuth/getUserInfo` | — | `R<UserInfoCacheDto>`（**缓存 Dto，非 Vo**） |
| `GET /commonAuth/find/userAuthTree` | — | `R<List<SysResourceTreeNodeVo>>` |
| `GET /commonSys/find/dictItemList` | `CommonDictItemQueryRo` | `R<Map<String,Map<String, SysDictItemCacheDto>>>`（**Map 形态**） |
| `GET /commonSys/find/configValue` | `CommonConfigQueryRo` | `R<SysConfigCacheDto>` |
| `POST /backSysXxx/edit` | `SysXxxEditDto` | `R<Void>` |
| `GET /backSysXxx/find/detail/{id}` | `@PathVariable` | `R<XxxListVo>` / `R<XxxNodeVo>` |
| `DELETE /backSysXxx/delete/batch` | `BatchDeleteDto` | `R<Void>` |
| `POST /backSysRole/authResource` | `AuthResourceRo`（roleId + resourceIds） | `R<Void>` |
| `POST /backSysUser/authRole` | `AuthRoleRo`（userId + roleIds） | `R<Void>` |
| `POST /backSysRole/refreshPermission` | `RefreshPermissionRo` | `R<Void>` |
| `GET /backDs/find/apis` | — | `R<List<DsSpringApiVo>>` |
| `POST /backDs/flushApis` | — | `R<List<DsSpringApiVo>>` |

### 3.1 关键接口行为约定

| 接口 | 行为 |
|------|------|
| `POST /backSysApi/edit` | **不支持新增**：接口由 `flushApis` 自动同步产生，主键为空直接抛 `BAD_REQUEST`「不支持新增」；只允许改名/改编码与其它字段、启停、删除 |
| `POST /backSysRole/authResource` | 资源授权为**全量覆盖**（先删后插）；**取消全部授权须传空数组 `[]`**，传 `null` 会 NPE（`resourceIds` 未加 `@NotEmpty`） |
| `POST /backSysUser/authRole` | 同上，`roleIds` 为全量覆盖，取消全部角色传 `[]` |
| `GET /backSysDir/find/list` | 返回**平铺列表**（不组树），前端自行按 `parentDirId` 组装 |
| `POST /backSysDictItem/edit` | 字典项编码与字典编码**不可编辑**（Service 内显式置 `null`，`updateById` 不更新这两列） |
| `POST /backSysConfig/edit`、`POST /backSysDict/edit` | 配置编码 / 字典编码**不可编辑**，同理由 Service 置 `null` 跳过更新 |
| `POST /backSysRole/refreshPermission` | 按「平台编码 + 角色编码」刷新角色授权接口缓存 |

## 4. 已知的接口形态例外（现状，勿当规范模仿）

| 位置 | 例外形态 |
|------|----------|
| `AuthController.getUserInfo` | 直接返回缓存 Dto `UserInfoCacheDto`，不经 Service/Vo |
| `AuthController.code()` | 在 Controller 内直接注入 `RedissonClient` 写验证码缓存 |
| `SysCommonController.dictItemList` | 直接调 `SysCacheUtil.dictItemCache()` 并返回 `Map<String,Map<String, SysDictItemCacheDto>>` |
| `SysCommonController.configValue` | 直接调 `SysCacheUtil.configCache()` 并返回 `SysConfigCacheDto` |

`controller/common` 下的通用只读接口是**既有设计**，允许越过 Service 层与 Vo；其余 Controller 必须 `Controller → Service → Mapper` 且返回 Vo。

## 5. 接口自动同步（`sys_api`）

- 触发：`POST /backDs/flushApis`（`DsServiceImpl.flushApis`，`@Transactional`）；查看：`GET /backDs/find/apis`。
- 语义：**只插入缺失，不更新既有**。判定「一致」的条件、白名单、`apiStyle` 推导等细节见 [flows.md](flows.md) 第 7 节。
- 新接口默认 `enableFlag=N`、`apiCode`/`apiName` = `DsConst.AUTO_API_PREFIX`（`自动生成_`）+ 雪花 ID。
- 白名单字典：`DsConst.WITHOUT_FLUSH_API_CODE`。
