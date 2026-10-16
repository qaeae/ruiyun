# models 书写规范（models-standard）

> 适用范围：`src/models/**`（`common.ts`、`pages/*.ts`、`components/*.ts`、`index.ts`）。
> 上位规范：[PROJECT-STANDARD.md](PROJECT-STANDARD.md)（项目整体规范），冲突时以其为准；配套：[page-standard.md](page-standard.md)、[reference.md](reference.md)。

---

## 一、定位与依赖

`src/models/` 是项目的**唯一数据契约层**：接口（`interface`）、类（`class`）、常量与映射、取用函数全部在此定义。

```
views / components / stores  →  models（转换方法）  →  api/modules  →  utils/request
```

- ✅ 允许：`import { compileMapper, transform } from 'mapia'`；`import type { ... } from '../common.js'`
- ❌ 禁止：`import` 任何 `@/api`、`@/stores`、`@/views`、`@/components`、`vue`、`element-plus`
- ❌ 禁止：在 `.vue` 中定义 `interface` / `class` / 业务常量（一律放 models）
- 依赖方向单向：`models` 不依赖任何上层；`components/*.ts` 可引用 `pages/*.ts`（**组件 > 页面**），反向禁止

---

## 二、目录与文件划分

| 位置 | 内容 | 何时放这里 |
|---|---|---|
| `models/common.ts` | 通用传输 Dto/Bo + 通用字典标记、展示映射、取用函数、通用选项、通用字典编码 | 多个模块共用的结构与文案（启用状态、是否显示、鉴权、表单标题/按钮/结果、空占位、默认平台、`DICT_CODE`） |
| `models/pages/{模块}.ts` | 页面模块模型与业务常量 | 该模块专属（如 `resource.ts` 的资源类型、`api.ts` 的接口风格） |
| `models/components/{组件}.ts` | 组件模型 | 组件专属（如 `fileUpload.ts`、`userSelect.ts`、`icon.ts`、`sidebarMenu.ts`） |
| `models/index.ts` | 统一导出 | 新增模块后**必须**在此新增 `export * from './pages/{模块}.js'` |

**一模块一文件**；`.vue` / 页面 / api 模块统一从 `@/models/index.js` 导入。不再使用 `src/types/`。

```typescript
// src/models/index.ts
export * from './common.js'
export * from './pages/user.js'
export * from './components/fileUpload.js'
```

---

## 三、文件内部结构顺序（固定）

同一模块文件内按以下顺序书写（自上而下）：

```
1. 文件头 JSDoc（模块名 + Qo/Vo/Bo/Dto 说明 + mapia 说明）
2. import（mapia → common 类型/常量）
3. 快照 Qo（interface，如 SysXxxPageQueryQo）        ← 有跨页快照需求时
4. Vo（interface：主 Vo → ListVo（extends 主 Vo）→ 其它回显 Vo/TreeNodeVo）
5. 查询 Qo（class：字段 → constructor → reset → toParams）
6. 私有映射：interface SysXxxEditFormData + const xxxEditFormFromRowMapper = compileMapper(...)
7. 提交 Bo（class：字段 → constructor → reset → fill → toParams/toFormData）
8. 其它 Bo（授权、批量、更新信息等）
9. 业务常量（*_VIEW / *_OPTIONS / *_FORM_TITLE / 取用函数）
```

文件头与 `index.ts` 头部都要写明目录规则说明：

```typescript
/**
 * 字典模块模型（dict）
 * Qo=查询表单；Vo=页面回显；Bo=数据提交；Dto=前端内部通用传输
 * 数据拷贝/映射统一使用 mapia
 */
import { compileMapper, transform } from 'mapia'
import type { FormMode } from '../common.js'
```

---

## 四、命名规范（R2）

### 4.1 后缀（强制）

| 后缀 | 形态 | 用途 | 示例 |
|---|---|---|---|
| `Qo` | 类 / 接口 | 只做查询：查询表单（可 `toParams()`）、跨页快照 | `SysUserQueryQo`、`SysDictPageQueryQo`、`UserSelectQueryQo` |
| `Vo` | 接口 | 只做页面回显 | `SysUserListVo`、`SysResourceTreeNodeVo`、`CaptchaVo` |
| `Bo` | 类 | 只用于提交（非查询表单调用） | `SysUserEditBo`、`BatchDeleteBo`、`RoleResourceAuthBo` |
| `Dto` | 接口 | 前端内部传输/复用结构、缓存结构、路由菜单结构 | `SysDictItemCacheDto`、`PageQueryDto`、`SidebarMenuItemVo` |

- 类名 = 模块前缀（`Sys`）+ 业务 + 后缀：`SysUserEditBo`；组件模型可无 `Sys` 前缀：`FileUploadBo`
- `Vo` 用 `interface`（纯数据，不写方法）；`Qo`/`Bo` 用 `class`（需要 `reset`/`fill`/`toParams`）
- 列表项 `XxxListVo extends XxxVo`；树节点 `XxxTreeNodeVo`；缓存结构 `XxxCacheDto` / `XxxCacheMap`

### 4.2 方法与字段

| 项 | 规则 | 示例 |
|---|---|---|
| 类字段 | camelCase，与后端字段名一致（不随类名改） | `dictItemCodeSet`、`enableFlag` |
| 构造函数 / 重置 | `constructor(init?: Partial<T>)`、`reset()` | `new SysUserQueryQo()` |
| 回填 | `fill(row: XxxListVo)`（内部调 mapper） | `editForm.fill(row)` |
| 转换 | `toParams(...)` / `toFormData(...)` | `toParams(id?: string)` |
| 私有映射 | `interface SysXxxEditFormData`（不导出）+ `const xxxEditFormFromRowMapper` | `userEditFormFromRowMapper` |
| 常量 | UPPER_SNAKE_CASE；随用途加后缀 | `RESOURCE_TYPE`、`ENABLE_FLAG_VIEW`、`API_STYLE_OPTIONS`、`DICT_FORM_TITLE` |
| 取用函数 | `get` + 语义，带非法值兜底 | `getEnableFlagView(flag?)`、`getApiStyleText(style?)` |

---

## 五、类书写规范

### 5.1 查询类（Qo）

```typescript
/** 字典查询（Qo，表单查询 + v-model 绑定） */
export class SysDictQueryQo {
  dictCode: string
  dictName: string
  pageNum?: number
  pageSize?: number

  /**
   * 构造字典查询表单
   * @param init 初始值
   */
  constructor(init: Partial<SysDictQueryQo> = {}) {
    // 1. 初始化查询字段默认值
    this.dictCode = ''
    this.dictName = ''
    // 2. 合并传入的初始值
    Object.assign(this, init)
  }

  /** 重置搜索条件 */
  reset(): void {
    // 1. 以全新实例覆盖当前字段
    Object.assign(this, new SysDictQueryQo())
  }

  /**
   * 转换为字典查询参数（仅包含非空字段）
   * @param pageNum 页码
   * @param pageSize 每页大小
   * @returns 查询参数
   */
  toParams(pageNum?: number, pageSize?: number): SysDictQueryQo {
    // 1. 创建空参数对象，按需写入分页
    const params = {} as SysDictQueryQo
    if (pageNum !== undefined) params.pageNum = pageNum
    if (pageSize !== undefined) params.pageSize = pageSize
    // 2. 仅写入非空查询条件
    if (this.dictCode) params.dictCode = this.dictCode
    if (this.dictName) params.dictName = this.dictName
    return params
  }
}
```

要点：
- 字段声明与后端请求字段**同名同义**；可空字段用 `?`
- `constructor(init: Partial<T> = {})`：先赋默认值再 `Object.assign(this, init)`
- `reset()` 用 `Object.assign(this, new Xxx())` 覆盖
- `toParams()` 返回**类型化的普通参数对象**（`{} as Xxx`），仅写入非空字段；分页参数由调用方显式传入

### 5.2 回显类（Vo）

```typescript
/** 系统字典（Vo，页面数据显示） */
export interface SysDictVo {
  id?: string
  dictCode: string
  dictName: string
  dictSort?: number
  dictDesc?: string
  createTime?: string
  updateTime?: string
}

/** 字典列表项（Vo，页面数据显示） */
export interface SysDictListVo extends SysDictVo {
  createUserId?: string
  createUserName?: string
  updateUserName?: string
}
```

- 纯 `interface`，不写方法；必填字段不加 `?`，后端可能不返回的加 `?`
- 需要用到的字段才声明（按接口文档），命名与后端一致

### 5.3 提交类（Bo）

```typescript
interface SysDictEditFormData {
  dictCode: string
  dictName: string
  dictSort: number
  dictDesc: string
}

const dictEditFormFromRowMapper = compileMapper<SysDictListVo, SysDictEditFormData>({
  dictCode: 'dictCode',
  dictName: 'dictName',
  dictSort: transform((dictSort?: number | string) => Number(dictSort) || 0),
  dictDesc: transform((dictDesc?: string) => dictDesc || '')
})

/** 字典编辑提交模型（Bo，页面双向绑定 + 提交） */
export class SysDictEditBo {
  id?: string
  dictCode: string
  dictName: string
  dictSort: number
  dictDesc: string

  /** 构造字典编辑表单（新增默认值） */
  constructor() {
    // 1. 初始化字段默认值
    this.dictCode = ''
    this.dictName = ''
    this.dictSort = 0
    this.dictDesc = ''
  }

  /** 重置为新增默认值 */
  reset(): void {
    // 1. 以全新实例覆盖当前字段
    Object.assign(this, new SysDictEditBo())
  }

  /**
   * 从列表行回填表单
   * @param row 字典列表行
   */
  fill(row: SysDictListVo): void {
    // 1. 由 mapia 完成字段映射并合并到表单
    Object.assign(this, dictEditFormFromRowMapper.mapOne(row))
  }

  /**
   * 转换为字典编辑提交参数（仅包含非空字段）
   * @param id 字典ID（编辑时传入）
   * @returns 提交参数
   */
  toParams(id?: string): SysDictEditBo {
    // 1. 创建空参数对象并写入主键
    const params = {} as SysDictEditBo
    params.id = id || undefined
    // 2. 写入必填字段
    params.dictCode = this.dictCode
    params.dictName = this.dictName
    params.dictSort = this.dictSort
    // 3. 仅写入非空可选字段
    if (this.dictDesc) params.dictDesc = this.dictDesc
    return params
  }
}
```

要点：
- 新增/编辑共用一个 `XxxEditBo`；`toParams(id?)` 通过参数区分主键
- 必填字段直接写入；可空字段用 `if (this.xxx)` 条件写入
- 新增默认值在 `constructor` 中给定，页面 `handleAdd` 调 `reset()`
- 上传类 Bo 提供 `toFormData()`：

```typescript
/**
 * 生成上传 body FormData
 * @returns 含 file 字段的 FormData
 */
toFormData(): FormData {
  // 1. 创建 FormData 并追加文件字段
  const formData = new FormData()
  formData.append('file', this.file)
  return formData
}
```

### 5.4 其它 Bo

| 场景 | 类名示例 | 关键点 |
|---|---|---|
| 批量删除 | `BatchDeleteBo`（common.ts） | `constructor(ids: string[] = [])`、`toParams()` 拷贝数组 |
| 授权 | `UserRoleAuthBo`、`RoleResourceAuthBo` | `toParams()` 复制数组/字段，避免引用共享 |
| 更新信息/密码 | `SysUserUpdateInfoBo`、`SysUserUpdatePwdBo` | 有 `fill()` + `reset()` + `toParams()` |
| 刷新权限 | `RoleRefreshPermissionBo` | 编码 + 平台 |
| 组件查询 | `FileChunkUploadQueryQo`、`UserSelectQueryQo` | 组件 `components/` 下的 Qo |

---

## 六、数据映射 mapia（强制，R3）

```typescript
import { compileMapper, transform, transformWithRename } from 'mapia'

interface SysResourceEditFormData { /* 表单字段 */ }

const resourceEditFormFromRowMapper = compileMapper<SysResourceTreeNodeVo, SysResourceEditFormData>({
  resourceCode: 'resourceCode',                                  // 同名直接映射
  resourceName: transformWithRename('name'),                     // 后端字段名不同 → 重命名映射
  resourceSort: transform((v?: number | string) => Number(v) || 0), // 类型/结构转换
  extensions: transform((extensions?: SysResourceExtVo[]) =>     // 嵌套转换
    (extensions || []).map((ext) => ({ /* ... */ })))
})
```

- `fill(row)` 必须用 `compileMapper` 定义的 mapper + `mapOne(row)`，再 `Object.assign(this, ...)`
- 同名映射写字符串；字段名不同用 `transformWithRename`；类型转换/派生/嵌套用 `transform`
- mapper 命名：`{camelCase模块}EditFormFromRowMapper`（如 `userEditFormFromRowMapper`）
- mapper 与其 `FormData` interface **不导出**（模块私有）
- 数组/嵌套结构转换结果类型不符时用 `as XxxDto[]` 断言（并在 mapper 内保证字段完整）

**允许不用 mapper 的例外**：`constructor` 默认值、`reset()`、`toParams()` 直接构造、`toFormData()`。

---

## 七、字段类型与命名类型（强制，R8）

| 场景 | 规则 |
|---|---|
| 数字字段（排序号、数量） | 后端可能返回字符串 → mapper 中 `Number(x) \|\| 0`（防止 `ElInputNumber` 报 `Invalid prop: type check failed`） |
| Flag 字段（`Y`/`N`） | 默认值引用常量（`FLAG.YES` / `FLAG.NO`），不写字面量 |
| 可选字段留空 | `toParams()` 中不写入（或用 `\|\| undefined`） |
| 数组字段 | 转换时防御 `(list \|\| []).map(...)` |
| 日期时间 | 保持字符串（如 `createTime?: string`），不在 models 中格式化 |
| 联合类型 | 用 `type` 声明（如 `ResourceType = 'DIR' \| 'PAGE' \| 'BUTTON'`），禁止 `enum` |
| **字典值字段** | `interface` / `class` 中表示字典值的属性只声明 `string`，**禁止写死字面量联合类型**（`'Y' \| 'N'`、`'M' \| 'F'`、`'DIR' \| 'PAGE' \| 'BUTTON'`、`'C' \| 'P'`）——字典项可由字典管理增减 |

**字典值字段类型说明（强制）**

`enableFlag`、`visitFlag`、`sex`、`resourceType`、`apiStyle`、`shouldDel`、`logReq`、`logResp`、`h5LinkFlag`、`dirTypeCode` 等字典值属性统一声明为 `string`：

```typescript
export interface SysUserListVo {
  enableFlag: string        // ✅ 只声明数据类型
  sex?: string              // ✅
  // enableFlag: 'Y' | 'N'  // ❌ 字典项会增减，禁止写死字面量联合
}

export class SysUserEditBo {
  enableFlag: string        // ✅
  sex: string               // ✅
}
```

- 代码值用常量赋值与比较：`this.enableFlag = FLAG.YES`、`this.sex = SEX.MALE`、`row.resourceType === RESOURCE_TYPE.BUTTON`
- mapper 的 `transform` 回调参数同样写 `string`：`transform((enableFlag?: string) => enableFlag || FLAG.YES)`
- **确需字面量类型时必须命名后引用**：不允许在属性/参数/映射上内联写字面量联合（`'Y' | 'N'`、`'M' | 'F'`、`'asc' | 'desc'`、`'bg' | 'fg'`、`'primary' | 'success'`、`'text' | 'picture' | 'picture-card'` 等），必须先定义**命名类型**（优先由 `as const` 常量派生）再引用；映射的 key 同样引用命名类型：`ENABLE_FLAG_VIEW: Record<FlagValue, FlagViewDto>`、`RESOURCE_TYPE_STYLE: Record<ResourceType, ...>`、`API_STYLE_VIEW: Record<ApiStyle, string>`

**命名类型清单（新增字面量类型时先查这里，能复用则复用）**

| 命名类型 | 取值 | 定义位置 | 派生来源 |
|---|---|---|---|
| `FlagValue` | `'Y' | 'N'` | `models/common.ts` | `(typeof FLAG)[keyof typeof FLAG]` |
| `SexValue` | `'M' | 'F'` | `models/common.ts` | `(typeof SEX)[keyof typeof SEX]` |
| `FormMode` | `'add' | 'edit' | 'detail'` | `models/common.ts` | 表单三模式 |
| `FormSubmitMode` | `'add' | 'edit'` | `models/common.ts` | 提交结果提示 |
| `SortOrder` | `'asc' | 'desc'` | `models/common.ts` | 列表/树排序方向 |
| `TagType` | `'primary' | 'success' | 'warning' | 'info' | 'danger'` | `models/common.ts` | ElTag/ElButton 类型 |
| `ResourceType` | `'DIR' | 'PAGE' | 'BUTTON'` | `models/pages/resource.ts` | 资源类型 |
| `ApiStyle` | `'C' | 'P'` | `models/pages/api.ts` | 接口路径风格 |
| `FileListType` | `'text' | 'picture' | 'picture-card'` | `models/components/fileUpload.ts` | 上传列表展示 |
| `UserQueryType` | `'bg' | 'fg'` | `models/components/userSelect.ts` | `(typeof USER_QUERY_TYPE)[keyof typeof USER_QUERY_TYPE]` |

规则要点：

1. **字典值属性** → 只写数据类型（`string`）；
2. **确需字面量** → 先在 models 定义命名类型（通用放 `common.ts`、业务放 `pages/{模块}.ts`、组件放 `components/{组件}.ts`），再在属性、`Record` key、函数参数、组件 props 中引用；
3. 能由常量派生就不要手写联合（`(typeof FLAG)[keyof typeof FLAG]`），保证常量增删时类型自动同步；
4. 组件 props（`.vue` 的 `defineProps<{...}>`）同样遵守本规则（引用 models 中的命名类型）。

---

## 八、常量与映射规范（强制，R7）

### 8.1 先区分：字典内容 vs 前端文案

| 类别 | 示例 | 处理方式 |
|---|---|---|
| 前端 UI 文案 | 「新增资源」标题、「取消/返回」按钮、启用/停用展示文案、空占位 | models 常量（`*_TITLE`、`*_VIEW`、`EMPTY_TEXT`） |
| **字典内容** | 性别（`SEX`）、资源类型（`RESOURCE_TYPE`）等由「字典管理」维护的名称 | **禁止在 models 硬编码文案**：models 仅保留代码值常量与展示样式（`RESOURCE_TYPE_STYLE` 的 tagType/color），名称经 `dictStore` 加载，模板用 `getDictItemNameSync` / `getDictItemOptions` 渲染 |

- 字典编码常量：通用字典 → `DICT_CODE`（common.ts）；业务字典 → 模块内 `XXX_DICT_CODE`（如 `RESOURCE_TYPE_DICT_CODE`）
- 页面 `onMounted` 中 `await dictStore.loadDictItems([...])` 预加载（缓存优先），模板同步渲染，避免异步取值

### 8.2 放哪里

| 内容 | 位置 |
|---|---|
| 通用字典标记、展示映射、取用函数、通用选项 | `models/common.ts` |
| 业务字典标记、展示映射、业务选项、模块表单标题 | `models/pages/{模块}.ts` |

### 8.3 命名与形态（固定四种）

```typescript
/** 通用字典标记（as const） */
export const FLAG = { YES: 'Y', NO: 'N' } as const
export const FORM_MODE = { ADD: 'add', EDIT: 'edit', DETAIL: 'detail' } as const

/** 展示映射：text（文案）+ tagType（ElTag 类型）+ color（纯文本颜色） */
export const ENABLE_FLAG_VIEW: Record<'Y' | 'N', FlagViewDto> = {
  Y: { text: '启用', tagType: 'success', color: '#67C23A' },
  N: { text: '停用', tagType: 'danger', color: '#F56C6C' }
}

/** 选项数组：供模板 v-for（label 复用 VIEW 文案，value 复用字典常量） */
export const ENABLE_FLAG_OPTIONS = [
  { label: ENABLE_FLAG_VIEW.Y.text, value: FLAG.YES },
  { label: ENABLE_FLAG_VIEW.N.text, value: FLAG.NO }
]

/** 表单标题：按 FormMode */
export const DICT_FORM_TITLE: Record<FormMode, string> = {
  add: '新增字典',
  edit: '编辑字典',
  detail: '字典详情'
}

/** 取用函数：带非法值兜底 */
export function getEnableFlagView(flag?: string | null): FlagViewDto {
  // 1. 仅 Y 视为启用，其余按停用处理
  return ENABLE_FLAG_VIEW[flag === FLAG.YES ? 'Y' : 'N']
}
```

| 后缀 | 用途 |
|---|---|
| `_VIEW` | 值 → `{ text, tagType, color }` 展示配置 |
| `_OPTIONS` | 值 → `{ label, value }[]`，模板 `v-for` 使用 |
| `_TITLE` | `Record<FormMode, string>`，弹框/页面标题 |
| （无后缀） | 字典标记对象、占位文案、类名等标量常量 |

### 8.4 强制要求

- 模板中禁止业务字面量（详见 PROJECT-STANDARD 第九章）；**models 内部同样禁止散落字面量**：

```typescript
this.resourceType = RESOURCE_TYPE.PAGE      // ✅
this.resourceType = 'PAGE'                  // ❌
this.apiStyle = API_STYLE.COMMON            // ✅
this.enableFlag = FLAG.YES                  // ✅
this.username = DEFAULT_LOGIN_USERNAME      // ✅
platformCode: this.platformCode || DEFAULT_PLATFORM_CODE  // ✅
```

- 新增展示文案必须同时更新 `*_VIEW` / `*_OPTIONS` / `*_TITLE`，页面不得另写文案
- 取用函数必须处理非法值（回退到默认分支），避免模板渲染 `undefined`

---

## 九、通用结构（Dto）规范

| 结构 | 用途 |
|---|---|
| `ApiResponseDto<T>` | 统一响应体（`code` / `data` / `msg`） |
| `TableDataDto<T>` | 分页数据结构（`rows` / `totalNum` / `pageNum` / `pageSize`） |
| `PageQueryDto` | 分页查询基础（`pageNum?` / `pageSize?`） |
| `SysDictItemCacheDto` / `SysDictItemCacheMap` | 字典批量缓存结构 |
| `UserInfoCacheDto` | 鉴权用户信息缓存 |
| `SysResourceExtConfigInfoDto` / `SysResourceChannelExtDto` | 资源扩展配置/平台渠道 |
| `SidebarMenuItemVo` / `RouteConfigDto`（components/sidebarMenu.ts） | 侧边栏菜单与路由配置 |
| `IconVo` / `IconMap`（components/icon.ts） | 图标清单与索引 |

新增通用结构时优先复用以上类型；仅当语义不同才新增。

---

## 十、API 模块配套规范（R1、R4）

```typescript
// src/api/modules/user.ts
import type { TableDataDto, SysUserQueryQo, SysUserListVo, BatchDeleteBo } from '@/models/index.js'

export const userApi = {
  /**
   * 分页查询后台用户
   * @param params 查询参数（由 SysUserQueryQo.toParams 产出）
   */
  bgPageList(params: SysUserQueryQo): Promise<TableDataDto<SysUserListVo>> {
    // 1. 转发已转换好的查询参数
    return request({ url: '/user/bgPageList', method: 'post', data: params, apiCode: 'SYS_USER_PAGE' })
  }
}
```

- 参数类型用 `Qo` / `Bo`，返回类型用 `Vo` / `Dto`（`TableDataDto<XxxListVo>`）
- 模块内**只转发**，不组装、不变换
- 方法命名与 models 语义对应（`pageList` / `detail` / `edit` / `deleteBatch` / `enableOpt`）

---

## 十一、测试规范

- 位置：`src/models/__tests__/models.test.ts`（Vitest）
- 覆盖：`fill()` 字段映射与类型归一化、`toParams()` 仅含非空字段与主键写入、`reset()` 默认值、常量映射文案
- 断言 `toParams()` 产出的对象应使用 `toEqual`，注意其**不含默认空字段**
- 运行：`pnpm test`（非 watch 用 `pnpm exec vitest run`）

---

## 十二、自检清单（models 专项）

> 与 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 第十三章「验收自检清单（全局）」配合使用。

- [ ] 接口/类/常量都在 `src/models/`，未在 `.vue` 中定义 interface/class/业务常量
- [ ] 文件划分正确（通用 → `common.ts`、页面 → `pages/`、组件 → `components/`），且已在 `index.ts` 导出
- [ ] 命名符合 `Qo`/`Vo`/`Bo`/`Dto` 后缀与字段同名后端约定
- [ ] `Vo` 为纯 `interface`；`Qo`/`Bo` 有 `constructor`（默认值）+ `reset()`；需要回填的有 `fill()`（mapia）
- [ ] `toParams()` 仅包含非空字段、返回类型化参数对象；`toFormData()` 用于上传
- [ ] 数字字段 `Number(x) || 0`；数组字段有 `|| []` 防御
- [ ] 无散落字面量，全部引用 `FLAG` / `RESOURCE_TYPE` / `API_STYLE` / `*_VIEW` / `*_OPTIONS` / `*_TITLE` 等常量
- [ ] 每个类/接口/方法/字段都有 `/** */` 注释，方法体内有 `// 1.` 步骤注释
- [ ] `import` 未引入 api/store/vue/element-plus
- [ ] `pnpm exec tsc --noEmit`（或 `pnpm check`）通过，`pnpm test` 通过

---

## 十三、模块文件模板

复制 [templates/model-module.ts](templates/model-module.ts) 到 `src/models/pages/{module}.ts`，替换占位符（`{模块}` / `{module}` / `SysXxx` / `xxx`），并在 `src/models/index.ts` 新增 `export * from './pages/{module}.js'`。

模板已内置（满足 R1–R9）：

| 组成 | 说明 |
|---|---|
| `SysXxxPageQueryQo` | 列表页查询条件快照 |
| `SysXxxVo` / `SysXxxListVo` | 页面回显（字典值字段声明 `string`） |
| `SysXxxQueryQo` | 查询表单（`constructor` / `reset` / `toParams`，仅非空字段） |
| `SysXxxEditFormData` + `xxxEditFormFromRowMapper` | 模块私有映射（mapia，数字字段归一化） |
| `SysXxxEditBo` | 提交模型（`reset` / `fill` / `toParams(id?)`） |
| `XXX_FORM_TITLE` | 表单标题（按 `FormMode`） |

---

## 十四、参考实现

| 参考 | 文件 | 特点 |
|---|---|---|
| 标准完整模块 | `src/models/pages/dict.ts` | Vo/Qo/Bo + mapper + 业务常量，最简洁的样板 |
| 多 Bo + 表单映射 | `src/models/pages/user.ts` | 用户/角色授权/更新信息与密码多个 Bo，多个 mapper |
| 复杂嵌套映射 | `src/models/pages/resource.ts` | `transformWithRename`、嵌套 `extensions` 转换、资源类型视图与选项 |
| 通用常量与选项 | `src/models/common.ts` | `FLAG`/`FORM_MODE`/`*_VIEW`/`*_OPTIONS`/取用函数 |
| 组件模型（上传） | `src/models/components/fileUpload.ts` | `toFormData()`、分片上传 Qo/Bo |
| 组件模型（跨模块引用） | `src/models/components/userSelect.ts` | 组件 Qo 复用 `SysUserQueryQo` |
