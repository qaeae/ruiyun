<script setup lang="ts">
/**
 * 库存查询页（**按真实契约实现**：定位一个 SKU，看它的三态、两级账、库位分布与流水）。
 *
 * ## 为什么不是"列表 + 筛选"
 *
 * 联调确认后端 `GET /inventory/sku` 需要 `warehouseId + merchantId + skuId` **三个必填参数**，
 * 返回**单个**三态对象——没有"库存列表"接口。这不是缺接口，而是库存管理的真实用法：
 * 你总是先知道要查哪个商品（从盘点/拣货/客诉进来），再看它的账。
 *
 * ## 页面要表达的两件事
 *
 * 1. **第一级账**：实物 = 可用 + 预留（前端也校验一次，一眼可见是否自洽）；
 * 2. **第二级账**：SKU 三态 vs 库位合计（后端算好的 `twoLevelBalanced`）——
 *    两级都平才算账对得上；只平一级说明"某一级被绕过接口改过"。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  InventoryApi,
  balanceLabel,
  isBalanced,
  twoLevelLabel,
  type LocationStockVo,
  type MovementVo,
  type StockVo,
} from '@/models/inventory'
import { formatQty, formatTime } from '@/models/common'
import { useAuthStore } from '@/stores/auth'
import { showError } from '@/utils/request'

const auth = useAuthStore()

/** 定位条件（三个 ID 都是雪花字符串，直接透传，不做数值转换）。 */
const query = reactive({ warehouseId: '', merchantId: '', skuId: '' })

/** 三态（未查询时为 null）。 */
const stock = ref<StockVo | null>(null)

/** 库位分布。 */
const locations = ref<LocationStockVo[]>([])

/** 流水。 */
const movements = ref<MovementVo[]>([])

/** 加载态（R5：初始 false —— 本页需要先输入条件才查，避免无参数请求）。 */
const loading = ref(false)

/**
 * 查询：三态 + 库位 + 流水一起拉（同一次交互，三个请求属于一件事）。
 */
async function handleQuery(): Promise<void> {
  if (!query.warehouseId || !query.merchantId || !query.skuId) {
    ElMessage.warning('仓库、商家、商品三个 ID 都为必填（后端按这三个键定位库存）')
    return
  }
  loading.value = true
  try {
    // 1. 三态（主信息）
    stock.value = await InventoryApi.getSku(query.warehouseId, query.merchantId, query.skuId)
    // 2. 库位分布与流水（次信息，失败不影响三态展示）
    const [locationList, movementList] = await Promise.all([
      InventoryApi.listLocations(query.warehouseId, query.skuId),
      InventoryApi.listMovements(query.warehouseId, query.skuId, undefined, undefined, 50),
    ])
    locations.value = locationList
    movements.value = movementList
  } catch (error) {
    stock.value = null
    locations.value = []
    movements.value = []
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 不变量检查（四层对账里"库存层"的依据）。
 */
async function handleInvariantCheck(): Promise<void> {
  loading.value = true
  try {
    const result = await InventoryApi.invariantCheck()
    const detail = result.items.map((item) => `${item.name}=${item.violation}`).join('；')
    if (result.allPassed) {
      ElMessage.success(`库存不变量全部通过（${result.items.length} 项）`)
    } else {
      ElMessage.error(`库存不变量有 ${result.failedCount} 项不通过：${detail}`)
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 流水数量展示（带符号）。
 *
 * @param qty 数量
 * @returns 展示文案
 */
function signedQty(qty: number): string {
  const text = formatQty(qty)
  return Number(qty) > 0 ? `+${text}` : text
}

onMounted(() => {
  // 1. 只有一个授权仓库时预填，减少输入
  const visible = auth.visibleWarehouseIds
  if (visible.length === 1) {
    query.warehouseId = visible[0]
  }
})
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="仓库 ID">
          <el-input v-model="query.warehouseId" placeholder="雪花 ID（字符串）" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="商家 ID">
          <el-input v-model="query.merchantId" placeholder="雪花 ID（字符串）" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="商品 ID">
          <el-input v-model="query.skuId" placeholder="雪花 ID（字符串）" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery">查询</el-button>
          <el-button @click="handleInvariantCheck">不变量检查</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="ID 是雪花字符串，请勿手工改成数字：19 位整数超出 JS 安全范围，转成数字会丢精度（联调时真实踩过）"
      />
    </el-card>

    <template v-if="stock">
      <el-card shadow="never" class="page__block">
        <template #header>
          <span>三态（SKU {{ stock.skuId }}）</span>
        </template>
        <el-row :gutter="12">
          <el-col :span="6">
            <el-statistic title="实物（= 可用 + 预留）" :value="Number(stock.physicalQty)" />
          </el-col>
          <el-col :span="6">
            <el-statistic title="可用" :value="Number(stock.availableQty)" />
          </el-col>
          <el-col :span="6">
            <el-statistic title="预留" :value="Number(stock.reservedQty)" />
          </el-col>
          <el-col :span="6">
            <el-statistic title="库位实物合计（第二级账）" :value="Number(stock.locationQtySum)" />
          </el-col>
        </el-row>
        <div class="page__checks">
          <el-tag :type="isBalanced(stock) ? 'success' : 'danger'">
            第一级（实物=可用+预留）：{{ balanceLabel(stock) }}
          </el-tag>
          <el-tag :type="stock.twoLevelBalanced ? 'success' : 'danger'">
            第二级（SKU 三态 vs 库位合计）：{{ twoLevelLabel(stock.twoLevelBalanced) }}
          </el-tag>
          <span class="page__hint">两级都平衡才算账对得上；只平一级说明某一级被绕过接口改过</span>
        </div>
      </el-card>

      <el-card shadow="never" class="page__block">
        <template #header>
          <span>库位分布（作业视角）</span>
        </template>
        <el-table :data="locations" border stripe>
          <el-table-column prop="locationId" label="库位 ID" width="220" />
          <el-table-column prop="skuId" label="商品 ID" width="220" />
          <el-table-column label="实物" width="120">
            <template #default="{ row }">{{ formatQty(row.physicalQty) }}</template>
          </el-table-column>
          <el-table-column label="已分配（拣货占用）" width="160">
            <template #default="{ row }">{{ formatQty(row.allocatedQty) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="page__block">
        <template #header>
          <span>库存流水（最近 50 条）</span>
        </template>
        <el-table :data="movements" border stripe>
          <el-table-column prop="movementNo" label="流水号" width="220" />
          <el-table-column prop="movementType" label="类型" width="130" />
          <el-table-column label="数量" width="100">
            <template #default="{ row }">{{ signedQty(row.qty) }}</template>
          </el-table-column>
          <el-table-column label="变动后实物" width="120">
            <template #default="{ row }">{{ formatQty(row.physicalAfter) }}</template>
          </el-table-column>
          <el-table-column label="变动后可用" width="120">
            <template #default="{ row }">{{ formatQty(row.availableAfter) }}</template>
          </el-table-column>
          <el-table-column prop="bizType" label="业务类型" width="130" />
          <el-table-column prop="bizNo" label="业务单号" width="180" />
          <el-table-column prop="deviceCode" label="设备" width="120" />
          <el-table-column label="发生时间" width="180">
            <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page__block {
  margin-top: 12px;
}

.page__checks {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.page__hint {
  color: #909399;
  font-size: 12px;
}
</style>
