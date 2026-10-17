<script setup lang="ts">
/**
 * 垫付台账页（列表 / 登记 / **限额检查** / 核销）。
 *
 * 界面口径（`04` §七 + `AdvancePaymentRule`）：
 * - 限额是**预判**语义：登记前先查「已有累计 + 本笔」是否超限（¥2000 或连续 3 单）；
 * - **触发限额不是失败**，而是"需要人工审批"——界面要区分这两种语义，否则会误导操作人。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AdvanceApi, AdvanceRegisterQo, type AdvanceLimitVo, type AdvanceVo } from '@/models/finance'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 查询条件（商家）。 */
const query = reactive<{ merchantId?: string }>({})

/** 列表。 */
const rows = ref<AdvanceVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 限额信息。 */
const limitInfo = ref<AdvanceLimitVo | null>(null)

/** 登记弹窗。 */
const registerVisible = ref(false)

/** 登记表单。 */
const registerForm = reactive(new AdvanceRegisterQo())

/**
 * 加载台账与限额（有商家时）。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await AdvanceApi.list(query.merchantId)
    if (query.merchantId) {
      limitInfo.value = await AdvanceApi.limit(query.merchantId)
    } else {
      limitInfo.value = null
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 打开登记（预填查询的商家）。
 */
function openRegister(): void {
  registerForm.merchantId = query.merchantId
  registerVisible.value = true
}

/**
 * 提交登记。
 */
async function submitRegister(): Promise<void> {
  if (!registerForm.waybillNo.trim() || !registerForm.merchantId) {
    ElMessage.warning('运单号与商家为必填')
    return
  }
  const result = await withMask(() => AdvanceApi.register(registerForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success('垫付已登记')
  registerVisible.value = false
  loadList()
}

/**
 * 核销垫付。
 *
 * @param row 垫付记录
 */
async function handleClear(row: AdvanceVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确认核销运单 ${row.waybillNo} 的垫付？`, '核销确认', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) {
    return
  }
  const done = await withMask(() => AdvanceApi.clear(row.waybillNo), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  ElMessage.success('已核销')
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="商家 ID">
          <el-input v-model="query.merchantId" placeholder="商家 ID（雪花字符串）" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="openRegister">登记垫付</el-button>
        </el-form-item>
      </el-form>

      <!-- 限额：触发限额是「需人工审批」，不是失败，界面必须区分 -->
      <el-alert
        v-if="limitInfo"
        :type="limitInfo.limited ? 'warning' : 'success'"
        :closable="false"
        show-icon
        :title="
          limitInfo.limited
            ? `已触发垫付限额（需人工审批）：${limitInfo.message}`
            : `垫付额度正常：${limitInfo.message}`
        "
        :description="`累计 ${limitInfo.cumulativeAdvance} / 上限 ${limitInfo.limitAmount}；连续 ${limitInfo.consecutiveCount} 笔 / 上限 ${limitInfo.limitCount} 笔`"
      />
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="waybillNo" label="运单号" width="160" />
        <el-table-column prop="merchantId" label="商家" width="100" />
        <el-table-column prop="amount" label="垫付金额" width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="billingPeriod" label="账期" width="100" />
        <el-table-column prop="occurredAt" label="发生时间" width="180" />
        <el-table-column prop="clearedAt" label="核销时间" width="180" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.clearedAt" link type="primary" @click="handleClear(row)">核销</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="registerVisible" title="登记垫付" width="480px">
      <el-form :model="registerForm" label-width="96px">
        <el-form-item label="运单号">
          <el-input v-model="registerForm.waybillNo" placeholder="运单号" />
        </el-form-item>
        <el-form-item label="商家 ID">
          <el-input v-model="registerForm.merchantId" placeholder="商家 ID" />
        </el-form-item>
        <el-form-item label="垫付金额">
          <el-input v-model="registerForm.amount" placeholder="如 12.50" />
        </el-form-item>
        <el-form-item label="账期">
          <el-input v-model="registerForm.billingPeriod" placeholder="yyyyMM（可空）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="registerForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRegister">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}
</style>
