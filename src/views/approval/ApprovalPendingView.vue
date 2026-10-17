<script setup lang="ts">
/**
 * 复核待办页（**双人复核的两个界面口径必须在这里落地**）。
 *
 * 1. 详情必须展示 `payloadJson`（审批内容快照）——复核人据此判断"自己要批的是哪一份内容"；
 * 2. **不能自审**：自己发起的单，通过/驳回按钮禁用并给出原因（后端 80002 也会拒，
 *    但让人点了才报错是糟糕的体验）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApprovalApi, ApprovalBizTypeLabel, type ApprovalBizType, type ApprovalVo } from '@/models/approval'
import { useAuthStore } from '@/stores/auth'
import { withMask, MaskText, showSuccess } from '@/utils/mask'
import { showError } from '@/utils/request'

const auth = useAuthStore()

/** 待办列表。 */
const rows = ref<ApprovalVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 业务类型筛选（空 = 全部）。 */
const bizType = ref<ApprovalBizType | ''>('')

/** 详情弹窗。 */
const detailVisible = ref(false)

/** 当前查看的单。 */
const current = ref<ApprovalVo | null>(null)

/**
 * 加载待办。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await ApprovalApi.pending(bizType.value === '' ? undefined : bizType.value)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 打开详情（**展示内容快照**）。
 *
 * @param row 待办单
 */
function openDetail(row: ApprovalVo): void {
  current.value = row
  detailVisible.value = true
}

/**
 * 是否可复核（自己发起的单不能自审）。
 *
 * @param row 待办单
 * @returns 可复核返回 true
 */
function canReview(row: ApprovalVo): boolean {
  return row.applyBy !== auth.userId
}

/**
 * 复核通过。
 *
 * @param row 待办单
 */
async function handleApprove(row: ApprovalVo): Promise<void> {
  // 1. 二次确认（通过后会**立即执行副作用**：改库存/动钱，不可逆）
  const confirmed = await ElMessageBox.confirm(
    `通过后将立即执行：${ApprovalBizTypeLabel[row.bizType]}（金额 ${row.amount ?? '不涉及'}）。确认通过？`,
    '复核通过确认',
    { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  // 2. 提交
  const result = await withMask(() => ApprovalApi.approve(row.id), MaskText.SUBMIT)
  if (!result) {
    return
  }
  showSuccess(`已通过：${result.approvalNo}`)
  detailVisible.value = false
  loadList()
}

/**
 * 驳回（原因必填）。
 *
 * @param row 待办单
 */
async function handleReject(row: ApprovalVo): Promise<void> {
  const input = await ElMessageBox.prompt('请填写驳回原因（发起人据此修改）', '驳回', {
    inputPlaceholder: '例如：数量与现场交接记录不符',
    inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '驳回原因不能为空'),
    confirmButtonText: '确认驳回',
    cancelButtonText: '取消',
  }).catch(() => null)
  if (!input) {
    return
  }
  const done = await withMask(() => ApprovalApi.reject(row.id, input.value), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  ElMessage.success('已驳回')
  detailVisible.value = false
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="业务类型">
          <el-select v-model="bizType" clearable placeholder="全部" style="width: 180px" @change="loadList">
            <el-option
              v-for="(label, code) in ApprovalBizTypeLabel"
              :key="code"
              :label="label"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadList">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="approvalNo" label="审批单号" width="170" />
        <el-table-column prop="bizTypeLabel" label="业务类型" width="110" />
        <el-table-column prop="bizNo" label="业务单号" width="150" />
        <el-table-column prop="amount" label="金额" width="110" />
        <el-table-column prop="reason" label="申请事由" show-overflow-tooltip />
        <el-table-column prop="applyBy" label="发起人" width="90" />
        <el-table-column prop="applyAt" label="发起时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-tooltip v-if="!canReview(row)" content="不能复核自己发起的申请（双人复核）">
              <span><el-button link disabled>通过</el-button></span>
            </el-tooltip>
            <el-button v-else link type="success" @click="handleApprove(row)">通过</el-button>
            <el-button v-if="canReview(row)" link type="danger" @click="handleReject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="复核详情" width="720px">
      <el-descriptions v-if="current" :column="2" border>
        <el-descriptions-item label="审批单号">{{ current.approvalNo }}</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{ current.bizTypeLabel }}</el-descriptions-item>
        <el-descriptions-item label="业务单号">{{ current.bizNo ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="涉及金额">{{ current.amount ?? '不涉及' }}</el-descriptions-item>
        <el-descriptions-item label="发起人">{{ current.applyBy }}</el-descriptions-item>
        <el-descriptions-item label="发起时间">{{ current.applyAt }}</el-descriptions-item>
        <el-descriptions-item label="申请事由" :span="2">{{ current.reason }}</el-descriptions-item>
      </el-descriptions>
      <div class="page__payload">
        <div class="page__payload-title">审批内容快照（**执行时以它为准**）</div>
        <pre class="page__payload-body">{{ current?.payloadJson }}</pre>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <template v-if="current && canReview(current)">
          <el-button type="danger" @click="handleReject(current)">驳回</el-button>
          <el-button type="success" @click="handleApprove(current)">通过并执行</el-button>
        </template>
        <el-alert v-else type="warning" :closable="false" title="自己发起的申请不能自审" show-icon />
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}

.page__payload {
  margin-top: 12px;
}

.page__payload-title {
  margin-bottom: 6px;
  font-weight: 600;
}

.page__payload-body {
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
}
</style>
