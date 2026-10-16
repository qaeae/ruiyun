<script setup lang="ts">
/**
 * {模块}管理页面（列表 + 详情/编辑弹框）
 * 用法：复制到 src/views/system/SysXxx.vue，替换 SysXxx / xxx / {模块} 占位符；
 *       在 src/router/index.ts 的 componentMap 注册组件名，在 resourceTree.ts 补 PAGE_ROUTE_FALLBACK。
 * 规则依据：PROJECT-STANDARD R1–R12；结构细则见 page-standard.md
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
import {
  BatchDeleteBo,
  ENABLE_FLAG_OPTIONS,
  SysXxxPageQueryQo,
  SysXxxQueryQo,
  SysXxxEditBo,
  XXX_FORM_TITLE,
  getEnableFlagView,
  getEnableFlagToggle,
  getFormTitle,
  getSubmitResultText
} from '@/models/index.js'
import type { SysXxxListVo } from '@/models/index.js'

const router = useRouter()
const dictStore = useDictStore()
const pageQueryStore = usePageQueryStore()

/** 查询条件快照 key（跳转子页面前保存，返回时恢复） */
const QUERY_KEY = 'sysXxx'

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
/** 搜索表单（模型实例，禁止对象字面量） */
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

// ==================== 表单验证规则 ====================

/** 编辑表单验证规则 */
const editRules = computed<FormRules>(() => ({
  xxxCode: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  xxxName: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}))

// ==================== 业务方法 ====================

/** 查询列表 */
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
    showError('获取{模块}列表失败')
  } finally {
    // 5. 关闭加载状态
    loading.value = false
  }
}

/** 搜索（重置到第 1 页） */
function handleSearch() {
  // 1. 重置页码并重新查询
  currentPage.value = 1
  fetchList()
}

/** 重置查询条件 */
function handleReset() {
  // 1. 重置搜索表单
  searchForm.reset()
  // 2. 重置页码并重新查询
  currentPage.value = 1
  fetchList()
}

/** 翻页 */
function handlePageChange(p: number) {
  // 1. 记录页码并重新查询
  currentPage.value = p
  fetchList()
}

/** 改变每页条数 */
function handleSizeChange(s: number) {
  // 1. 记录每页条数并回到第 1 页重新查询
  pageSize.value = s
  currentPage.value = 1
  fetchList()
}

/** 表格多选变化 */
function handleSelectionChange(rows: SysXxxListVo[]) {
  // 1. 记录选中行
  selectedRows.value = rows
}

/** 新增 */
function handleAdd() {
  // 1. 重置表单为新增默认值
  editForm.reset()
  // 2. 设置弹框模式
  isEdit.value = false
  isDetail.value = false
  editingId.value = ''
  // 3. 打开弹框并清除校验状态
  editDialogVisible.value = true
  setTimeout(() => editFormRef.value?.clearValidate(), 0)
}

/**
 * 打开详情/编辑弹框并回填
 * @param row 列表行
 * @param detail 是否详情只读
 */
function openDialog(row: SysXxxListVo, detail: boolean) {
  // 1. 设置弹框模式与目标ID
  isDetail.value = detail
  isEdit.value = !detail
  editingId.value = detail ? '' : (row.id || '')
  // 2. 回填表单（模型内部用 mapia 映射）
  editForm.fill(row)
  // 3. 打开弹框并清除校验状态
  editDialogVisible.value = true
  setTimeout(() => editFormRef.value?.clearValidate(), 0)
}

/**
 * 查看详情
 * @param row 列表行
 */
function handleDetail(row: SysXxxListVo) {
  // 1. 以只读模式打开弹框
  openDialog(row, true)
}

/**
 * 编辑
 * @param row 列表行
 */
function handleEdit(row: SysXxxListVo) {
  // 1. 以编辑模式打开弹框
  openDialog(row, false)
}

/** 提交新增/编辑 */
async function handleEditSubmit() {
  // 1. 表单校验，未通过则终止
  if (!editFormRef.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  // 2. 打开按钮加载态
  editLoading.value = true
  try {
    // 3. 显示操作幕布提交（编辑传主键）
    await withMask(() => xxxApi.edit(editForm.toParams(editingId.value || undefined)), '提交中...')
    // 4. 提示成功并关闭弹框
    showSuccess(getSubmitResultText(isEdit.value))
    editDialogVisible.value = false
    // 5. 刷新列表
    fetchList()
  } catch (error: unknown) {
    // 6. 异常提示
    const err = error as Error
    showError(err.message || '提交失败')
  } finally {
    // 7. 关闭按钮加载态
    editLoading.value = false
  }
}

/**
 * 启用/停用切换
 * @param row 列表行
 */
async function handleEnableToggle(row: SysXxxListVo) {
  try {
    // 1. 显示操作幕布调用启停接口
    await withMask(() => xxxApi.enableOpt(row.id!), '处理中...')
    // 2. 提示结果并刷新列表
    showSuccess(getEnableFlagToggle(row.enableFlag).resultText)
    fetchList()
  } catch (error: unknown) {
    // 3. 异常提示
    const err = error as Error
    showError(err.message || '操作失败')
  }
}

/**
 * 删除单行
 * @param row 列表行
 */
async function handleDelete(row: SysXxxListVo) {
  // 1. 二次确认
  await showConfirm(`确定要删除"{row.xxxName}"吗？`)
  try {
    // 2. 显示操作幕布并提交批量删除
    await withMask(() => xxxApi.deleteBatch(new BatchDeleteBo([row.id!]).toParams()))
    // 3. 提示成功并刷新列表
    showSuccess('删除成功')
    fetchList()
  } catch (error: unknown) {
    // 4. 异常提示
    const err = error as Error
    showError(err.message || '删除失败')
  }
}

/** 批量删除 */
async function handleBatchDelete() {
  // 1. 未选中时终止
  if (!selectedRows.value.length) return
  // 2. 二次确认
  await showConfirm(`确定要删除选中的 ${selectedRows.value.length} 条数据吗？`)
  try {
    // 3. 显示操作幕布并提交批量删除
    const ids = selectedRows.value.map((row) => row.id!)
    await withMask(() => xxxApi.deleteBatch(new BatchDeleteBo(ids).toParams()))
    // 4. 提示成功并刷新列表
    showSuccess('删除成功')
    fetchList()
  } catch (error: unknown) {
    // 5. 异常提示
    const err = error as Error
    showError(err.message || '删除失败')
  }
}

// ==================== 生命周期 ====================

onMounted(async () => {
  // 1. 页面初始资源加载（loading 初始为 true，全程遮罩并禁止操作）
  try {
    // 1.1 批量加载字典（缓存优先，仅请求缺失编码）
    await dictStore.loadDictItems(['PLATFORM'])
    // 1.2 从子页面返回时恢复跳转前保存的查询条件
    const snapshot = pageQueryStore.takeQuery<SysXxxPageQueryQo>(QUERY_KEY)
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
</script>

<template>
  <div v-loading="loading" class="page-container">
    <!-- 搜索栏 -->
    <div class="search-card">
      <div class="search-row">
        <div class="search-item"><span class="label">编码</span><ElInput v-model="searchForm.xxxCode" placeholder="请输入" clearable /></div>
        <div class="search-item"><span class="label">名称</span><ElInput v-model="searchForm.xxxName" placeholder="请输入" clearable /></div>
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
      </div>

      <ElTable :data="tableData" border @selection-change="handleSelectionChange">
        <ElTableColumn type="selection" width="50" />
        <ElTableColumn prop="xxxCode" label="编码" width="160" />
        <ElTableColumn prop="xxxName" label="名称" min-width="160" />
        <ElTableColumn prop="enableFlag" label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="getEnableFlagView(row.enableFlag).tagType" size="small">{{ getEnableFlagView(row.enableFlag).text }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="hasPermission('SYS_XXX_DETAIL')" link type="primary" size="small" @click="handleDetail(row)">详情</ElButton>
            <ElButton v-if="hasPermission('SYS_XXX_EDIT')" link type="success" size="small" @click="handleEdit(row)">编辑</ElButton>
            <ElButton v-if="hasPermission('SYS_XXX_ENABLE')" link :type="getEnableFlagToggle(row.enableFlag).buttonType" size="small" @click="handleEnableToggle(row)">{{ getEnableFlagToggle(row.enableFlag).text }}</ElButton>
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
        <ElFormItem label="名称" prop="xxxName">
          <ElInput v-model="editForm.xxxName" :disabled="isDetail" />
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
