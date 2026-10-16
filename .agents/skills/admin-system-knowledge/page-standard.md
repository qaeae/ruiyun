# 页面书写规范（page-standard）

> 适用范围：`src/views/**` 下的页面（列表页、表单页、详情页）。组件（`src/components/**`）除结构分区外同样适用本规范中「加载、幕布、魔法值、注释、数据流」相关约束。
> 本规范为**强制**规范，新增/改造页面必须遵循；上位规范为同目录 [PROJECT-STANDARD.md](PROJECT-STANDARD.md)（项目整体规范），两者冲突时以 PROJECT-STANDARD 为准。

---

## 一、文件与命名（R2）

| 项 | 约定 |
|---|---|
| 页面位置 | `src/views/{业务域}/{页面名}.vue`，如 `src/views/system/SysUser.vue`、`src/views/login/index.vue` |
| 组件位置 | `src/components/{组件名}/{组件名}.vue`，如 `src/components/UserSelect/UserSelectDialog.vue` |
| 文件命名 | PascalCase（`SysUser.vue`），入口页可用 `index.vue` |
| 页面私有组件 | 放同级目录，命名 `{页面名}{用途}.vue` |
| 模型文件 | 一模块一文件：`src/models/pages/{模块}.ts`、`src/models/components/{组件}.ts`，统一从 `@/models/index.js` 导入 |

---

## 二、SFC 结构顺序（R12）

必须按 `script setup` → `template` → `style` 顺序书写：

```vue
<script setup lang="ts">
/* ... */
</script>

<template>
  <!-- ... -->
</template>

<style scoped lang="scss">
/* ... */
</style>
```

**禁止**在 `.vue` 中定义 `interface` / `class` / 业务常量（一律来自 `@/models`）。

---

## 三、`<script setup>` 分区与顺序（R12、R9）

固定四个分区，使用统一分区注释：

```typescript
<script setup lang="ts">
/**
 * {页面名}（一句话说明页面职责）
 * 补充说明：如「列表 + 详情/编辑弹框」「支持从 xx 页跳转带入参数」
 */
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { APP_CONFIG } from '@/config/index.js'
import { xxxApi } from '@/api/index.js'
import { hasPermission } from '@/utils/permission.js'
import { showSuccess, showError, showConfirm } from '@/utils/message.js'
import { withMask } from '@/utils/loading.js'
import { useDictStore } from '@/stores/dictStore.js'
import { usePageQueryStore } from '@/stores/pageQueryStore.js'
import { SysXxxQueryQo, SysXxxEditBo, BatchDeleteBo } from '@/models/index.js'
import { XXX_FORM_TITLE, getFormTitle, getSubmitResultText } from '@/models/index.js'
import type { SysXxxListVo, SysXxxPageQueryQo } from '@/models/index.js'

// ==================== 页面状态 ====================
// ==================== 表单验证规则 ====================
// ==================== 业务方法 ====================
// ==================== 生命周期 ====================
</script>
```

**import 顺序**：`vue` → `vue-router` → 第三方（element-plus 类型） → `@/config` → `@/api` → `@/utils` → `@/stores` → `@/models`（先值、后 `import type`）。

### 3.1 页面状态分区

顺序固定：**加载态 → 列表数据 → 分页 → 选择 → 查询表单 → 弹框状态 → 标题等派生状态**

```typescript
// ==================== 页面状态 ====================

/** 页面加载中：初始为 true，onMounted 初始资源加载完成后才关闭并完整显示 */
const loading = ref(true)
/** 列表数据 */
const tableData = ref<SysXxxListVo[]>([])
/** 数据总数 */
const total = ref(0)
/** 当前页码 */
const currentPage = ref(1)
/** 每页大小 */
const pageSize = ref<number>(APP_CONFIG.defaultPageSize)
/** 已选中的行 */
const selectedRows = ref<SysXxxListVo[]>([])
/** 搜索表单（模型实例，禁止使用对象字面量） */
const searchForm = reactive(new SysXxxQueryQo())

/** 编辑弹框是否可见 */
const editDialogVisible = ref(false)
/** 编辑提交加载中 */
const editLoading = ref(false)
/** 编辑表单引用 */
const editFormRef = ref<FormInstance>()
/** 编辑表单数据（模型实例） */
const editForm = reactive(new SysXxxEditBo())
/** 是否编辑模式 */
const isEdit = ref(false)
/** 是否详情只读模式 */
const isDetail = ref(false)
/** 正在编辑的数据ID */
const editingId = ref('')

/** 编辑弹框标题（按详情/编辑/新增模式） */
const formTitle = computed(() => getFormTitle(XXX_FORM_TITLE, isDetail.value, isEdit.value))
```

- 页面级常量（如 `pageQueryStore` 的 key）放分区之前，命名 `UPPER_SNAKE_CASE` 并在 `src/models` 中定义；
- 需要带入子页面的查询条件快照 key 使用业务模块常量，禁止裸字符串。

### 3.2 表单验证规则分区

```typescript
// ==================== 表单验证规则 ====================

/** 编辑表单验证规则 */
const editRules = computed<FormRules>(() => ({
  xxxCode: [{ required: true, message: '请输入编码', trigger: 'blur' }]
}))
```

### 3.3 业务方法分区（顺序固定）

```typescript
// ==================== 业务方法 ====================

/** 查询列表 */
async function fetchList() { /* 1. 打开加载态 2. 组装查询参数 3. 请求 4. 写入列表 5. finally 关闭加载态 */ }
/** 搜索（重置到第 1 页） */
function handleSearch() {}
/** 重置查询条件 */
function handleReset() {}
/** 翻页 */
function handlePageChange(p: number) {}
/** 改变每页条数 */
function handleSizeChange(s: number) {}
/** 表格多选变化 */
function handleSelectionChange(rows: SysXxxListVo[]) {}
/** 新增 */
function handleAdd() {}
/** 打开详情/编辑弹框并回填 */
function openDialog(row: any, detail: boolean) {}
/** 查看详情 */
function handleDetail(row: any) {}
/** 编辑 */
function handleEdit(row: any) {}
/** 提交新增/编辑 */
async function handleEditSubmit() {}
/** （模块特有操作，如字典项/授权/启停） */
/** 删除单行 */
async function handleDelete(row: any) {}
/** 批量删除 */
async function handleBatchDelete() {}
```

要点：

- **查询参数由模型产出**：`const params = searchForm.toParams()`，禁止内联对象字面量传 API；
- **写操作必须用幕布**：`await withMask(() => xxxApi.edit(editForm.toParams(editingId.value || undefined)), '提交中...')`；
- **提示文案由 models 提供**：`showSuccess(getSubmitResultText(isEdit.value))`、`showSuccess(getEnableFlagToggle(row.enableFlag).resultText)`；
- **提交成功后**：关闭弹框 → 提示 → `fetchList()`；
- **删除流程**：`showConfirm(...)` → `withMask(() => xxxApi.deleteBatch(new BatchDeleteBo(ids).toParams()))` → 提示 → 刷新。

### 3.4 生命周期分区

```typescript
// ==================== 生命周期 ====================

onMounted(async () => {
  // 1. 页面初始资源加载（loading 初始为 true，全程遮罩并禁止操作）
  try {
    // 1.1 批量加载字典（缓存优先，仅请求缺失编码）
    await dictStore.loadDictItems(['PLATFORM'])
    // 1.2 恢复跳转前保存的查询条件（有则按原条件）
    const snapshot = pageQueryStore.takeQuery<SysXxxPageQueryQo>(XXX_QUERY_KEY)
    if (snapshot) {
      searchForm.xxxCode = snapshot.xxxCode
      currentPage.value = snapshot.currentPage
      pageSize.value = snapshot.pageSize
    }
    // 1.3 查询首屏列表
    await fetchList()
  } finally {
    // 2. 初始资源全部就绪后关闭页面 loading，完整显示页面
    loading.value = false
  }
})
```

---

## 四、加载与遮罩（要点，完整规则见 PROJECT-STANDARD 第七章）

| 场景 | 规范 |
|---|---|
| 页面进入 | `const loading = ref(true)`，根容器 `v-loading="loading"`，`onMounted` 资源加载与首屏列表 `try/finally` 收尾后才关闭；加载期间遮罩并禁止操作 |
| 表单页（编辑/详情） | 按 `mode` 决定初始态：`ref(mode.value !== FORM_MODE.ADD)`；数据未就绪不显示空表单 |
| 列表请求（搜索/翻页/刷新） | `fetchList` 内部置 `loading`，请求结束 `finally` 关闭 |
| 所有写操作 | `withMask(task, text?)` 包裹（删除「操作中...」、提交「提交中...」、启停「处理中...」、授权「授权中...」、刷新「刷新中...」、重置「重置中...」、详情「数据加载中...」） |
| 弹框组件 | 打开并请求数据时先置 `loading = true`，数据就绪前遮罩，就绪后才完整显示 |

---

## 五、模板书写规范（R7）

### 5.1 列表页结构（固定骨架）

> 可直接运行的完整骨架（含 script 全部方法与注释）：[templates/list-page.vue](templates/list-page.vue)；下列为结构与类名要点。

```vue
<template>
  <div v-loading="loading" class="page-container">
    <!-- 搜索栏 -->
    <div class="search-card">
      <div class="search-row">
        <div class="search-item"><span class="label">编码</span><ElInput v-model="searchForm.xxxCode" placeholder="请输入" clearable /></div>
        <div class="search-actions"><ElButton type="primary" @click="handleSearch">搜索</ElButton><ElButton @click="handleReset">重置</ElButton></div>
      </div>
    </div>

    <!-- 操作栏与表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <div class="toolbar-left">
          <ElButton v-if="hasPermission('SYS_XXX_ADD')" type="primary" @click="handleAdd">新增</ElButton>
          <ElButton v-if="hasPermission('SYS_XXX_DELETE')" type="danger" :disabled="!selectedRows.length" @click="handleBatchDelete">批量删除</ElButton>
        </div>
        <div class="toolbar-right">
          <!-- 平台/字典等筛选控件 -->
        </div>
      </div>

      <ElTable :data="tableData" border @selection-change="handleSelectionChange">
        <ElTableColumn type="selection" width="50" />
        <ElTableColumn prop="xxxCode" label="编码" width="160" />
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="hasPermission('SYS_XXX_DETAIL')" link type="primary" size="small" @click="handleDetail(row)">详情</ElButton>
            <ElButton v-if="hasPermission('SYS_XXX_EDIT')" link type="success" size="small" @click="handleEdit(row)">编辑</ElButton>
            <ElButton v-if="hasPermission('SYS_XXX_DELETE')" link type="danger" size="small" @click="handleDelete(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="pagination-wrap">
        <ElPagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[...APP_CONFIG.pageSizes]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <!-- 详情/编辑弹框 -->
    <ElDialog v-model="editDialogVisible" :title="formTitle" width="500px" :close-on-click-modal="false">
      <ElForm ref="editFormRef" :model="editForm" :rules="isDetail ? {} : editRules" label-width="90px">
        <ElFormItem label="编码" prop="xxxCode">
          <ElInput v-model="editForm.xxxCode" :disabled="isDetail || isEdit" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect class="select-sm" v-model="editForm.enableFlag" :disabled="isDetail">
            <ElOption v-for="opt in ENABLE_FLAG_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editDialogVisible = false">取消</ElButton>
        <ElButton v-if="!isDetail" type="primary" :loading="editLoading" @click="handleEditSubmit">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>
```

### 5.2 模板规则

1. **禁止魔法值**：模板中不得出现业务字面量（`'Y'`/`'N'`、`'DIR'`/`'PAGE'`/`'BUTTON'`、`'C'`/`'P'`、`'M'`/`'F'`、`'add'`/`'edit'`/`'detail'`、中文文案、颜色值、类名等）。
   - 通用映射/取用函数/选项数组 → `src/models/common.ts`
   - 业务映射/文案/选项 → `src/models/pages/{模块}.ts`
   - 需要拼接的字符串用页面 `computed`（如 `formTitle`、`passwordPlaceholder`）
2. **禁止模板三元魔法值**：`isDetail ? '返回' : '取消'` 应改为 `{{ getFormFooterText(isDetail) }}`，弹框标题改为 `:title="formTitle"`。
3. **下拉选项统一 `v-for`**：`<ElOption v-for="opt in ENABLE_FLAG_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />`。
4. **表格状态列统一取展示配置**：
   ```vue
   <ElTag :type="getEnableFlagView(row.enableFlag).tagType" size="small">{{ getEnableFlagView(row.enableFlag).text }}</ElTag>
   <ElButton link :type="getEnableFlagToggle(row.enableFlag).buttonType" @click="handleEnableToggle(row)">{{ getEnableFlagToggle(row.enableFlag).text }}</ElButton>
   ```
5. **按钮权限**：一律 `v-if="hasPermission('资源编码')"`，禁止前端硬编码角色判断。
6. **模板注释**：关键区块用注释标注（`<!-- 搜索栏 -->`、`<!-- 操作栏与表格 -->`、`<!-- 详情/编辑弹框 -->`）。
7. **禁止在模板中写业务逻辑**：计算/转换放 `computed` 或 models 的取用函数。

---

## 六、样式规范（R12）

列表页样式固定为以下结构，禁止引入页面私有颜色/间距魔法值之外的额外规则：

```scss
<style scoped lang="scss">
.page-container {
  .search-card { background: #fff; padding: 20px; border-radius: 8px; margin-bottom: 16px; }
  .search-row { display: flex; flex-wrap: wrap; gap: 16px; align-items: center; }
  .search-item { display: flex; align-items: center; gap: 8px; .label { white-space: nowrap; font-size: 14px; color: #606266; } }
  .search-actions { display: flex; gap: 10px; }
  .table-card { background: #fff; padding: 20px; border-radius: 8px; }
  .table-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
  .toolbar-left { display: flex; gap: 10px; }
  .pagination-wrap { display: flex; justify-content: center; margin-top: 16px; }
}
</style>
```

- 一律 `scoped` + `lang="scss"`；
- 下拉等控件宽度使用全局类 `select-sm` / 配置项，禁止散落像素值；
- 自定义组件样式需加 `:deep()` 穿透。

---

## 七、注释要点（完整规则见 PROJECT-STANDARD 第九章，R9）

1. **每个状态、每个方法必须有注释**；方法用 JSDoc：
   ```typescript
   /**
    * 查询列表
    * @param xxx 参数说明
    */
   ```
2. **方法体内步骤注释**：`// 1.`、`// 2.`、`// 3.`，嵌套用 `// 2.1`：
   ```typescript
   async function handleEditSubmit() {
     // 1. 表单校验，未通过则终止
     // 2. 打开按钮加载态
     // 3. 显示操作幕布提交
     // 4. 提示成功并关闭弹框
     // 5. 刷新列表
   }
   ```
3. **模板分区注释**：`<!-- 搜索栏 -->` 等；
4. **`finally` 中的收尾动作**也要写注释（如「// 2. 初始资源全部就绪后关闭页面 loading，完整显示页面」）。

---

## 八、页面级交互约定（R11）

| 约定 | 做法 |
|---|---|
| 查询条件保留 | 跳转子页面前 `pageQueryStore.saveQuery(KEY, { ...查询条件, currentPage, pageSize, 展开项 })`，返回时 `onMounted` 中 `takeQuery(KEY)` 恢复并按原条件查询 |
| 平台切换刷新 | 平台选择器 `@change` → 重置 `currentPage = 1` → `fetchList()` |
| 字典批量加载 | 页面 `onMounted` 中 `await dictStore.loadDictItems([...])`：缓存优先，仅请求缺失编码 |
| 行内跳转 | 使用页面路由常量（`src/models` 或页面常量），禁止裸路径字符串 |
| 删除确认 | 一律 `showConfirm(...)` 二次确认后再执行 |
| 详情模式 | 复用编辑弹框，`:disabled="isDetail"` + `:rules="isDetail ? {} : editRules"`，标题由 `formTitle` 提供 |
| 错误处理 | `try/catch` + `showError(err.message || '默认文案')`，`finally` 关闭加载态 |
| 权限编码 | 后端资源编码常量（如 `SYS_USER_EDIT`），与 `hasPermission` 配合 |

---

## 九、页面自检清单（页面专项）

> 与 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 第十三章「验收自检清单（全局）」配合使用。

- [ ] `.vue` 中无 `interface` / `class` / 业务常量
- [ ] 查询/编辑表单均为 `models` 类实例（`new SysXxxQueryQo()` / `new SysXxxEditBo()`）
- [ ] 所有 API 入参来自 `toParams()` / `toFormData()`，无内联对象字面量
- [ ] 每次请求都传 `apiCode`（API 模块内已统一处理）
- [ ] `loading` 初始为 `true`，根容器 `v-loading`，`onMounted` 用 `try/finally` 收尾
- [ ] 所有写操作（删除/提交/启停/授权/刷新/重置）均用 `withMask` 包裹
- [ ] 模板无三元魔法值、无内联业务字面量、下拉选项走 `v-for` 常量
- [ ] 展示文案/颜色/标签类型全部取自 `models` 的映射与取用函数
- [ ] 状态与方法的注释、步骤注释齐全
- [ ] 跳转前保存查询条件、返回时恢复
- [ ] 平台切换、搜索、翻页均刷新列表并重置页码
- [ ] 所有按钮有 `hasPermission` 权限控制
- [ ] `pnpm test`、`pnpm build` 通过

---

## 十、参考实现

| 场景 | 参考文件 |
|---|---|
| 标准列表页（含详情/编辑弹框、删除、跳转） | `src/views/system/SysDict.vue` |
| 列表页 + 多弹框（授权、重置密码、附件） | `src/views/system/SysUser.vue` |
| 树形列表页 + 平台切换 + 跳转表单页 | `src/views/system/SysResource.vue` |
| 表单页（新增/编辑/详情三模式、接口选择、图标选择） | `src/views/system/SysResourceEdit.vue` |
| 选择器弹框组件（加载态 + 分页） | `src/components/UserSelect/UserSelectDialog.vue` |
