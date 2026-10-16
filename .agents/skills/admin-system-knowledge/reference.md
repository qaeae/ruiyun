# 参考手册（admin-system-knowledge）

本文件是知识库的**参考资料**：目录结构、命名表格、模块/页面/接口/模型清单、交互速查、测试与提交规范。
强制规则集中在 [PROJECT-STANDARD.md](PROJECT-STANDARD.md)（项目整体规范）与 [page-standard.md](page-standard.md)（页面书写细则），本文件不重复规范条文。

## 目录结构

- `src/api/index.ts` — 从 `src/api/modules/` 重新导出类型化 API 客户端（`auth.ts`、`user.ts`、`role.ts`、`resource.ts`、`api.ts`、`dict.ts`、`dictItem.ts`、`config.ts`、`dir.ts`、`ds.ts`、`file.ts`）。
- `src/models/` — 所有接口/类与常量统一放这里（不再使用 `src/types/`）：
  - `common.ts` — 通用传输 Dto/Bo + 通用常量（`ApiResponseDto`、`TableDataDto`、`PageQueryDto`、`BatchDeleteBo`、`FLAG`、`SEX`、`FORM_MODE`、`EMPTY_TEXT`、`DEFAULT_PLATFORM_CODE`、`DICT_CODE` 通用字典编码、各 `*_VIEW` 映射与取用函数、`*_OPTIONS` 选项）。
  - `pages/` — 页面模块模型，一个模块一个文件：`auth.ts`、`user.ts`、`role.ts`、`resource.ts`、`api.ts`、`dict.ts`、`dictItem.ts`、`config.ts`、`dir.ts`、`ds.ts`。
  - `components/` — 组件模型（组件 > 页面，组件可引用页面模型）：`fileUpload.ts`、`userSelect.ts`、`icon.ts`、`sidebarMenu.ts`。
  - `index.ts` — 统一导出。
  - `__tests__/models.test.ts` — 模型转换测试。
- `src/components/` — 可复用组件：`ApiSelect/ApiSelectDialog.vue`、`FileUpload/FileUpload.vue`、`IconSelect/IconSelect.vue`、`MenuIcon.vue`、`SidebarMenuItem.vue`、`UserSelect/UserSelectDialog.vue`。
- `src/config/index.ts` — 全局配置 `APP_CONFIG`（系统名称、分页大小/选项、Token key、请求超时等）。
- `src/layouts/DefaultLayout.vue` — 登录后外层布局（侧边栏 + 顶栏 + 主内容 + 个人信息/修改密码弹框）。
- `src/router/index.ts` — 静态路由、`componentMap` 组件注册、`WHITE_LIST` 白名单、`preRegisterRoutes()` 动态路由预注册、路由守卫（鉴权 + 动态路由注册）。
- `src/stores/` — Pinia 状态（`appStore.ts`、`dictStore.ts`、`iconStore.ts`、`pageQueryStore.ts`）。
- `src/styles/`、`src/utils/` — 全局 SCSS；工具函数（`request.ts`、`dirTree.ts`、`resourceTree.ts`、`md5.ts`、`message.ts`、`permission.ts`、`storage.ts`、`loading.ts`）。
- `src/views/` — 页面：`dashboard/index.vue`、`login/index.vue`、`system/SysUser.vue`、`SysRole.vue`、`SysResource.vue`、`SysResourceEdit.vue`、`SysApi.vue`、`SysDict.vue`、`SysDictItem.vue`、`SysConfig.vue`、`SysDir.vue`。
- `src/auto-imports.d.ts`、`src/components.d.ts` — `unplugin-auto-import` / `unplugin-vue-components` 生成声明文件（自动导入 vue/vue-router/pinia 与 Element Plus 组件）。
- `public/icons/svg/*.svg` — 图标库源文件；`public/icons/list.json` — 生成的图标清单（`GET /icons/list.json` 静态接口）。
- `scripts/buildIcons.mjs` — 扫描 SVG 生成图标清单（`pnpm build:icons`，`predev`/`prebuild` 自动执行）。
- 根目录配置：`.env.dev` / `.env.sit` / `.env.uat` / `.env.production`、`vite.config.ts`、`tsconfig.json`、`index.html`、`package.json`、`接口文档.md`（权威接口文档）、`AGENTS.md`。

## 文件命名（速查，完整规则见 PROJECT-STANDARD 第三章）

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 页面 | PascalCase（业务域目录下） | `src/views/system/SysUser.vue`、`src/views/login/index.vue` |
| 组件 | PascalCase（同名目录） | `src/components/FileUpload/FileUpload.vue` |
| 模型文件 | camelCase | `pages/user.ts`、`components/fileUpload.ts` |
| API 客户端 | camelCase | `api/modules/user.ts` |
| Store 文件 | camelCase + `Store` | `appStore.ts`、`dictStore.ts` |
| 工具/样式/配置 | camelCase / kebab-case | `request.ts`、`global.scss`、`vite.config.ts` |

## models 命名规范（速查，完整规则见 PROJECT-STANDARD 第三章）
> 类书写、文件结构顺序、常量形态与模块模板见 [models-standard.md](models-standard.md)。

| 后缀 | 用途 | 典型示例 |
|------|------|----------|
| `Qo` | 只做查询的类或接口，用作表单查询 | `SysUserQueryQo`、`SysDictPageQueryQo` |
| `Vo` | 只做页面回显的接口和类，用作页面数据显示 | `SysUserListVo`、`SysResourceTreeNodeVo`、`CaptchaVo` |
| `Bo` | 只用于数据提交的接口和类，用于非查询表单的接口调用 | `SysUserEditBo`、`BatchDeleteBo`、`LoginBo` |
| `Dto` | 前端内部使用的传输通用接口和类，用于复用公用数据 | `SysDictItemCacheDto`、`PageQueryDto`、`UserInfoCacheDto` |

目录规则、`.vue` 禁写 interface/class 等强制要求见 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 第四章。

## 代码内命名（速查）

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 变量 | camelCase | `tableData`、`isLoading`、`pageSize` |
| 常量（模块级/全局） | UPPER_SNAKE_CASE | `API_BASE_URL`、`RESOURCE_TREE_BUTTON_INDENT` |
| 函数/方法 | camelCase，行为前缀 | `fetchList`、`handleSubmit`、`getApiStyleText` |
| 类型/接口 | PascalCase + Qo/Vo/Bo/Dto 后缀（不使用 `I` 前缀） | `SysUserListVo`、`ApiResponseDto` |
| 路由路径 | kebab-case | `/system/resource`、`/system/resource/edit` |
| CSS 类名 | kebab-case | `.search-card`、`.table-toolbar` |
| CSS 变量 | `--kebab-case` | `--app-select-width` |

## 命名前缀（速查）

| 前缀 | 用途 | 示例 |
|------|------|------|
| `use` | 组合式函数 / store hook | `useAppStore`、`usePageQueryStore` |
| `is` / `has` / `should` | 布尔值 | `isDetail`、`hasPermission`、`shouldDel` |
| `handle` | 事件处理器 | `handleSearch`、`handleEditSubmit`、`handleEnableToggle` |
| `fetch` | 获取数据 | `fetchList`、`fetchTree` |
| `get` / `set` | 读取 / 设置 | `getEnableFlagView`、`setUserInfo` |
| `clear` / `reset` | 清除 / 重置 | `clearIcon`、`reset` |
| `to` | 模型转换方法 | `toParams`、`toFormData`、`toQueryRo` |
| `El` / `define` | Element Plus 组件 / Vue 宏 | `ElButton`、`defineProps` |

## TypeScript 规则（速查，完整规则见 PROJECT-STANDARD 第五章）

- 对象结构用 `interface`；联合类型/工具类型用 `type`；类型导入用 `import type`。
- 不使用 `enum`、`namespace`、装饰器；用 `const` 对象（`as const`）+ 派生联合类型替代 `enum`。
- 字典值字段（`enableFlag`、`sex`、`resourceType`、`apiStyle` 等）在 `interface`/`class` 中只声明对应数据类型（`string`），不写死字面量联合类型；代码值用常量赋值与比较。
- 字面量类型必须**命名后引用**（如 `FlagValue`、`SexValue`、`SortOrder`、`TagType`、`ResourceType`、`ApiStyle`、`FileListType`、`UserQueryType`），不在属性/参数/映射 key 上内联书写。详见 [models-standard.md](models-standard.md) 第七章。
- `.vue` 中禁止定义 `interface`/`class`。
- 完整规则见 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 第六章。

## 注释规范（速查，完整规则见 PROJECT-STANDARD 第九章）

每个状态/方法用 `/** ... */` 说明，方法体内按执行顺序写 `// 1.` `// 2.` `// 3.` 步骤注释（复杂步骤用 `// 2.1`）。
完整规则与示例见 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 第十章。

## API 模块清单

| 模块 | 文件 | 主要接口 |
|------|------|----------|
| 认证 | `src/api/modules/auth.ts` | `getCaptcha`、`login`、`selectRole`、`getUserInfo` |
| 用户 | `src/api/modules/user.ts` | `edit`、`bgPageList`、`fgPageList`、`bgList`、`fgList`、`detail`、`deleteBatch`、`enableOpt`、`authRole`、`getUserRoles`、`getUserPlatforms`、`updatePwd`、`updateInfo`、`resetPwd` |
| 角色 | `src/api/modules/role.ts` | `edit`、`pageList`、`list`、`detail`、`deleteBatch`、`enableOpt`、`authResource`、`getRoleResources`、`refreshPermission` |
| 资源 | `src/api/modules/resource.ts` | `edit`、`pageList`、`list`、`treeList`、`simpleTreeList`、`detail`、`deleteBatch`、`enableOpt`、`getUserAuthTree` |
| 接口 | `src/api/modules/api.ts` | `edit`、`pageList`、`list`、`detail`、`deleteBatch`、`enableOpt` |
| 字典 | `src/api/modules/dict.ts` | `edit`、`pageList`、`list`、`detail`、`deleteBatch` |
| 字典项 | `src/api/modules/dictItem.ts` | `edit`、`pageList`、`dictItemList`（批量字典缓存）、`detail`、`deleteBatch`、`enableOpt` |
| 配置 | `src/api/modules/config.ts` | `edit`、`pageList`、`configValue`、`detail`、`deleteBatch`、`enableOpt` |
| 目录 | `src/api/modules/dir.ts` | `edit`、`list`、`dirSelectlist`、`detail`、`deleteBatch` |
| 文件 | `src/api/modules/file.ts` | `upload`、`chunkInit`、`chunkUpload`、`chunkMerge` |
| ds 框架 | `src/api/modules/ds.ts` | `flushApis` |

## 模型清单（`src/models/`）

| 文件 | 主要类/接口 |
|------|--------|
| `common.ts` | `ApiResponseDto`、`TableDataDto`、`PageQueryDto`、`BatchDeleteBo`、`FlagViewDto`、`FlagToggleViewDto`、`FormMode`、`FormSubmitMode`、`ButtonType` |
| `pages/auth.ts` | `CaptchaVo`、`LoginVo`、`LoginSelectRoleVo`、`UserInfoCacheDto`、`LoginBo`、`LoginSubmitBo`、`SelectRoleBo` |
| `pages/user.ts` | `SysUserVo`、`SysUserListVo`、`SysUserRoleRelVo`、`SysUserPlatformVo`、`SysUserQueryQo`、`SysUserEditBo`、`UserRoleAuthBo`、`SysUserUpdatePwdBo`、`SysUserUpdateInfoBo` |
| `pages/role.ts` | `SysRoleVo`、`SysRoleListVo`、`SysRoleResourceRelVo`、`SysRoleQueryQo`、`SysRoleEditBo`、`RoleResourceAuthBo`、`RoleRefreshPermissionBo` |
| `pages/resource.ts` | `ResourceType`、`SysResourcePageQueryQo`、`SysResourceVo`、`SysResourceListVo`、`SysResourceExtConfigInfoDto`、`SysResourceExtBo`、`SysResourceExtVo`、`SysResourceApiOverviewVo`、`SysResourceTreeNodeVo`、`SysResourceQueryQo`、`SysResourceEditBo`、`SysResourceChannelExtDto`、`ResourceSimpleTreeQueryQo` |
| `pages/api.ts` | `ApiStyle`、`SysApiVo`、`SysApiListVo`、`SysApiQueryQo`、`SysApiEditBo` |
| `pages/dict.ts` | `SysDictPageQueryQo`、`SysDictVo`、`SysDictListVo`、`SysDictSelectListVo`、`SysDictQueryQo`、`SysDictEditBo` |
| `pages/dictItem.ts` | `SysDictItemVo`、`SysDictItemListVo`、`SysDictItemCacheDto`、`SysDictItemCacheMap`、`SysDictItemQueryQo`、`SysDictItemBatchQueryQo`、`SysDictItemEditBo` |
| `pages/config.ts` | `SysConfigVo`、`SysConfigListVo`、`SysConfigQueryQo`、`SysConfigEditBo` |
| `pages/dir.ts` | `SysDirVo`、`SysDirListVo`、`SysDirSelectVo`、`SysDirTreeNodeVo`、`SysDirQueryQo`、`SysDirEditBo` |
| `pages/ds.ts` | `DsSpringApiVo` |
| `components/fileUpload.ts` | `FileUploadVo`、`FileChunkInitVo`、`FileUploadBo`、`FileChunkInitBo`、`FileChunkUploadQueryQo`、`FileChunkMergeBo` |
| `components/userSelect.ts` | `UserSelectQueryQo` |
| `components/icon.ts` | `IconVo`、`IconMap` |
| `components/sidebarMenu.ts` | `SidebarMenuItemVo`、`RouteConfigDto` |

## 常量清单（模板取值来源）

| 位置 | 常量 / 取用函数 |
|------|----------------|
| `models/common.ts` | `FLAG`、`SEX`、`FORM_MODE`、`EMPTY_TEXT`、`DEFAULT_PLATFORM_CODE`、`DICT_CODE`、`ENABLE_FLAG_VIEW`、`ENABLE_FLAG_TOGGLE`、`VISIT_FLAG_VIEW`、`AUTH_CHECK_VIEW`、`FORM_SUBMIT_RESULT`、`FORM_FOOTER_TEXT`、`getEnableFlagView`、`getEnableFlagToggle`、`getVisitFlagView`、`getAuthCheckView`、`getFormTitle`、`getFormFooterText`、`getSubmitResultText`、`ENABLE_FLAG_OPTIONS`、`YES_NO_OPTIONS`、`VISIT_FLAG_OPTIONS` |
| 命名类型（`common.ts`） | `FlagValue`、`SexValue`、`FormMode`、`FormSubmitMode`、`SortOrder`、`TagType`、`ButtonType` |
| 命名类型（业务/组件） | `ResourceType`、`ApiStyle`、`ResourceTypeStyle`（`pages/resource.ts`）、`FileListType`（`components/fileUpload.ts`）、`UserQueryType`（`components/userSelect.ts`） |
| `models/pages/resource.ts` | `RESOURCE_TYPE`（代码值）、`RESOURCE_TYPE_STYLE`（样式）、`ResourceTypeStyle`、`getResourceTypeStyle`、`getResourceTreeIndent`、`RESOURCE_TYPE_DICT_CODE`（字典编码）、`RESOURCE_FORM_TITLE`、`RESOURCE_TREE_BUTTON_INDENT`、`RESOURCE_TREE_BASE_INDENT` |
| `models/pages/api.ts` | `API_STYLE`、`API_STYLE_VIEW`、`API_STYLE_OPTIONS`、`LOG_FLAG_OPTIONS`、`getApiStyleText`、`API_FORM_TITLE`、`API_ROW_CLASS_SHOULD_DEL` |
| `models/pages/user.ts` | `USER_QUERY_TYPE`、`USER_PASSWORD_PLACEHOLDER`、`USER_FORM_TITLE`（性别名称走 `SEX` 字典） |
| `models/pages/auth.ts` | `DEFAULT_LOGIN_USERNAME`、`LOGIN_BUTTON_TEXT` |
| `models/pages/dict.ts` / `dictItem.ts` / `config.ts` / `role.ts` / `dir.ts` | `DICT_FORM_TITLE`、`DICT_ITEM_FORM_TITLE`、`CONFIG_FORM_TITLE`、`ROLE_FORM_TITLE`、`DIR_FORM_TITLE`；`DIR_TYPE` |

## 页面与组件清单

- 页面：`login/index.vue`、`dashboard/index.vue`、`system/SysUser.vue`、`SysRole.vue`、`SysResource.vue`、`SysResourceEdit.vue`、`SysApi.vue`、`SysDict.vue`、`SysDictItem.vue`、`SysConfig.vue`、`SysDir.vue`
- 组件：`SidebarMenuItem.vue`（递归菜单）、`MenuIcon.vue`（按 iconCode 渲染内联 SVG）、`IconSelect/IconSelect.vue`（图标选择器）、`FileUpload/FileUpload.vue`（小文件直传 + 分片断点续传）、`ApiSelect/ApiSelectDialog.vue`（接口选择弹框）、`UserSelect/UserSelectDialog.vue`（用户选择弹框）
- 路由注册：`componentMap`（`SysUser`/`SysRole`/`SysResource`/`SysApi`/`SysDict`/`SysDictItem`/`SysConfig`/`SysDir`）；静态路由含 `/login`、`/dashboard`、`/system/resource/edit`；`PAGE_ROUTE_FALLBACK` 在 `resourceTree.ts` 中提供已知页面兜底路径

## 关键交互约定速查（R5–R11）

| 约定 | 说明 | 涉及位置 |
|------|------|----------|
| 字典批量加载 | 页面 `onMounted` 中调用 `dictStore.loadDictItems([...])`；先取本地缓存，仅请求缺失的字典编码；模板用同步 getter（`getDictItemNameSync` / `getDictItemOptions`）渲染 | `src/stores/dictStore.ts`、各页面 |
| 字典内容禁止硬编码 | 可由字典管理维护的名称（性别 `SEX`、资源类型 `RESOURCE_TYPE`）不得在 models 写文案，只保留代码值与样式常量 | `models/common.ts`、`models/pages/resource.ts` |
| 图标库 | 图标经 `GET /icons/list.json` 提供给 Web/App；新增图标在 `public/icons/svg/` 加 `{iconCode}.svg` 并补 `iconNames`，执行 `pnpm build:icons` | `public/icons/`、`scripts/buildIcons.mjs`、`iconStore.ts` |
| 页面跳转保留查询条件 | 跳转前 `pageQueryStore.saveQuery(key, 快照)`，返回 `onMounted` 时 `takeQuery(key)` 恢复并按原条件查询 | `src/stores/pageQueryStore.ts`、列表页 |
| 平台切换刷新 | 搜索栏平台选择切换后自动刷新列表（分页列表重置到第 1 页） | `SysResource.vue`、`SysRole.vue` |
| 请求头与鉴权 | 每次请求必须传 `apiCode`（`Api-Code` 头）；401 清 token 跳转 `/login?redirect=...` | `src/utils/request.ts` |
| 动态路由预注册 | `main.ts` 先 `preRegisterRoutes()` 再安装 router，避免刷新时 `No match found for location` | `src/main.ts`、`src/router/index.ts` |
| 图标字段 | 资源树 DIR/PAGE 存 `iconCode`（后端列 `icon_code`），Web/App 按编码匹配渲染 | `models/pages/resource.ts`、`SysResourceEdit.vue` |
| 操作幕布 | 删除/提交/启停/授权/刷新/重置密码等写操作统一 `withMask(() => xxxApi.yyy(...), '提交中...')` | `src/utils/loading.ts`、各页面写方法、`DefaultLayout.vue` |
| 页面/组件初始加载 | 页面 `loading = ref(true)` + 根容器 `v-loading`，`onMounted` 资源与首屏列表 `try/finally` 收尾后才关闭；组件打开即加载态 | 各列表页、`SysResourceEdit.vue`、`UserSelectDialog.vue`、`ApiSelectDialog.vue`、`IconSelect.vue` |
| 模板魔法值 | 模板禁止业务字面量；通用映射/选项放 `models/common.ts`，业务映射/选项放 `models/pages/{模块}.ts` | `src/models/common.ts`、`src/models/pages/*.ts` |

## 测试指南

- 框架：Vitest。
- 测试命名为 `*.test.ts` / `*.spec.ts`，就近放置或在 `__tests__/` 下（现有模型测试：`src/models/__tests__/models.test.ts`）。
- 使用 `pnpm test` 运行（非 watch 可用 `pnpm exec vitest run`）。

## 提交与 PR 规范

- 提交信息保持简短、祈使语气（如 `init`、`初始化`）。
- PR：描述改动；如有引用了后端尚未实现的 API，需注明（留待后续完成）；必要时附上 UI 截图。
