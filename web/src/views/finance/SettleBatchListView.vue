<script setup lang="ts">
/**
 * 结算批次页（生成 / 列表 / **下发走双人复核**）。
 *
 * 界面口径：
 * - 生成批次来自**已确认**的对账单；
 * - 下发是"真正动钱"的动作，走双人复核 → 页面显示**复核单号**而不是"已付款"；
 * - `下发失败` 的批次要展示失败原因（重试需重走复核流程）。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { SettleApi, type SettleBatchVo } from '@/models/finance'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 查询条件。 */
const query = reactive({ carrierCode: '' })

/** 列表。 */
const rows = ref<SettleBatchVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 生成批次弹窗。 */
const generateVisible = ref(false)

/** 生成批次入参（对账单号）。 */
const generateForm = reactive({ rclNo: '' })

/** 上一次下发的复核单号。 */
const lastApprovalNo = ref('')

/** 批次状态取值（与后端一致）。 */
const StatusWaiting = '待下发'
const StatusFailed = '下发失败'

/**
 * 加载列表。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await SettleApi.list(query.carrierCode || undefined)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 生成批次。
 */
async function submitGenerate(): Promise<void> {
  if (!generateForm.rclNo.trim()) {
    ElMessage.warning('请填写对账单号')
    return
  }
  const result = await withMask(() => SettleApi.generate(generateForm.rclNo.trim()), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success('已生成结算批次')
  generateVisible.value = false
  generateForm.rclNo = ''
  loadList()
}

/**
 * 下发结算（**走双人复核**）。
 *
 * @param row 批次
 */
async function handleDispatch(row: SettleBatchVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `下发将提交双人复核，通过后才会真正付款：${row.stbNo}，金额 ${row.totalAmount}。确认？`,
    '下发结算',
    { type: 'warning', confirmButtonText: '提交复核', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  const result = await withMask(() => SettleApi.dispatch(row.stbNo), MaskText.SUBMIT)
  if (!result) {
    return
  }
  lastApprovalNo.value = result.approvalNo
  ElMessage.success(`已提交双人复核：${result.approvalNo}`)
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="承运商">
          <el-input v-model="query.carrierCode" placeholder="如 SF" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="generateVisible = true">生成批次</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="lastApprovalNo"
        type="success"
        :closable="false"
        show-icon
        :title="`已提交复核：${lastApprovalNo}（通过后才会真正付款）`"
      />
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="stbNo" label="批次号" width="180" />
        <el-table-column prop="carrierCode" label="承运商" width="100" />
        <el-table-column prop="period" label="账期" width="100" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="waybillCount" label="运单数" width="90" />
        <el-table-column prop="totalAmount" label="总金额" width="130" />
        <el-table-column prop="instructionNo" label="通道指令号" width="180" />
        <el-table-column prop="failReason" label="失败原因" show-overflow-tooltip />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === StatusWaiting || row.status === StatusFailed"
              link
              type="primary"
              @click="handleDispatch(row)"
            >
              下发
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="generateVisible" title="由对账单生成结算批次" width="460px">
      <el-form :model="generateForm" label-width="96px">
        <el-form-item label="对账单号">
          <el-input v-model="generateForm.rclNo" placeholder="已确认的对账单号" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="只有「已确认」的对账单才能生成批次" show-icon />
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}
</style>
