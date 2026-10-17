<script setup lang="ts">
/**
 * 对账单页（列表 / 详情明细 / 差异处理 / 确认）。
 *
 * 界面口径：
 * - **差异处理结论与责任方由后端规则给（1 元 / 1% / 20% 档位）**，前端只展示，不自己判档；
 * - 确认后差异不可再改，因此确认要二次确认。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ReconcileApi,
  ReconcileHandleQo,
  type ReconcileItemVo,
  type ReconcileLiability,
  type ReconcileVo,
} from '@/models/finance'
import { withMask, MaskText, showSuccess } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 查询条件。 */
const query = reactive({ carrierCode: '', period: '' })

/** 列表。 */
const rows = ref<ReconcileVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 详情弹窗。 */
const detailVisible = ref(false)

/** 当前详情（含明细）。 */
const current = ref<ReconcileVo | null>(null)

/** 差异处理弹窗。 */
const handleVisible = ref(false)

/** 待处理明细。 */
const handleTarget = ref<ReconcileItemVo | null>(null)

/** 处理表单。 */
const handleForm = reactive(new ReconcileHandleQo())

/** 责任方选项（R7：选项来自 models 常量，模板不硬编码）。 */
const liabilityOptions: ReconcileLiability[] = ['平台', '商家', '物流']

/**
 * 加载列表。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await ReconcileApi.list(query.carrierCode || undefined, query.period || undefined)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 打开详情。
 *
 * @param row 对账单
 */
async function openDetail(row: ReconcileVo): Promise<void> {
  try {
    current.value = await ReconcileApi.detail(row.rclNo)
    detailVisible.value = true
  } catch (error) {
    showError(error)
  }
}

/**
 * 打开差异处理。
 *
 * @param item 明细
 */
function openHandle(item: ReconcileItemVo): void {
  handleTarget.value = item
  handleForm.liability = '平台'
  handleForm.remark = ''
  handleVisible.value = true
}

/**
 * 提交差异处理。
 */
async function submitHandle(): Promise<void> {
  if (!current.value || !handleTarget.value) {
    return
  }
  if (!handleForm.remark.trim()) {
    ElMessage.warning('处理说明不能为空')
    return
  }
  const done = await withMask(
    () => ReconcileApi.handleItem(current.value!.rclNo, handleTarget.value!.id, handleForm),
    MaskText.SUBMIT,
  )
  if (!done) {
    return
  }
  showSuccess('差异已处理')
  handleVisible.value = false
  await openDetail(current.value)
  loadList()
}

/**
 * 确认对账单。
 *
 * @param row 对账单
 */
async function handleConfirm(row: ReconcileVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确认后 ${row.rclNo} 的差异不可再修改，且可据此生成结算批次。确认？`,
    '确认对账单',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  const done = await withMask(() => ReconcileApi.confirm(row.rclNo), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  showSuccess('已确认')
  detailVisible.value = false
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
        <el-form-item label="账期">
          <el-input v-model="query.period" placeholder="yyyyMM，如 202610" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="rclNo" label="对账单号" width="180" />
        <el-table-column prop="carrierCode" label="承运商" width="100" />
        <el-table-column prop="period" label="账期" width="100" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="totalWaybills" label="运单数" width="90" />
        <el-table-column prop="platformAmount" label="平台金额" width="120" />
        <el-table-column prop="carrierAmount" label="承运商金额" width="130" />
        <el-table-column prop="diffAmount" label="差额" width="110" />
        <el-table-column prop="autoCount" label="自动平账" width="100" />
        <el-table-column prop="manualCount" label="待人工" width="90" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">明细</el-button>
            <el-button
              v-if="row.status === '差异待处理'"
              link
              type="success"
              @click="handleConfirm(row)"
            >
              确认
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="对账明细" width="960px">
      <el-alert
        v-if="current"
        type="info"
        :closable="false"
        show-icon
        :title="`差异档位由服务端判定：≤1 元或 ≤1% 自动平账；>1% 挂账待处理；>20% 强制人工`"
      />
      <el-table v-if="current" :data="current.items ?? []" border class="page__items">
        <el-table-column prop="waybillNo" label="运单号" width="150" />
        <el-table-column prop="platformFee" label="平台计费" width="100" />
        <el-table-column prop="carrierFee" label="承运商计费" width="110" />
        <el-table-column prop="diffAmount" label="差额" width="90" />
        <el-table-column prop="diffRate" label="差异率" width="90" />
        <el-table-column prop="diffType" label="差异类型" width="130" />
        <el-table-column prop="handleResult" label="处理结论" width="110" />
        <el-table-column prop="liability" label="责任方" width="90" />
        <el-table-column prop="remark" label="说明" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.handleResult === '人工处理' || row.handleResult === '挂账待处理'"
              link
              type="primary"
              @click="openHandle(row)"
            >
              处理
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="handleVisible" title="差异处理" width="520px">
      <el-form :model="handleForm" label-width="88px">
        <el-form-item label="运单号">
          <span>{{ handleTarget?.waybillNo }}</span>
        </el-form-item>
        <el-form-item label="差额">
          <span>{{ handleTarget?.diffAmount }}（{{ handleTarget?.diffRate }}）</span>
        </el-form-item>
        <el-form-item label="责任方">
          <el-select v-model="handleForm.liability" style="width: 100%">
            <el-option v-for="item in liabilityOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="handleForm.remark" type="textarea" :rows="3" placeholder="说明差异原因与依据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}

.page__items {
  margin-top: 12px;
}
</style>
