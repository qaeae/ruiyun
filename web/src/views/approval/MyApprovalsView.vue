<script setup lang="ts">
/**
 * 我的申请页（发起人视角：查看进度、撤回）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApprovalApi, type ApprovalVo } from '@/models/approval'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 列表。 */
const rows = ref<ApprovalVo[]>([])

/** 加载态。 */
const loading = ref(true)

/**
 * 加载列表。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await ApprovalApi.mine()
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 撤回（仅「待复核」且仅发起人本人）。
 *
 * @param row 申请
 */
async function handleWithdraw(row: ApprovalVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确认撤回 ${row.approvalNo}？`, '撤回确认', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) {
    return
  }
  const done = await withMask(() => ApprovalApi.withdraw(row.id), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  ElMessage.success('已撤回')
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-table :data="rows" border stripe>
        <el-table-column prop="approvalNo" label="审批单号" width="170" />
        <el-table-column prop="bizTypeLabel" label="业务类型" width="110" />
        <el-table-column prop="bizNo" label="业务单号" width="150" />
        <el-table-column prop="amount" label="金额" width="110" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="rejectReason" label="驳回原因" show-overflow-tooltip />
        <el-table-column prop="applyAt" label="发起时间" width="180" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === '待复核'" link type="danger" @click="handleWithdraw(row)">
              撤回
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
