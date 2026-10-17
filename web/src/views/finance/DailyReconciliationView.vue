<script setup lang="ts">
/**
 * 日终勾稽页（四层：资金 / 业务 / 三方 / 库存）。
 *
 * 界面口径：
 * - **「待接入」不是差异**（缺通道或承运商数据时后端记为待接入），若把两者混在一起展示，
 *   运维会把"没数据"当成"账不对"，天天白排查——所以状态列要明确区分；
 * - 默认查 **T-1**（当天数据还在变，勾稽结果不可复现）。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DailyReconciliationApi, DailyReconcileStatus, type DailyReconciliationVo } from '@/models/finance'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 查询条件（默认 T-1）。 */
const query = reactive({ bizDate: yesterday() })

/** 四层结果。 */
const rows = ref<DailyReconciliationVo[]>([])

/** 加载态。 */
const loading = ref(true)

/**
 * 昨天（yyyy-MM-dd）。
 *
 * @returns 日期字符串
 */
function yesterday(): string {
  const date = new Date()
  date.setDate(date.getDate() - 1)
  return date.toISOString().slice(0, 10)
}

/**
 * 查询。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await DailyReconciliationApi.query(query.bizDate)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 执行勾稽（幂等：同一天重跑覆盖同一天结论）。
 */
async function handleRun(): Promise<void> {
  const result = await withMask(() => DailyReconciliationApi.run(query.bizDate), MaskText.SUBMIT)
  if (!result) {
    return
  }
  rows.value = result
  ElMessage.success(`已完成 ${query.bizDate} 的四层勾稽`)
}

/**
 * 处理差异。
 *
 * @param row 勾稽记录
 */
async function handleDiff(row: DailyReconciliationVo): Promise<void> {
  const input = await ElMessageBox.prompt('请填写处理说明（归档留痕）', '处理差异', {
    inputPlaceholder: '例如：通道文件缺 2 笔，已向机构发起查询',
    inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '处理说明不能为空'),
  }).catch(() => null)
  if (!input) {
    return
  }
  const done = await withMask(() => DailyReconciliationApi.handle(row.id, input.value), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  ElMessage.success('已记录处理说明')
  loadList()
}

/** 状态标签类型（R7：用函数而不是模板内三元）。 */
function statusTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === DailyReconcileStatus.MATCH) {
    return 'success'
  }
  return status === DailyReconcileStatus.DIFF ? 'danger' : 'info'
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="业务日期">
          <el-date-picker v-model="query.bizDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="handleRun">执行勾稽</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="默认跑 T-1：当天数据还在变，勾稽结果不可复现；「待接入」表示缺通道/三方数据，不计入差异告警"
      />
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="bizDate" label="业务日期" width="120" />
        <el-table-column prop="layer" label="层" width="90" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="platformAmount" label="平台侧金额" width="140" />
        <el-table-column prop="channelAmount" label="通道/三方金额" width="150" />
        <el-table-column prop="diffAmount" label="差额" width="120" />
        <el-table-column prop="diffCount" label="差异笔数" width="100" />
        <el-table-column prop="handledBy" label="处理人" width="90" />
        <el-table-column prop="detailJson" label="明细" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === DailyReconcileStatus.DIFF && !row.handledBy"
              link
              type="primary"
              @click="handleDiff(row)"
            >
              处理
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}
</style>
