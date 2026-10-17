<script setup lang="ts">
/**
 * 任务运维页（任务一览 / 执行历史 / 手工补跑 / 启停）。
 *
 * 界面口径（前置包 §4.5，都是踩过的坑）：
 * - **「跳过」不是失败**：多实例下每个周期约一半实例会跳过，若标红会造成告警疲劳；
 * - `lastStatus` 停留在「执行中」= 任务卡死或被杀（这是"先写开始、结束再更新"留下的痕迹）；
 * - 补跑与定时**共用同一段代码**（含租约），因此补跑成功基本等价于定时能成功。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  JobApi,
  JobStatus,
  TriggerType,
  jobEnabledLabel,
  jobToggleLabel,
  type JobRunVo,
  type JobVo,
} from '@/models/job'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 任务列表。 */
const jobs = ref<JobVo[]>([])

/** 执行历史。 */
const runs = ref<JobRunVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 历史查询条件。 */
const runQuery = reactive({ jobName: '', limit: 50 })

/** 历史弹窗。 */
const runVisible = ref(false)

/**
 * 加载任务一览。
 */
async function loadJobs(): Promise<void> {
  loading.value = true
  try {
    jobs.value = await JobApi.list()
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 查看执行历史。
 *
 * @param jobName 任务名（可空 = 全部）
 */
async function openRuns(jobName: string): Promise<void> {
  runQuery.jobName = jobName
  try {
    runs.value = await JobApi.runs(jobName || undefined, runQuery.limit)
    runVisible.value = true
  } catch (error) {
    showError(error)
  }
}

/**
 * 手工补跑。
 *
 * @param row 任务
 */
async function handleRun(row: JobVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `补跑「${row.displayName}」会立即执行真实业务动作（扣款/勾稽/告警）。确认？`,
    '手工补跑',
    { type: 'warning', confirmButtonText: '补跑', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  const result = await withMask(() => JobApi.run(row.jobName), MaskText.SUBMIT)
  if (!result) {
    return
  }
  // 跳过不是失败：分开提示，避免操作人以为出错了
  if (result.status === JobStatus.SKIPPED) {
    ElMessage.warning(`未执行（${result.message}）`)
  } else if (result.status === JobStatus.FAILED) {
    ElMessage.error(`执行失败：${result.message}`)
  } else {
    ElMessage.success(`执行成功：${result.message}`)
  }
  loadJobs()
}

/**
 * 启停任务。
 *
 * @param row 任务
 */
async function handleToggle(row: JobVo): Promise<void> {
  const next = !row.enabled
  const done = await withMask(() => JobApi.setEnabled(row.jobName, next), MaskText.TOGGLE)
  if (done === undefined) {
    return
  }
  ElMessage.success(next ? '已启用' : '已停用')
  loadJobs()
}

/**
 * 状态标签类型（R7：函数内判断，模板不写三元链）。
 *
 * @param status 状态
 * @returns 标签类型
 */
function statusTagType(status?: string): 'success' | 'danger' | 'info' | 'warning' {
  if (status === JobStatus.SUCCESS) {
    return 'success'
  }
  if (status === JobStatus.FAILED) {
    return 'danger'
  }
  if (status === JobStatus.SKIPPED) {
    return 'info'
  }
  return 'warning'
}

/**
 * 触发方式文案。
 *
 * @param triggerType 触发方式
 * @returns 文案
 */
function triggerLabel(triggerType: string): string {
  return triggerType === TriggerType.MANUAL ? '手工补跑' : '定时'
}

onMounted(loadJobs)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="「跳过」不是失败：多实例部署下未抢到租约或任务已停用都会记为跳过；「执行中」长期不结束 = 任务卡死或被强杀"
      />
      <div class="page__actions">
        <el-button @click="openRuns('')">全部执行历史</el-button>
        <el-button @click="loadJobs">刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="jobs" border stripe>
        <el-table-column prop="displayName" label="任务" width="150" />
        <el-table-column prop="jobName" label="任务名" width="190" />
        <el-table-column prop="cronDesc" label="周期" width="130" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ jobEnabledLabel(row.enabled) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上次结果" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.lastStatus" :type="statusTagType(row.lastStatus)">{{ row.lastStatus }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastStartAt" label="上次开始" width="180" />
        <el-table-column prop="lastEndAt" label="上次结束" width="180" />
        <el-table-column prop="successCount" label="成功次数" width="90" />
        <el-table-column prop="failCount" label="失败次数" width="90" />
        <el-table-column prop="leaseOwner" label="当前执行实例" width="160" />
        <el-table-column prop="lastMessage" label="上次摘要" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleRun(row)">补跑</el-button>
            <el-button link @click="openRuns(row.jobName)">历史</el-button>
            <el-button link :type="row.enabled ? 'danger' : 'success'" @click="handleToggle(row)">
              {{ jobToggleLabel(row.enabled) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="runVisible" title="执行历史" width="900px">
      <el-table :data="runs" border>
        <el-table-column prop="jobName" label="任务名" width="180" />
        <el-table-column label="触发方式" width="110">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始" width="180" />
        <el-table-column prop="costMs" label="耗时(ms)" width="100" />
        <el-table-column prop="operatorId" label="操作人" width="90" />
        <el-table-column prop="message" label="摘要/异常" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="runVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}

.page__actions {
  margin-top: 12px;
}
</style>
