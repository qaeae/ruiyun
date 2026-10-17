<script setup lang="ts">
/**
 * 兜底扣减页（扫描到期未回传 + 多退少补）。
 *
 * 背景（`01` §1.2 第 8 行）：揽收后 **7 天**无回传 → 按预估价兜底扣减；运单迟回传后**多退少补**。
 *
 * 界面口径：
 * - 扫描结果是"候选"（含**已扣过**的），必须展示 `alreadyDeducted`，否则操作人会以为漏扣；
 * - 迟回传结算需要填**实际费用**才能算多退少补。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FallbackApi, deductedLabel, type FallbackCandidateVo } from '@/models/finance'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 候选列表。 */
const rows = ref<FallbackCandidateVo[]>([])

/** 加载态（初始 false：本页需要人工点"扫描"才加载，避免一进页面就触发扣款扫描）。 */
const loading = ref(false)

/**
 * 扫描到期未回传。
 */
async function handleSweep(): Promise<void> {
  loading.value = true
  try {
    rows.value = await FallbackApi.sweep()
    const newly = rows.value.filter((item) => !item.alreadyDeducted).length
    ElMessage.success(`扫描完成：候选 ${rows.value.length} 笔，其中本次新扣 ${newly} 笔`)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 迟回传结算（多退少补）。
 *
 * @param row 候选
 */
async function handleSettle(row: FallbackCandidateVo): Promise<void> {
  const input = await ElMessageBox.prompt(
    `运单 ${row.waybillNo} 的实际费用（预估 ${row.estimatedFee}）：超出预估为补收、低于预估为退还`,
    '迟回传结算',
    {
      inputPlaceholder: '如 18.50',
      inputValidator: (value: string) =>
        value && !Number.isNaN(Number(value)) && Number(value) >= 0 ? true : '请填写非负数字',
    },
  ).catch(() => null)
  if (!input) {
    return
  }
  const result = await withMask(() => FallbackApi.settleLateReturn(row.waybillNo, input.value), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已结算：${result.message}（${result.action}）`)
  handleSweep()
}
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="兜底扣减 = 揽收后 7 天未回传时按预估价先扣"
        description="本页点击「扫描」不会立即扣款：落账由服务端幂等键（uk_fee_biz）保证，重复扫描不会重复扣款。"
      />
      <div class="page__actions">
        <el-button type="primary" @click="handleSweep">扫描到期未回传</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="waybillNo" label="运单号" width="160" />
        <el-table-column prop="merchantId" label="商家" width="100" />
        <el-table-column prop="estimatedFee" label="预估费用" width="120" />
        <el-table-column prop="collectedAt" label="揽收时间" width="180" />
        <el-table-column label="扣款状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.alreadyDeducted ? 'info' : 'success'">{{ deductedLabel(row.alreadyDeducted) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleSettle(row)">迟回传结算</el-button>
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

.page__actions {
  margin-top: 12px;
}
</style>
