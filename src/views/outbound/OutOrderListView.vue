<script setup lang="ts">
/**
 * 出库单列表页（API-OUT-01 建单 / API-OUT-13 分页）。
 *
 * 界面口径：
 * - **建单要冻结运费**：预估运费不足会直接被后端拦成 `20001`，所以运费是必填且不做前端试算；
 * - **列表只做查询与建单**：派发/拣货/称重/打包/交接/装车/取消/拦截都在详情页按状态机渲染，
 *   列表上不给"一步到装车"的捷径——装车是库存出账的不可逆点，必须在能看到明细与轨迹的页面里确认；
 * - 状态筛选用**中文枚举**（后端落库即中文），选项来自 `models/outbound`，模板不硬编码。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CreateOutOrderItemQo,
  CreateOutOrderQo,
  OUT_ORDER_SOURCE_OPTIONS,
  OUT_ORDER_STATUS_OPTIONS,
  OutboundApi,
  OutOrderSourceType,
  OutOrderQueryQo,
  sameDayLabel,
  statusTagType,
  type OutOrderVo,
} from '@/models/outbound'
import { formatTime } from '@/models/common'
import { useAuthStore } from '@/stores/auth'
import { MaskText, showSuccess, withMask } from '@/utils/mask'
import { showError } from '@/utils/request'

const router = useRouter()
const auth = useAuthStore()

/** 查询条件。 */
const query = reactive(new OutOrderQueryQo())

/** 列表数据。 */
const rows = ref<OutOrderVo[]>([])

/** 总条数。 */
const total = ref(0)

/** 加载态（R5：首屏即为 true）。 */
const loading = ref(true)

/** 建单弹窗。 */
const createVisible = ref(false)

/** 建单表单。 */
const createForm = reactive(new CreateOutOrderQo())

/**
 * 加载列表。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    const page = await OutboundApi.page(query)
    rows.value = page.list ?? []
    total.value = page.total ?? 0
  } catch (error) {
    rows.value = []
    total.value = 0
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 重置查询。
 */
async function handleReset(): Promise<void> {
  query.status = undefined
  query.doNo = undefined
  query.warehouseId = undefined
  query.merchantId = undefined
  query.pageNum = 1
  await loadList()
}

/**
 * 翻页。
 *
 * @param page 新页码
 */
async function handlePageChange(page: number): Promise<void> {
  query.pageNum = page
  await loadList()
}

/**
 * 改每页条数。
 *
 * @param size 每页条数
 */
async function handleSizeChange(size: number): Promise<void> {
  query.pageSize = size
  query.pageNum = 1
  await loadList()
}

/** 打开建单弹窗（重置表单）。 */
function openCreate(): void {
  Object.assign(createForm, new CreateOutOrderQo())
  createForm.warehouseId = auth.visibleWarehouseIds.length === 1 ? auth.visibleWarehouseIds[0] : ''
  createVisible.value = true
}

/** 加一行明细。 */
function addItem(): void {
  createForm.items.push(new CreateOutOrderItemQo())
}

/**
 * 删一行明细（至少保留一行）。
 *
 * @param index 行下标
 */
function removeItem(index: number): void {
  if (createForm.items.length <= 1) {
    ElMessage.warning('至少要保留一行出库明细')
    return
  }
  createForm.items.splice(index, 1)
}

/**
 * 提交建单。
 */
async function submitCreate(): Promise<void> {
  // 1. 前端只做"必填 + 明显格式"的提示，业务规则（禁入品类/库存不足/余额不足）一律以后端为准
  if (!createForm.warehouseId || !createForm.merchantId) {
    ElMessage.warning('仓库与商家都是必填（雪花 ID 字符串）')
    return
  }
  if (!createForm.carrierCode.trim()) {
    ElMessage.warning('承运商编码不能为空')
    return
  }
  if (!createForm.estimatedFreight) {
    ElMessage.warning('预估运费不能为空：建单即按此金额冻结，不足会被拦下')
    return
  }
  if (!createForm.sourceOrderNo.trim() && createForm.sourceType === OutOrderSourceType.API) {
    ElMessage.warning('来源类型为 API 时必须填上游订单号（用于防重复下单）')
    return
  }
  const emptyLine = createForm.items.find((item) => !item.skuCode.trim() || !item.qty)
  if (emptyLine) {
    ElMessage.warning('每行明细都要填商品条码与应发数量')
    return
  }
  // 2. 提交（幂等键由 request.ts 按"动作 + 载荷"自动生成并复用）
  const created = await withMask(() => OutboundApi.create(createForm), MaskText.SUBMIT)
  if (!created) {
    return
  }
  showSuccess(`已建单 ${created.doNo}`)
  createVisible.value = false
  await router.push({ name: 'OutOrderDetail', params: { doNo: created.doNo } })
}

/**
 * 进入详情。
 *
 * @param row 列表行
 */
function openDetail(row: OutOrderVo): void {
  router.push({ name: 'OutOrderDetail', params: { doNo: row.doNo } })
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <!-- 查询区 -->
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="出库单号">
          <el-input v-model="query.doNo" placeholder="精确匹配" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="item in OUT_ORDER_STATUS_OPTIONS" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input v-model="query.warehouseId" placeholder="雪花 ID" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="商家 ID">
          <el-input v-model="query.merchantId" placeholder="雪花 ID" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button v-if="auth.can('outbound:order:create')" type="success" @click="openCreate">新建出库单</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="doNo" label="出库单号" width="220" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="90">
          <template #default="{ row }">{{ sameDayLabel(row.isSameDay) }}</template>
        </el-table-column>
        <el-table-column prop="carrierCode" label="承运商" width="100" />
        <el-table-column prop="warehouseId" label="仓库 ID" width="200" />
        <el-table-column prop="merchantId" label="商家 ID" width="200" />
        <el-table-column label="件数" width="80">
          <template #default="{ row }">{{ row.packageCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="建单时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" width="180">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情/作业</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="page__pager"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <!-- 建单弹窗 -->
    <el-dialog v-model="createVisible" title="新建出库单" width="900px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="建单即预占库存并冻结运费：库存不足返回 50001、余额/冻结失败返回 20001，整单回滚"
      />
      <el-form :model="createForm" label-width="110px" class="page__form">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="仓库 ID">
              <el-input v-model="createForm.warehouseId" placeholder="雪花 ID" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="商家 ID">
              <el-input v-model="createForm.merchantId" placeholder="雪花 ID" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="来源类型">
              <el-select v-model="createForm.sourceType" style="width: 100%">
                <el-option v-for="item in OUT_ORDER_SOURCE_OPTIONS" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="上游订单号">
              <el-input v-model="createForm.sourceOrderNo" placeholder="API 来源必填，防重复下单" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="承运商编码">
              <el-input v-model="createForm.carrierCode" placeholder="如 SF" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="承运商名称">
              <el-input v-model="createForm.carrierName" placeholder="如 顺丰" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="预估运费">
              <el-input v-model="createForm.estimatedFreight" placeholder="建单即冻结" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="应收重量">
              <el-input v-model="createForm.expectedWeight" placeholder="kg，可空" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="应收体积">
              <el-input v-model="createForm.expectedVolume" placeholder="m³，可空" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="收件人">
              <el-input v-model="createForm.receiverName" placeholder="姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="收件电话">
              <el-input v-model="createForm.receiverPhone" placeholder="手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="省/市/区">
              <div class="page__region">
                <el-input v-model="createForm.receiverProvince" placeholder="省" />
                <el-input v-model="createForm.receiverCity" placeholder="市" />
                <el-input v-model="createForm.receiverDistrict" placeholder="区" />
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="收件地址">
              <el-input v-model="createForm.receiverAddress" placeholder="详细地址（入库加密，接口只回掩码）" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="createForm.remark" placeholder="可空" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">出库明细</el-divider>
        <el-table :data="createForm.items" border>
          <el-table-column label="#" width="60">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="商品条码">
            <template #default="{ row }">
              <el-input v-model="row.skuCode" placeholder="如 SKU-V-001" />
            </template>
          </el-table-column>
          <el-table-column label="应发数量" width="140">
            <template #default="{ row }">
              <el-input v-model="row.qty" placeholder="数量" />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="120">
            <template #default="{ row }">
              <el-input v-model="row.unit" placeholder="可空" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button class="page__add" @click="addItem">+ 增加一行</el-button>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">提交建单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__table {
  margin-top: 12px;
}

.page__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.page__form {
  margin-top: 12px;
}

.page__region {
  display: flex;
  gap: 6px;
}

.page__add {
  margin-top: 8px;
}
</style>
