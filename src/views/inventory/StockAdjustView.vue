<script setup lang="ts">
/**
 * 库存调整页（**提交后不直接改库存，而是生成复核单**）。
 *
 * 为什么界面要这样表达：调整是敏感动作（改的是账），制度要求**双人复核**。
 * 若提交后提示"调整成功"，用户会以为库存已经改了——那是最危险的误解。
 *
 * 联调确认的报文字段：`warehouseId/merchantId/skuId/locationId/bizType` 必填，
 * `qty` 可正可负（盘亏填负数），ID 均为**字符串**。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { InventoryApi, StockOpQo, type AdjustSubmitVo } from '@/models/inventory'
import { withMask, MaskText } from '@/utils/mask'

/** 表单模型。 */
const form = reactive(new StockOpQo())

/** 最后一次提交结果（展示复核单号）。 */
const lastSubmit = ref<AdjustSubmitVo | null>(null)

/**
 * 提交调整申请。
 */
async function handleSubmit(): Promise<void> {
  // 1. 必填校验（后端也会校验；前端先拦一次，避免用户白等一次往返）
  if (!form.warehouseId || !form.merchantId || !form.skuId || !form.locationId) {
    ElMessage.warning('仓库、商家、商品、库位都为必填（后端按这些键定位库存）')
    return
  }
  if (form.qty === undefined || form.qty === null || Number.isNaN(Number(form.qty))) {
    ElMessage.warning('请填写调整数量（盘亏填负数）')
    return
  }
  if (Number(form.qty) === 0) {
    ElMessage.warning('调整数量不能为 0')
    return
  }
  // 2. 提交（幂等键由请求层按"方法+URL+负载"自动生成并在重试时复用）
  const result = await withMask(() => InventoryApi.submitAdjust(form), MaskText.SUBMIT)
  if (!result) {
    return
  }
  lastSubmit.value = result
  ElMessage.success('已提交双人复核，通过后才会真正调整库存')
}
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="库存调整需要他人复核"
        description="提交后生成复核单；由另一位有复核权限的同事通过后才会真正调整库存（自己不能复核自己）。"
      />
      <el-form :model="form" label-width="120px" class="page__form">
        <el-form-item label="仓库 ID">
          <el-input v-model="form.warehouseId" placeholder="雪花 ID（字符串）" />
        </el-form-item>
        <el-form-item label="商家 ID">
          <el-input v-model="form.merchantId" placeholder="雪花 ID（字符串）" />
        </el-form-item>
        <el-form-item label="商品 ID">
          <el-input v-model="form.skuId" placeholder="雪花 ID（字符串）" />
        </el-form-item>
        <el-form-item label="库位 ID">
          <el-input v-model="form.locationId" placeholder="雪花 ID（字符串）" />
        </el-form-item>
        <el-form-item label="调整数量">
          <el-input-number v-model="form.qty" :precision="4" placeholder="盘亏填负数，如 -5" />
        </el-form-item>
        <el-form-item label="业务类型">
          <el-input v-model="form.bizType" placeholder="MANUAL / STOCKTAKE 等" />
        </el-form-item>
        <el-form-item label="业务单号">
          <el-input v-model="form.bizNo" placeholder="可空，如盘点单号" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="复核人据此判断要批的是哪一份内容" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSubmit">提交复核</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="lastSubmit" shadow="never" class="page__result">
      <el-result icon="success" title="已提交双人复核" :sub-title="lastSubmit.message">
        <template #extra>
          <el-descriptions :column="1" border style="width: 460px">
            <el-descriptions-item label="复核单号">{{ lastSubmit.approvalNo }}</el-descriptions-item>
          </el-descriptions>
          <p class="page__tip">到「复核待办」页由另一位同事通过后，库存才会变化。</p>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.page__form {
  margin-top: 16px;
}

.page__result {
  margin-top: 12px;
}

.page__tip {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
