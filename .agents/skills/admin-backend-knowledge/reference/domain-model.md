# domain-model.md — 领域模型、术语与授权语义

> 本文件回答「**这些实体是什么关系、字段取值什么意思、RBAC 授权在语义上是怎么成立的**」。
> 表与列的物理结构见 [database.md](database.md)；授权/登录的代码流程见 [flows.md](flows.md)。

## 1. 术语表

| 术语 | 含义 |
|------|------|
| **平台（Platform）** | 用户侧访问入口，字典 `PLATFORM` 定义，当前为 `WEB`（管理后台）与 `APP`（移动端）。常量 `PlatformConst.WEB` / `APP`。用户、角色、资源都按平台隔离。 |
| **资源（Resource）** | 权限树与菜单的载体（`sys_resource`），三级：目录 `DIR` → 页面 `PAGE` → 按钮 `BUTTON`。 |
| **资源扩展（ResourceExt）** | 资源按**渠道**维度的前端配置（`sys_resource_ext`，JSON `config_info`），渠道编码即平台编码；结构由字典 `PLATFORM_WEB_RESOURCE_PROP` 定义。 |
| **接口（Api）** | 后端 REST 端点登记项（`sys_api`），含 `api_code`、`api_path`、`api_style`、鉴权/日志开关。 |
| **角色（Role）** | 授权中间层（`sys_role`），属某平台；角色 → 资源（`sys_role_resource_rel`）→ 资源挂载的接口（`sys_resource_api_rel`）。 |
| **资源授权（authResource）** | 给角色勾选可访问资源，**全量覆盖**语义（先删后插）。 |
| **用户授权（authRole）** | 给用户分配角色，全量覆盖语义。 |
| **超管（SUPER_ADMIN）** | 角色编码 `SUPER_ADMIN`（`RbacConst.SUPER_ADMIN`），直接放行平台下全部启用资源，不查授权关系。 |
| **祖先链授权** | 资源可见的必要条件：从该资源到根的**每一级**都必须被角色授权；父级未授权则子级不可见。 |
| **逻辑删除** | `del_flag`：`N` 未删 / `Y` 已删。只存在于 `sys_api`、`sys_config`、`sys_dict`、`sys_dict_item`、`sys_dir`、`sys_resource`、`sys_resource_ext`、`sys_role`。 |
| **启用标识** | `enable_flag`：`Y` 启用 / `N` 停用。用户、角色、资源、接口、配置、字典项均有。 |
| **接口风格（api_style）** | `C` 固定路径 / `P` 路径参数（详见第 4 节口径说明）。 |
| **自动生成接口** | 由 `flushApis` 扫描 Spring MVC 后自动入库的接口，`apiCode`/`apiName` = `自动生成_` + 雪花 ID，默认 `enableFlag=N`。 |
| **刷新白名单** | 字典 `WITHOUT_FLUSH_API_CODE`，命中的 `api_code` 所在路径不参与接口自动插入。 |
| **缓存三件套** | 接口缓存（`apiCode → ApiCacheDto`）、角色授权接口缓存（平台:角色 → apiCode 集合）、用户信息缓存（用户:平台 → `UserInfoCacheDto`）。 |
| **兜底刷新** | 写操作后除立即刷新/失效外，再经 `micorSchedulePool` **延迟 5 秒**刷新一次，保证多实例本地缓存最终一致并避开事务未提交窗口。 |

## 2. 实体关系

### 2.1 授权主链路

```text
sys_user ─┬─ sys_user_platform ──▶ 平台（WEB/APP）：该用户能登录哪些平台
          └─ sys_user_role_rel ── sys_role ── sys_role_resource_rel ── sys_resource（树）
                                                                        └─ sys_resource_api_rel ── sys_api
```

- 一个用户可授权多个平台、多个角色；一个角色属**单一平台**。
- 一个角色可授权多个资源；一个资源可挂多个接口；一个接口可被多个资源挂载。
- 资源树靠 `sys_resource.parent_resource_id` 自关联，深度固定 3 层。

### 2.2 资源与目录体系（两套树，勿混淆）

| 树 | 表 | 自关联列 | 用途 |
|----|----|----------|------|
| **权限/菜单树** | `sys_resource` | `parent_resource_id` | RBAC 授权与用户菜单（`DIR`→`PAGE`→`BUTTON`） |
| **接口目录树** | `sys_dir` | `parent_dir_id` | `sys_api.dir_id` 指向的分类目录，仅用于接口管理界面分组 |

### 2.3 主子关系（「先删后插」全量覆盖的落点）

| 主表 | 子表 | 覆盖时机 |
|------|------|----------|
| `sys_resource` | `sys_resource_ext`（按渠道）、`sys_resource_api_rel`（挂载接口） | `SysResourceServiceImpl.edit` |
| `sys_role` | `sys_role_resource_rel` | `SysRoleServiceImpl.authResource` |
| `sys_user` | `sys_user_platform`、`sys_user_role_rel` | `SysUserServiceImpl.edit` / `authRole` |

### 2.4 字典引用关系

多个业务列以**字典项编码**作为取值来源（列注释均标注「SysDictItem 编码」）：

| 列 | 引用字典 |
|----|----------|
| `sys_user.sex` | `SEX`（`M`/`F`） |
| `sys_user_platform.platform_code`、`sys_role.platform_code`、`sys_resource.platform_code`、`sys_resource_ext.channel_code` | `PLATFORM`（`WEB`/`APP`） |
| `sys_resource.resource_type` | `RESOURCE_TYPE`（`DIR`/`PAGE`/`BUTTON`） |
| `sys_dir.dir_type_code` | `API_DIR_TYPE`（`API_DIR`） |
| `sys_resource_ext.config_info` 的 JSON 结构 | `PLATFORM_WEB_RESOURCE_PROP`（`path`/`componentName`/`h5LinkFlag`） |
| `sys_api.api_code`（白名单跳过判断） | `WITHOUT_FLUSH_API_CODE` |

## 3. RBAC 授权语义（关键）

### 3.1 可见性规则

- 角色返回的资源 = **祖先链每一级都被该角色授权的资源**；父级未授权，子级绝不返回（内存与 SQL 两处实现必须一致，见 [flows.md](flows.md) 第 4 节）。
- `SUPER_ADMIN` 直接返回平台下全部**已启用**资源（`enable_flag=Y`），跳过授权关系查询。

### 3.2 角色与接口的推导

角色 → 资源（`sys_role_resource_rel`）→ 资源挂载的接口（`sys_resource_api_rel`）→ `sys_api.api_code`。

推导出的 `apiCode` 集合即「角色授权接口缓存」，是接口级鉴权的数据基础（`AuthFilter` 对 `auth_check=Y` 的接口校验该集合，见 [flows.md](flows.md) 第 3 节）。

### 3.3 鉴权相关的资源标记

| 字段 | 语义 |
|------|------|
| `sys_api.auth_check` | 是否需要鉴权（`Y`/`N`）；当前仅入库登记，未参与运行时判断 |
| `sys_api.log_req` / `log_resp` | 是否记录请求/响应日志 |
| `sys_resource.visit_flag` | 访问标记（db 无注释，业务未使用，改动前先确认） |
| `sys_resource.icon_code` | 菜单/目录图标 |

## 4. 字段取值口径（易踩坑）

| 字段 | 实际取值 | 说明 |
|------|----------|------|
| `sys_api.api_style` | `C` / `P` | **三方口径不一致**：DB 列注释为 `C-固定路径,P-路径参数,R-正则`；`ApiStyleEnum` 只有 `C("C","普通")`、`P("P","正则")`；`DsServiceImpl.resolveApiStyle` 实际逻辑为「路径同时含 `{` 与 `}` → `P`，否则 `C`」。改动前必须确认以哪一处为准。 |
| `sys_resource.resource_type` | `DIR` / `PAGE` / `BUTTON` | 库中存的是**字典项编码**；`ResourceTypeEnum` 的 `code` 是 `D`/`P`/`B`，且**当前未被业务代码引用**（仅 javadoc 提及）。判断资源类型时请直接比对字符串或字典，勿依赖该枚举的 `code`。 |
| `sys_file.large_flag` | `Y` / `N` | 大文件分片上传标记；常量 `SysFile.LARGE_FLAG_YES` / `LARGE_FLAG_NO` |
| `sys_file.status` | `1` = 上传完成 | 常量 `SysFile.STATUS_COMPLETED` |
| `sys_file.file_size` | 单位 **KB** | 由 `FileServiceImpl.bytesToKb` 换算 |
| `del_flag` | `N` / `Y` | `sys_user`、`sys_file`、`sys_*_rel` **无此列** |

## 5. 种子数据约定

- ID 为 19 位数字字符串，按表分段：`sys_api` 以 `14` 为主（62/74 条）、`sys_resource` 以 `15` 为主（42/44 条），另有少量历史数据落在 `20`、`21` 段。
- 新增数据使用雪花 ID（`IdWorker` / `IdType.ASSIGN_ID`），不沿用种子分段。
