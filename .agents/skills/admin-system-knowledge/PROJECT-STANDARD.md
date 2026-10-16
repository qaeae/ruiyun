# 项目整体规范（PROJECT-STANDARD）

> **定位**：singleProjectForce（`admin-system`）的**唯一权威规范**。全局性强制规则集中在此定义；领域细则由配套文档承载，本文保留要点并给出指向。
>
> **配套文档**（同目录）
> | 文档 | 定位 |
> |---|---|
> | [models-standard.md](models-standard.md) | models 细则：文件结构顺序、类书写、mapia 映射、字段类型、命名类型清单、常量形态、模块模板 |
> | [page-standard.md](page-standard.md) | 页面细则：SFC 与 script 分区、列表页/表单页骨架、样式骨架、页面级交互、页面自检 |
> | [reference.md](reference.md) | 纯参考资料：目录结构、命名表格、API/模型/常量/页面清单、速查表 |
> | [SKILL.md](SKILL.md) | 技能入口：何时使用、文档地图、规则索引、命令、任务索引 |
>
> **冲突处理**：领域细则与本文冲突时，以本文为准；本文未覆盖的细节以领域细则为准。

---

## 〇、强制规则编号索引

代码评审、PR 说明、任务清单可直接引用编号（如「符合 R5」）。

| 编号 | 规则 | 详细章节 |
|---|---|---|
| **R1** | 数据流与请求载荷：载荷必须由模型转换方法产生，禁止内联对象字面量传 API | 四.4.1 |
| **R2** | 模型归属与命名：接口/类/常量统一放 `src/models/`，后缀 Qo/Vo/Bo/Dto；`.vue` 禁止 `interface`/`class` | 三、四.4.2 |
| **R3** | 模型实现：`fill()` 用 mapia；数字字段 `Number(x) \|\| 0`；`toParams()` 仅非空字段 | 四.4.3 |
| **R4** | 请求与接口：每次请求传 `apiCode`；字段以 `接口文档.md` 为准 | 四.4.4 |
| **R5** | 初始加载：页面 `loading = ref(true)` + 根容器 `v-loading`，资源就绪后才关闭；组件打开即加载态 | 七.7.1 |
| **R6** | 操作幕布：所有写操作（删除/提交/启停/授权/刷新/重置）用 `withMask` 包裹 | 七.7.2 |
| **R7** | 常量化：模板禁止业务字面量；字典内容必须走字典，禁止硬编码文案 | 八.8.1、8.2 |
| **R8** | 类型规范：字典值属性只声明数据类型；字面量类型必须命名后引用 | 五.5.2 |
| **R9** | 注释：状态/方法 JSDoc + 方法体 `// 1.` 步骤注释 | 九 |
| **R10** | 路由与权限：`componentMap` 注册、启动预注册、按钮 `hasPermission` | 十 |
| **R11** | 交互约定：字典批量加载、查询条件保留、平台切换刷新、图标库 | 十一 |
| **R12** | 页面书写：SFC 顺序、script 四分区、骨架类名、方法顺序 | 六 |

---

## 一、技术栈与工程约定

| 项 | 约定 |
|---|---|
| 技术栈 | Vue 3 + TypeScript + Vite 6 + Pinia + Vue Router 5 + Element Plus + Axios，包管理 pnpm（包名 `admin-system`） |
| 开发服务器 | 5173 端口；`/api` 代理到 `http://127.0.0.1:8080` 并剥离 `/api` 前缀（`vite.config.ts`） |
| 权威接口文档 | 根目录 `接口文档.md`（后端命名 Ro/Dto/Vo；前端按 Qo/Vo/Bo/Dto 落地） |
| 自动导入 | `unplugin-auto-import`（vue/vue-router/pinia）、`unplugin-vue-components`（Element Plus `El*`），声明文件 `src/auto-imports.d.ts`、`src/components.d.ts`；现有代码以显式 `import` 为主，保持文件内风格一致 |
| 构建分包 | `element-plus`、`vue-vendor` 独立 chunk（`vite.config.ts` 的 `manualChunks`） |
| 环境变量 | `.env.dev` / `.env.sit` / `.env.uat` / `.env.production`（`VITE_API_BASE`、`VITE_APP_TITLE`） |
| 全局配置 | `src/config/index.ts` 的 `APP_CONFIG`（系统名、默认分页、分页选项、请求超时等），禁止在页面散落同类常量 |
| 全局样式类 | `src/styles/global.scss`（如 `.select-sm`）；页面样式一律 `scoped` |

---

## 二、目录与分层职责

```
view/component/store  →  src/models（转换方法）  →  src/api/modules  →  src/utils/request.ts
```

| 目录 | 职责 | 禁止 |
|---|---|---|
| `src/views/` | 页面：布局、交互、编排 | 定义 `interface`/`class`/业务常量；内联对象字面量传 API |
| `src/components/` | 可复用组件 | 同上 |
| `src/layouts/` | 登录后外层框架（侧边栏 + 顶栏 + 主内容 + 个人中心弹框） | 业务规则 |
| `src/models/` | **所有接口、类、常量与映射**：`common.ts`（通用）、`pages/{模块}.ts`、`components/{组件}.ts`、`index.ts`（统一导出） | 引入 `@/api`、store、组件、vue、element-plus |
| `src/api/modules/` | 类型化 API 客户端，只转发已转换的 params/data/FormData | 组装参数、写业务逻辑 |
| `src/stores/` | Pinia 状态（`appStore`、`dictStore`、`iconStore`、`pageQueryStore`） | 直接操作 DOM |
| `src/utils/` | 工具（`request`、`loading`、`message`、`permission`、`storage`、`md5`、`dirTree`、`resourceTree`） | 业务耦合 |
| `src/router/` | 静态路由 + `componentMap` + 守卫 + 动态路由预注册 | 页面内硬编码路由表 |
| `public/icons/` | 图标库源文件与清单 | 手工编辑 `list.json` |
| `scripts/` | 构建脚本（`buildIcons.mjs`） | — |

**依赖方向**：`views/components/stores → models →（无上层依赖）`；`views/components → api → models/utils`；`components/*.ts` 可引用 `pages/*.ts`（**组件 > 页面**），反向禁止。

---

## 三、命名规范（R2）

> 完整表格与速查见 [reference.md](reference.md)「文件命名 / 代码内命名 / 命名前缀」。

### 3.1 文件与目录

| 类型 | 规则 | 示例 |
|---|---|---|
| 页面 | `src/views/{业务域}/{PascalCase}.vue` | `system/SysUser.vue`、`login/index.vue`、`dashboard/index.vue` |
| 页面私有组件 | 同级目录 `{页面名}{用途}.vue` | `ApiSelect/ApiSelectDialog.vue` |
| 组件 | `src/components/{PascalCase}/{PascalCase}.vue` | `FileUpload/FileUpload.vue` |
| 模型 | `src/models/{pages|components}/{camelCase}.ts`，一模块一文件 | `pages/dictItem.ts`、`components/fileUpload.ts` |
| API 客户端 | `src/api/modules/{camelCase}.ts` | `dictItem.ts`、`resource.ts` |
| store / 工具 | `{camelCase}Store.ts` / `{camelCase}.ts` | `dictStore.ts`、`request.ts` |
| 样式 | `src/styles/{kebab-case}.scss` | `global.scss` |

### 3.2 模型后缀（强制）

| 后缀 | 形态 | 用途 | 示例 |
|---|---|---|---|
| `Qo` | 类 / 接口 | 只做查询（查询表单、跨页快照） | `SysUserQueryQo`、`SysDictPageQueryQo` |
| `Vo` | 接口 | 只做页面回显 | `SysUserListVo`、`SysResourceTreeNodeVo` |
| `Bo` | 类 | 只用于数据提交 | `SysUserEditBo`、`BatchDeleteBo` |
| `Dto` | 接口 | 前端内部传输/复用结构 | `SysDictItemCacheDto`、`PageQueryDto` |

### 3.3 代码内命名（强制）

| 类型 | 规则 | 示例 |
|---|---|---|
| 变量 | camelCase | `tableData`、`currentPage`、`editForm` |
| 布尔变量 | `is` / `has` / `should` 前缀 | `isDetail`、`isEdit`、`hasPermission` |
| 方法 | camelCase，行为前缀（`fetch`/`handle`/`get`/`set`/`clear`/`reset`/`to`） | `fetchList`、`handleEditSubmit`、`getEnableFlagView` |
| 模块级常量 | UPPER_SNAKE_CASE | `RESOURCE_TYPE_DICT_CODE`、`API_ROW_CLASS_SHOULD_DEL` |
| 类型/接口 | PascalCase，无 `I` 前缀，带 Qo/Vo/Bo/Dto 后缀 | `SysUserListVo` |
| 命名类型 | PascalCase，语义化（见 5.2） | `FlagValue`、`SortOrder`、`TagType` |
| 路由路径 | kebab-case | `/system/resource`、`/system/resource/edit` |
| CSS 类 / 变量 | kebab-case / `--kebab-case` | `.search-card`、`--app-select-width` |

**禁止** `enum`、`namespace`、装饰器；用 `const` 对象（`as const`）+ 派生类型替代。

---

## 四、数据流与模型规范（强制，R1–R4）

### 4.1 数据流与请求载荷（R1）

1. **v-model 绑定**：对象/数组形态的 `v-model` 状态必须是 `src/models/` 类的实例，如 `reactive(new SysUserQueryQo())`；基础 UI 状态可用类型化 `ref`。
2. **请求载荷**：所有 body / query / `FormData` 必须由模型转换方法产生（`toParams()` / `toFormData()`）。**禁止**内联对象字面量直传 API；非表单请求（批量删除、授权、刷新权限、简略树查询、分片上传、字典/配置缓存查询）同样必须建模。
3. **API 模块**：只转发已转换好的 params/data/FormData，不在内部组装；统一 `export const xxxApi = { ... }` 并在 `src/api/index.ts` 导出。

```typescript
// ✅ 正确
const searchForm = reactive(new SysUserQueryQo())
const editForm = reactive(new SysUserEditBo())
const data = await userApi.bgPageList(searchForm.toParams(currentPage.value, pageSize.value))
await withMask(() => userApi.deleteBatch(new BatchDeleteBo(ids).toParams()))
await withMask(() => roleApi.authResource(authForm.toParams()), '授权中...')

// ❌ 错误
await userApi.edit({ username: editForm.username })
await userApi.deleteBatch({ ids })
```

### 4.2 模型归属与文件划分（R2）

- 所有接口、类、常量与映射放 `src/models/`：通用 → `common.ts`；页面 → `pages/{模块}.ts`；组件 → `components/{组件}.ts`；新增模块必须在 `index.ts` 导出。
- 一模块一文件；统一从 `@/models/index.js` 导入导出；**不使用 `src/types/`**。
- `.vue` 中禁止定义 `interface` / `class` / 业务常量（props 数据形状、快照结构等一律放 models）。
- 依赖方向：models 不引用上层；`components/` 可引用 `pages/`。

### 4.3 模型实现（R3）

- `fill(row)` 必须用 mapia 的 `compileMapper` + `mapOne`；字段改名用 `transformWithRename`，类型/结构转换用 `transform`。
- 数字字段（排序号、数量）后端可能返回字符串 → mapper 中 `Number(x) || 0`，避免 `ElInputNumber` 报 `Invalid prop: type check failed`。
- `toParams()` 只包含非空字段；主键通过参数传入（`toParams(id?)`）。
- `Qo` / `Bo` 提供 `constructor`（默认值）与 `reset()`；`reset()` / 构造函数默认值允许 `Object.assign`。

> 细则（文件结构顺序、类书写、常量形态、命名类型清单、模块模板）见 [models-standard.md](models-standard.md)。

### 4.4 请求与接口对齐（R4）

- 每次请求必须传 `apiCode`（由 `src/utils/request.ts` 统一作为 `Api-Code` 请求头）；响应 `code === 200` 返回 `data`，否则抛 Error；401 清 token 跳 `/login?redirect=...`。
- 字段以根目录 `接口文档.md` 为准；后端 `Ro/Dto/Vo` → 前端 `Qo/Bo/Vo/Dto`；请求字段名（如 `dictItemCodeSet`、`enableFlag`）与后端一致，不随类名调整。
- 列表/详情返回 `XxxListVo` / `XxxTreeNodeVo`（例外：`dir.detail` → `SysDirTreeNodeVo`）；编辑提交由 `XxxEditBo.toParams()` 产出扁平结构。
- 鉴权用户信息字段：`userId`、`roleCodeList`、`roleCode`、`platformCode`、`username`、`userNickname`、`headImgUrl`。
- URL 路径参数（`/detail/{id}`、`/enableOpt/{id}`）可用模板字符串拼接。
- 后端未实现的字段/接口需记录到待补充清单并在 PR 说明。

---

## 五、TypeScript 规范

### 5.1 通则

- 对象结构用 `interface`；联合类型/工具类型用 `type`；类型导入用 `import type`。
- 禁止 `enum` / `namespace` / 装饰器，用 `const` 对象 + `as const` 派生类型。
- `.vue` 中禁止 `interface` / `class`（含 props 数据形状、快照结构）。
- 2 空格缩进、单引号、必要时分号、尾逗号一致。
- 类型检查：`pnpm check`（`vue-tsc --noEmit`，需先安装 `vue-tsc`）；仅校验 `.ts` 时可用 `pnpm exec tsc --noEmit`。

### 5.2 字段类型（强制，R8）

1. **字典值字段只声明数据类型**：`enableFlag`、`visitFlag`、`sex`、`resourceType`、`apiStyle`、`shouldDel`、`logReq`、`logResp`、`h5LinkFlag`、`dirTypeCode` 等统一写 `string`——字典项可由字典管理增减，写死字面量联合会导致类型与数据不一致；代码值用常量（`FLAG.YES`、`SEX.MALE`、`RESOURCE_TYPE.PAGE`）赋值与比较。
2. **字面量类型必须命名后引用**：`interface` / `class` / props / 函数参数 / `Record` key 中不允许内联字面量类型，必须先定义命名类型再引用；优先由常量派生（`type FlagValue = (typeof FLAG)[keyof typeof FLAG]`）。

```typescript
export interface SysUserListVo {
  enableFlag: string        // ✅ 字典值 → 只声明数据类型
  sex?: string              // ✅
  // enableFlag: 'Y' | 'N'  // ❌ 字典项会增减
}

export const ENABLE_FLAG_VIEW: Record<FlagValue, FlagViewDto> = { ... }  // ✅ 引用命名类型
```

**命名类型清单**（新增字面量类型前先查表复用；完整表格见 [models-standard.md](models-standard.md) 第七章）：

| 命名类型 | 取值 | 定义位置 |
|---|---|---|
| `FlagValue` / `SexValue` | `'Y' \| 'N'` / `'M' \| 'F'` | `models/common.ts`（由 `FLAG` / `SEX` 派生） |
| `FormMode` / `FormSubmitMode` | `'add' \| 'edit' \| 'detail'` / `'add' \| 'edit'` | `models/common.ts` |
| `SortOrder` / `TagType` | `'asc' \| 'desc'` / ElTag 类型取值 | `models/common.ts` |
| `ResourceType` / `ApiStyle` | `'DIR' \| 'PAGE' \| 'BUTTON'` / `'C' \| 'P'` | `models/pages/resource.ts`、`api.ts` |
| `FileListType` / `UserQueryType` | 上传列表类型 / `'bg' \| 'fg'` | `models/components/fileUpload.ts`、`userSelect.ts` |

---

## 六、页面与组件书写（R12）

完整骨架、分区顺序与页面自检见 [page-standard.md](page-standard.md)，强制要点：

1. 结构顺序 `script setup` → `template` → `style`；样式一律 `scoped` + `lang="scss"`。
2. `<script setup>` 固定四分区：`// ==================== 页面状态 / 表单验证规则 / 业务方法 / 生命周期 ====================`；import 顺序 `vue → vue-router → 第三方 → @/config → @/api → @/utils → @/stores → @/models（值 → import type）`。
3. 列表页模板骨架固定：`page-container` → `search-card` → `table-card`（`table-toolbar` + `ElTable` + `pagination-wrap`）→ `ElDialog`；类名与样式骨架复用 page-standard 第 6 章。
4. 业务方法固定顺序：`fetchList → handleSearch → handleReset → handlePageChange → handleSizeChange → handleSelectionChange → handleAdd → openDialog → handleDetail → handleEdit → handleEditSubmit →（模块特有）→ handleDelete → handleBatchDelete`。
5. 详情模式复用编辑弹框：`:disabled="isDetail"` + `:rules="isDetail ? {} : editRules"`，标题用页面 `computed`（`formTitle`）。
6. 按钮/操作权限一律 `v-if="hasPermission('资源编码')"`。

---

## 七、加载与遮罩（强制，R5、R6）

### 7.1 初始加载（R5）

| 场景 | 规范 |
|---|---|
| 页面进入 | `const loading = ref(true)`；根容器 `v-loading="loading"`；`onMounted` 中前置资源（字典/下拉/详情）与首屏列表全部完成后，才在 `finally` 关闭并完整显示 |
| 表单页（编辑/详情） | 按 `mode` 决定初始态：`ref(mode.value !== FORM_MODE.ADD)`；数据未就绪不渲染空表单 |
| 列表请求 | `fetchList` 内部置 `loading`，`finally` 关闭；搜索/翻页/刷新统一走该状态 |
| 弹框组件 | 打开并请求数据时先置 `loading = true`，数据就绪前遮罩并禁止操作 |
| 无需加载的页面/组件 | `dashboard`、登录页、`FileUpload` 等除外 |

```typescript
onMounted(async () => {
  // 1. 页面初始资源加载（loading 初始为 true，全程遮罩并禁止操作）
  try {
    await dictStore.loadDictItems(['PLATFORM'])
    await fetchList()
  } finally {
    // 2. 初始资源全部就绪后关闭页面 loading，完整显示页面
    loading.value = false
  }
})
```

### 7.2 操作幕布（R6）

所有写操作统一用 `src/utils/loading.ts` 的 `withMask(task, text?)`（ElLoading 全屏，引用计数，成功/失败均自动关闭）：

```typescript
await withMask(() => userApi.edit(editForm.toParams(editingId.value || undefined)), '提交中...')
```

| 文案 | 场景 |
|---|---|
| `操作中...` | 删除（单删/批删） |
| `提交中...` | 新增/编辑提交、个人信息/密码提交 |
| `处理中...` | 启用/停用切换 |
| `授权中...` | 角色/资源授权 |
| `刷新中...` | 刷新权限缓存、刷新系统接口 |
| `重置中...` | 重置用户密码 |
| `数据加载中...` | 详情回显请求 |

---

## 八、常量与魔法值（强制，R7）

### 8.1 先区分两类字面量

| 类别 | 判定 | 处理方式 |
|---|---|---|
| 前端 UI 文案 | 由前端交互决定（表单标题「新增资源」、按钮「取消」、启用/停用展示文案、空占位等） | models 常量提供：`ENABLE_FLAG_VIEW`、`*_FORM_TITLE`、`EMPTY_TEXT` 等 |
| **字典内容** | 可由「系统字典管理」页面维护的名称（如性别 `SEX`、资源类型 `RESOURCE_TYPE`） | **禁止硬编码文案**：models 只保留代码值常量（`SEX.MALE`、`RESOURCE_TYPE.PAGE`）与展示样式（`RESOURCE_TYPE_STYLE` 的 tagType/color）；名称经 `dictStore` 加载并在模板用**同步 getter** 渲染 |

### 8.2 落地方式

```typescript
// models：只声明字典编码与样式，不写名称
export const DICT_CODE = { SEX: 'SEX' } as const                    // common.ts
export const RESOURCE_TYPE_DICT_CODE = 'RESOURCE_TYPE'              // pages/resource.ts
export const RESOURCE_TYPE_STYLE = { PAGE: { tagType: 'success', color: '#67C23A' }, ... }
```

```typescript
// 页面：onMounted 中批量预加载（缓存优先）
await dictStore.loadDictItems(['PLATFORM', DICT_CODE.SEX, RESOURCE_TYPE_DICT_CODE])
```

```vue
<!-- 模板：只读缓存渲染，不发起请求 -->
<ElTag :type="getResourceTypeStyle(row.resourceType).tagType">
  {{ dictStore.getDictItemNameSync(RESOURCE_TYPE_DICT_CODE, row.resourceType) }}
</ElTag>
<ElOption v-for="opt in dictStore.getDictItemOptions(DICT_CODE.SEX)" :key="opt.value" :label="opt.label" :value="opt.value" />
```

### 8.3 常量化要求

1. **`.vue` 模板禁止业务字面量**：三元表达式、属性、插值、内联下拉选项一律不得出现 `'Y'`/`'N'`、`'DIR'`/`'PAGE'`/`'BUTTON'`、`'C'`/`'P'`、`'M'`/`'F'`、`'add'`/`'edit'`/`'detail'`、中文文案、颜色值、类名等。
2. **通用内容 → `src/models/common.ts`**：`FLAG`、`SEX`、`FORM_MODE`、`EMPTY_TEXT`、`DEFAULT_PLATFORM_CODE`、`DICT_CODE`；展示映射与取用函数 `getEnableFlagView` / `getEnableFlagToggle` / `getVisitFlagView` / `getAuthCheckView` / `getFormTitle` / `getFormFooterText` / `getSubmitResultText`；通用选项 `ENABLE_FLAG_OPTIONS` / `YES_NO_OPTIONS` / `VISIT_FLAG_OPTIONS`。
3. **业务内容 → `src/models/pages/{模块}.ts`**：资源类型（`RESOURCE_TYPE` 代码值、`RESOURCE_TYPE_STYLE` 样式、`getResourceTypeStyle`、`getResourceTreeIndent`、`RESOURCE_TYPE_DICT_CODE` 字典编码）、接口风格（`API_STYLE`、`API_STYLE_VIEW`、`API_STYLE_OPTIONS`、`getApiStyleText`）、各模块表单标题（`*_FORM_TITLE`）、用户（`USER_QUERY_TYPE`、`USER_PASSWORD_PLACEHOLDER`）等。
4. **models 内部同样禁止散落字面量**：统一引用常量（`FLAG.YES`、`RESOURCE_TYPE.PAGE`、`API_STYLE.COMMON`、`DIR_TYPE.API`、`DEFAULT_LOGIN_USERNAME`、`DEFAULT_PLATFORM_CODE`）。
5. 需要拼接的模板字符串用页面 `computed`（如 `formTitle`、`passwordPlaceholder`）。
6. 样式宽度等使用全局类（`select-sm`）或 `APP_CONFIG`，禁止散落像素值。

```vue
<ElTag :type="getEnableFlagView(row.enableFlag).tagType">{{ getEnableFlagView(row.enableFlag).text }}</ElTag>
<ElOption v-for="opt in ENABLE_FLAG_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
:title="formTitle"
```

---

## 九、注释规范（强制，R9）

1. 每个状态、常量、方法必须有 `/** ... */` 注释；方法体内按执行顺序写步骤注释 `// 1.`、`// 2.`、`// 3.`，复杂步骤用二级编号 `// 2.1`。
2. 简单方法也要写步骤注释（单步写 `// 1. xxx`）。
3. `interface` 字段、模块级常量、`ref`/`computed` 状态用行内 `/** ... */`。
4. 模板按区块写注释（`<!-- 搜索栏 -->`、`<!-- 操作栏与表格 -->`、`<!-- 详情/编辑弹框 -->`）。
5. `finally` 中的收尾动作也要注释（如「// 2. 初始资源全部就绪后关闭页面 loading，完整显示页面」）。

```typescript
/**
 * 查询列表
 */
async function fetchList() {
  // 1. 打开加载状态
  loading.value = true
  try {
    // 2. 组装查询参数（由模型 toParams 产出）
    const params = searchForm.toParams(currentPage.value, pageSize.value)
    // 3. 请求分页数据并写入表格
    const data = await xxxApi.pageList(params)
    tableData.value = data.rows
    total.value = Number(data.totalNum) || 0
  } catch {
    // 4. 异常提示
    showError('获取列表失败')
  } finally {
    // 5. 关闭加载状态
    loading.value = false
  }
}
```

---

## 十、路由与权限（强制，R10）

1. **静态路由**：`/login`（白名单，必须始终可访问）、`/dashboard`、不参与资源树驱动的内部页面（如 `/system/resource/edit` 资源表单页）。
2. **动态路由**：页面组件在 `src/router/index.ts` 的 `componentMap` 注册（componentName → 懒加载组件），由后端鉴权资源树驱动 `addRoute`；未注册组件会被忽略并告警；`resourceTree.ts` 的 `PAGE_ROUTE_FALLBACK` 提供已知页面兜底路径。
3. **预注册**：`main.ts` 在安装 router 前调用 `preRegisterRoutes()`（已登录时用用户信息 + 授权资源树注册动态路由），避免刷新动态路由页面时初始导航匹配失败报 `No match found for location`；预注册成功则守卫跳过初始化，失败由守卫兜底。
4. **守卫**：登录后拉取 `authApi.getUserInfo()` + `resourceApi.getUserAuthTree()`；无页面路由则回登录页；401 清 token 跳 `/login?redirect=...`。
5. **按钮/操作权限**：前端统一 `hasPermission('SYS_XXX_EDIT')`（后端资源编码），禁止硬编码角色判断。
6. **新增页面**：`views/.../Xxx.vue` → `models/pages/{模块}.ts` → `api/modules/{模块}.ts` 并在 `api/index.ts` 导出 → `componentMap` 注册 → 必要时补 `PAGE_ROUTE_FALLBACK`。

---

## 十一、公共能力与交互约定（强制，R11）

| 能力/约定 | 规范 |
|---|---|
| 字典加载 | 统一在页面 `onMounted` 调用 `dictStore.loadDictItems([...])`：缓存优先，仅请求缺失编码；`/commonSys/find/dictItemList` 为批量接口（请求 `dictItemCodeSet` + `enableFlag`，响应 `dictCode → (dictItemCode → 字典项)`）；刷新后清空缓存；模板用同步 getter（`getDictItemNameSync` / `getDictItemOptions`）渲染 |
| 查询条件保留 | 列表页跳转（详情/编辑/子页面）前 `pageQueryStore.saveQuery(key, 快照)`（含搜索条件、页码/每页大小、树展开状态），返回 `onMounted` 时 `takeQuery(key)` 恢复并按原条件查询；快照 key 用页面唯一标识（如 `sysResource`），快照类型放 `models/pages/` |
| 平台联动刷新 | 搜索栏平台选择切换后自动刷新（树形列表直接重查；分页列表重置到第 1 页后重查） |
| 图标库 | 前端维护 SVG 图标库，经 `GET /icons/list.json` 提供给 Web/App；源文件 `public/icons/svg/{iconCode}.svg`，`scripts/buildIcons.mjs`（含 `iconNames` 中文名）生成清单，`predev`/`prebuild` 自动执行；资源树 DIR/PAGE 存 `iconCode`，经 `iconStore` + `MenuIcon.vue`/`IconSelect.vue` 渲染与选择 |
| 文件上传 | `src/components/FileUpload/FileUpload.vue`（小文件直传 + 分片断点续传），模型 `models/components/fileUpload.ts` |
| 二次确认 | 删除等破坏性操作一律 `showConfirm(...)` |
| 错误处理 | `try/catch` + `showError(err.message || '默认文案')`，`finally` 关闭加载态 |
| ds 框架同步 | `dsApi.flushApis()` 将 Spring Web 暴露 API 同步到 `sys_api` |

---

## 十二、命令、测试与提交

| 命令 | 用途 |
|---|---|
| `pnpm dev` | 启动开发服务器（5173，`predev` 自动生成图标清单） |
| `pnpm build` | 打包到 `dist/`（`prebuild` 自动生成图标清单） |
| `pnpm test` | Vitest 测试套件（非 watch：`pnpm exec vitest run`） |
| `pnpm build:icons` | 扫描 `public/icons/svg/*.svg` 生成 `public/icons/list.json` |
| `pnpm check` | `vue-tsc --noEmit` 类型检查（需先安装 `vue-tsc`） |
| `pnpm check:standard` | 规范自检脚本（扫描 `src/`，检查 R1/R2/R5/R6/R7/R8，违规退出码 1） |
| `pnpm audit:standard` | 规范 ↔ 项目一致性审计（API 清单、models 登记、页面注册、命令表、图标清单、路径引用） |

- 测试文件：`*.test.ts` / `*.spec.ts`，就近或 `__tests__/`（现有 `src/models/__tests__/models.test.ts`）。
- 提交信息：简短、祈使语气（如 `init`、`初始化`）。
- PR：说明改动；引用了后端未实现接口需注明；必要时附 UI 截图；可引用规则编号（如「符合 R5、R6」）。

---

## 十三、验收自检清单（全局）

- [ ] 目录分层与依赖方向正确（无反向依赖、无 `src/types/`）——R1、R2
- [ ] 接口/类/常量放 `src/models/`，命名符合 Qo/Vo/Bo/Dto；`.vue` 无 `interface`/`class` ——R2
- [ ] 请求载荷全部来自 `toParams()` / `toFormData()`，每次请求带 `apiCode` ——R1、R4
- [ ] 数字字段 `Number(x) || 0` 归一化；`toParams()` 仅含非空字段 ——R3
- [ ] 页面/组件加载态符合规范（`loading = ref(true)`、`v-loading`、`try/finally`） ——R5
- [ ] 所有写操作用 `withMask` 包裹并选择对应文案 ——R6
- [ ] 模板无业务字面量、下拉走常量 `v-for`；字典内容走字典（不硬编码文案） ——R7
- [ ] 字典值字段声明 `string`；字面量类型均命名后引用 ——R8
- [ ] 注释（状态/方法/步骤/模板区块）齐全 ——R9
- [ ] 路由注册与按钮权限（`hasPermission`）覆盖 ——R10
- [ ] 跳转前保存查询条件、返回时恢复；平台切换自动刷新 ——R11
- [ ] 页面结构遵循骨架（分区顺序、类名、方法顺序） ——R12
- [ ] `pnpm test`、`pnpm build` 通过
