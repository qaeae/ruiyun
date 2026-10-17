<script setup lang="ts">
/**
 * 主数据页（商家 / 仓库 / 库区 / 库位 / 商品+箱规）。
 *
 * 界面口径：
 * - **层级串联**：库区属于仓库、库位属于库区（三级校验在服务端，前端按层级选择，避免选错组合）；
 * - `md_location`（库位主数据：编码/是否可拣）与 `iv_location`（库位库存）不是一回事，
 *   本页只管主数据，库存看「库存查询」页；
 * - **商品属于商家**，跨商家选商品是常见误操作，因此商品列表必须按商家过滤。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  MasterApi,
  DeviceTypeLabel,
  MerchantStatus,
  MerchantStatusTag,
  isMerchantEnabled,
  merchantToggleLabel,
  forbiddenLabel,
  pickableLabel,
  toggleLabel,
  yesNoLabel,
  LocationCreateQo,
  MerchantCreateQo,
  SkuCreateQo,
  UnitConvCreateQo,
  WarehouseCreateQo,
  ZoneCreateQo,
  ZoneType,
  type LocationVo,
  type MerchantVo,
  type QtyConvertVo,
  type SkuVo,
  type UnitConvVo,
  type WarehouseVo,
  type ZoneVo,
} from '@/models/master'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 当前页签。 */
const activeTab = ref('merchant')

/** 加载态。 */
const loading = ref(true)

/** 商家。 */
const merchants = ref<MerchantVo[]>([])

/** 仓库。 */
const warehouses = ref<WarehouseVo[]>([])

/** 库区（按选中仓库）。 */
const zones = ref<ZoneVo[]>([])

/** 库位（按选中仓库）。 */
const locations = ref<LocationVo[]>([])

/** 商品（按选中商家）。 */
const skus = ref<SkuVo[]>([])

/** 箱规（按选中商品）。 */
const unitConvs = ref<UnitConvVo[]>([])

/** 换算结果。 */
const convertResult = ref<QtyConvertVo | null>(null)

/** 当前选中的商家 / 仓库 / 商品。 */
const selected = reactive<{ merchantId?: string; warehouseId?: string; skuId?: string }>({})

/** 各新建弹窗。 */
const merchantVisible = ref(false)
const warehouseVisible = ref(false)
const zoneVisible = ref(false)
const locationVisible = ref(false)
const skuVisible = ref(false)
const unitConvVisible = ref(false)

/** 各表单。 */
const merchantForm = reactive(new MerchantCreateQo())
const warehouseForm = reactive(new WarehouseCreateQo())
const zoneForm = reactive(new ZoneCreateQo())
const locationForm = reactive(new LocationCreateQo())
const skuForm = reactive(new SkuCreateQo())
const unitConvForm = reactive(new UnitConvCreateQo())

/** 换算工具表单。 */
const convertForm = reactive({ qty: '1', fromUnit: '件', toUnit: '箱' })

/** 库区类型选项（R7）。 */
const zoneTypes = [ZoneType.STORAGE, ZoneType.PICK, ZoneType.TEMP, ZoneType.RETURN]

/** 设备类型选项及其中文名（用于页签内的设备提示，展示用）。 */
const deviceTypeLabels = DeviceTypeLabel

/**
 * 初次加载：商家 + 仓库。
 */
async function loadBase(): Promise<void> {
  loading.value = true
  try {
    merchants.value = await MasterApi.listMerchants()
    warehouses.value = await MasterApi.listWarehouses()
    // 1. 只有一个仓库时直接选中，减少无效操作
    if (warehouses.value.length === 1) {
      selected.warehouseId = warehouses.value[0].id
      await loadZonesAndLocations()
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 加载库区与库位（按仓库）。
 */
async function loadZonesAndLocations(): Promise<void> {
  if (!selected.warehouseId) {
    zones.value = []
    locations.value = []
    return
  }
  try {
    zones.value = await MasterApi.listZones(selected.warehouseId)
    locations.value = await MasterApi.listLocations(selected.warehouseId)
  } catch (error) {
    showError(error)
  }
}

/**
 * 加载商品与箱规（按商家）。
 */
async function loadSkus(): Promise<void> {
  if (!selected.merchantId) {
    skus.value = []
    unitConvs.value = []
    return
  }
  try {
    skus.value = await MasterApi.listSkus(selected.merchantId)
    selected.skuId = undefined
    unitConvs.value = []
    convertResult.value = null
  } catch (error) {
    showError(error)
  }
}

/**
 * 加载箱规（按商品）。
 */
async function loadUnitConvs(): Promise<void> {
  if (!selected.skuId) {
    unitConvs.value = []
    return
  }
  try {
    unitConvs.value = await MasterApi.listUnitConversions(selected.skuId)
    convertResult.value = null
  } catch (error) {
    showError(error)
  }
}

/** 新建商家。 */
async function submitMerchant(): Promise<void> {
  const result = await withMask(() => MasterApi.createMerchant(merchantForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已新建商家 #${result.id}`)
  merchantVisible.value = false
  merchants.value = await MasterApi.listMerchants()
}

/** 新建仓库。 */
async function submitWarehouse(): Promise<void> {
  const result = await withMask(() => MasterApi.createWarehouse(warehouseForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已新建仓库 #${result.id}`)
  warehouseVisible.value = false
  warehouses.value = await MasterApi.listWarehouses()
}

/** 新建库区。 */
async function submitZone(): Promise<void> {
  zoneForm.warehouseId = selected.warehouseId
  const result = await withMask(() => MasterApi.createZone(zoneForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已新建库区 #${result.id}`)
  zoneVisible.value = false
  loadZonesAndLocations()
}

/** 新建库位。 */
async function submitLocation(): Promise<void> {
  locationForm.warehouseId = selected.warehouseId
  const result = await withMask(() => MasterApi.createLocation(locationForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已新建库位 #${result.id}`)
  locationVisible.value = false
  loadZonesAndLocations()
}

/** 新建商品。 */
async function submitSku(): Promise<void> {
  skuForm.merchantId = selected.merchantId
  const result = await withMask(() => MasterApi.createSku(skuForm), MaskText.SUBMIT)
  if (!result) {
    return
  }
  ElMessage.success(`已新建商品 #${result.id}`)
  skuVisible.value = false
  loadSkus()
}

/** 新增箱规。 */
async function submitUnitConv(): Promise<void> {
  if (!selected.skuId) {
    ElMessage.warning('请先选择商品')
    return
  }
  const result = await withMask(
    () => MasterApi.addUnitConversion(selected.skuId!, unitConvForm),
    MaskText.SUBMIT,
  )
  if (!result) {
    return
  }
  ElMessage.success('箱规已新增')
  unitConvVisible.value = false
  loadUnitConvs()
}

/** 数量换算。 */
async function handleConvert(): Promise<void> {
  if (!selected.skuId) {
    ElMessage.warning('请先选择商品')
    return
  }
  try {
    convertResult.value = await MasterApi.convertQty(
      selected.skuId,
      convertForm.qty,
      convertForm.fromUnit,
      convertForm.toUnit,
    )
  } catch (error) {
    // 无换算路径时后端抛错（**不会返回原值**）——必须提示，否则会按错误数量作业
    showError(error)
  }
}

/**
 * 商家状态标签类型（R7：模板不写判断逻辑）。
 *
 * @param status 状态
 * @returns 标签类型
 */
function merchantStatusTag(status: string): 'success' | 'info' | 'danger' {
  if (status === MerchantStatus.NORMAL) {
    return MerchantStatusTag.NORMAL
  }
  return status === MerchantStatus.BLACKLIST ? MerchantStatusTag.BLACKLIST : MerchantStatusTag.DISABLED
}

/** 启停商家。 */
async function toggleMerchant(row: MerchantVo): Promise<void> {
  const done = await withMask(
    () => MasterApi.changeMerchantStatus(row.id, !isMerchantEnabled(row.status)),
    MaskText.TOGGLE,
  )
  if (done === undefined) {
    return
  }
  ElMessage.success('已变更')
  merchants.value = await MasterApi.listMerchants()
}

/** 启停仓库。 */
async function toggleWarehouse(row: WarehouseVo): Promise<void> {
  const done = await withMask(
    () => MasterApi.changeWarehouseStatus(row.id, row.status !== '启用'),
    MaskText.TOGGLE,
  )
  if (done === undefined) {
    return
  }
  ElMessage.success('已变更')
  warehouses.value = await MasterApi.listWarehouses()
}

/** 启停库位。 */
async function toggleLocation(row: LocationVo): Promise<void> {
  const done = await withMask(
    () => MasterApi.changeLocationStatus(row.id, row.status !== '启用'),
    MaskText.TOGGLE,
  )
  if (done === undefined) {
    return
  }
  ElMessage.success('已变更')
  loadZonesAndLocations()
}

/** 启停商品。 */
async function toggleSku(row: SkuVo): Promise<void> {
  const done = await withMask(() => MasterApi.changeSkuStatus(row.id, row.status !== '启用'), MaskText.TOGGLE)
  if (done === undefined) {
    return
  }
  ElMessage.success('已变更')
  loadSkus()
}

onMounted(loadBase)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-tabs v-model="activeTab" type="border-card">
      <!-- 商家 -->
      <el-tab-pane label="商家" name="merchant">
        <div class="page__actions">
          <el-button type="primary" @click="merchantVisible = true">新建商家</el-button>
        </div>
        <el-table :data="merchants" border stripe>
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="merchantNo" label="商家编号" width="150" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="contactName" label="联系人" width="120" />
          <el-table-column prop="contactPhoneMasked" label="手机号（脱敏）" width="160" />
          <el-table-column prop="creditScore" label="信用分" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="merchantStatusTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleMerchant(row)">
                {{ merchantToggleLabel(row.status) }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 仓库 / 库区 / 库位 -->
      <el-tab-pane label="仓库与库位" name="warehouse">
        <el-form inline>
          <el-form-item label="仓库">
            <el-select
              v-model="selected.warehouseId"
              placeholder="选择仓库"
              style="width: 200px"
              @change="loadZonesAndLocations"
            >
              <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="warehouseVisible = true">新建仓库</el-button>
            <el-button :disabled="!selected.warehouseId" @click="zoneVisible = true">新建库区</el-button>
            <el-button :disabled="!selected.warehouseId" @click="locationVisible = true">新建库位</el-button>
          </el-form-item>
        </el-form>

        <el-divider content-position="left">仓库</el-divider>
        <el-table :data="warehouses" border stripe>
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="warehouseCode" label="编码" width="130" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="province" label="省" width="100" />
          <el-table-column prop="city" label="市" width="100" />
          <el-table-column prop="cutoffTime" label="截单时间" width="120" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleWarehouse(row)">
                {{ toggleLabel(row.status) }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">库区（属于选中仓库）</el-divider>
        <el-table :data="zones" border stripe>
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="zoneCode" label="编码" width="130" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="zoneType" label="类型" width="110" />
          <el-table-column label="隔离区" width="90">
            <template #default="{ row }">{{ yesNoLabel(row.isolation) }}</template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">库位（主数据：编码 / 是否可拣）</el-divider>
        <el-table :data="locations" border stripe>
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="locationCode" label="编码" width="150" />
          <el-table-column prop="zoneId" label="库区" width="100" />
          <el-table-column prop="abcClass" label="ABC" width="80" />
          <el-table-column label="可拣货" width="90">
            <template #default="{ row }">
              <el-tag :type="row.pickable ? 'success' : 'info'">{{ pickableLabel(row.pickable) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleLocation(row)">
                {{ toggleLabel(row.status) }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 商品与箱规 -->
      <el-tab-pane label="商品与箱规" name="sku">
        <el-form inline>
          <el-form-item label="商家">
            <el-select
              v-model="selected.merchantId"
              placeholder="选择商家"
              style="width: 200px"
              @change="loadSkus"
            >
              <el-option v-for="item in merchants" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="商品">
            <el-select
              v-model="selected.skuId"
              placeholder="选择商品"
              style="width: 220px"
              @change="loadUnitConvs"
            >
              <el-option v-for="item in skus" :key="item.id" :label="`${item.skuCode} ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :disabled="!selected.merchantId" @click="skuVisible = true">新建商品</el-button>
            <el-button :disabled="!selected.skuId" @click="unitConvVisible = true">新增箱规</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="skus" border stripe>
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="skuCode" label="条码" width="150" />
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="category" label="品类" width="110" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="weightKg" label="重量(kg)" width="100" />
          <el-table-column prop="volumeRatio" label="体积比" width="100" />
          <el-table-column label="禁入" width="90">
            <template #default="{ row }">
              <el-tag :type="row.forbidden ? 'danger' : 'success'">{{ forbiddenLabel(row.forbidden) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleSku(row)">
                {{ toggleLabel(row.status) }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">箱规换算（1 unit_from = factor unit_to）</el-divider>
        <el-table :data="unitConvs" border stripe>
          <el-table-column prop="unitFrom" label="源单位" width="120" />
          <el-table-column prop="unitTo" label="目标单位" width="120" />
          <el-table-column prop="factor" label="系数" width="120" />
        </el-table>

        <el-form inline class="page__convert">
          <el-form-item label="换算">
            <el-input v-model="convertForm.qty" style="width: 100px" />
            <el-input v-model="convertForm.fromUnit" style="width: 90px" class="page__unit" />
            <span class="page__arrow">→</span>
            <el-input v-model="convertForm.toUnit" style="width: 90px" />
          </el-form-item>
          <el-form-item>
            <el-button :disabled="!selected.skuId" @click="handleConvert">计算</el-button>
            <el-tag v-if="convertResult" type="success">
              {{ convertResult.sourceQty }} {{ convertResult.fromUnit }} =
              {{ convertResult.resultQty }} {{ convertResult.toUnit }}
            </el-tag>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 设备档案提示（详细管理在「设备档案」页） -->
      <el-tab-pane label="设备类型速查" name="device">
        <el-descriptions :column="1" border>
          <el-descriptions-item v-for="(label, code) in deviceTypeLabels" :key="code" :label="code">
            {{ label }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          class="page__convert"
          type="info"
          :closable="false"
          show-icon
          title="设备登记与启停请在「设备档案」页操作；PDA 请求会带 X-Device-Code 做绑定校验"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 新建商家 -->
    <el-dialog v-model="merchantVisible" title="新建商家" width="480px">
      <el-form :model="merchantForm" label-width="96px">
        <el-form-item label="商家编号">
          <el-input v-model="merchantForm.merchantNo" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="merchantForm.name" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="merchantForm.contactName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="merchantForm.contactPhone" placeholder="服务端加密存储，列表只回脱敏值" />
        </el-form-item>
        <el-form-item label="信用分">
          <el-input-number v-model="merchantForm.creditScore" :min="0" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="merchantVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMerchant">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新建仓库 -->
    <el-dialog v-model="warehouseVisible" title="新建仓库" width="480px">
      <el-form :model="warehouseForm" label-width="96px">
        <el-form-item label="仓库编码">
          <el-input v-model="warehouseForm.warehouseCode" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="warehouseForm.name" />
        </el-form-item>
        <el-form-item label="省">
          <el-input v-model="warehouseForm.province" />
        </el-form-item>
        <el-form-item label="市">
          <el-input v-model="warehouseForm.city" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="warehouseForm.address" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="warehouseVisible = false">取消</el-button>
        <el-button type="primary" @click="submitWarehouse">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新建库区 -->
    <el-dialog v-model="zoneVisible" title="新建库区" width="480px">
      <el-form :model="zoneForm" label-width="96px">
        <el-form-item label="库区编码">
          <el-input v-model="zoneForm.zoneCode" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="zoneForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="zoneForm.zoneType" style="width: 100%">
            <el-option v-for="item in zoneTypes" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="zoneVisible = false">取消</el-button>
        <el-button type="primary" @click="submitZone">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新建库位 -->
    <el-dialog v-model="locationVisible" title="新建库位" width="480px">
      <el-form :model="locationForm" label-width="96px">
        <el-form-item label="库区">
          <el-select v-model="locationForm.zoneId" placeholder="选择库区" style="width: 100%">
            <el-option v-for="item in zones" :key="item.id" :label="item.zoneCode" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库位编码">
          <el-input v-model="locationForm.locationCode" />
        </el-form-item>
        <el-form-item label="ABC 分类">
          <el-input v-model="locationForm.abcClass" placeholder="A/B/C（可空）" />
        </el-form-item>
        <el-form-item label="可拣货">
          <el-switch v-model="locationForm.isPickable" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locationVisible = false">取消</el-button>
        <el-button type="primary" @click="submitLocation">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新建商品 -->
    <el-dialog v-model="skuVisible" title="新建商品" width="480px">
      <el-form :model="skuForm" label-width="96px">
        <el-form-item label="商品条码">
          <el-input v-model="skuForm.skuCode" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="skuForm.name" />
        </el-form-item>
        <el-form-item label="品类">
          <el-input v-model="skuForm.category" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="skuForm.unit" placeholder="件/箱/托" />
        </el-form-item>
        <el-form-item label="重量(kg)">
          <el-input v-model="skuForm.weightKg" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skuVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSku">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新增箱规 -->
    <el-dialog v-model="unitConvVisible" title="新增箱规" width="460px">
      <el-form :model="unitConvForm" label-width="96px">
        <el-form-item label="源单位">
          <el-input v-model="unitConvForm.unitFrom" placeholder="件" />
        </el-form-item>
        <el-form-item label="目标单位">
          <el-input v-model="unitConvForm.unitTo" placeholder="箱" />
        </el-form-item>
        <el-form-item label="系数">
          <el-input v-model="unitConvForm.factor" placeholder="如 12 表示 1 件 = 12 箱？注意方向按源→目标" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="语义：1 unit_from = factor unit_to；仅支持「件/箱/托」三种单位"
      />
      <template #footer>
        <el-button @click="unitConvVisible = false">取消</el-button>
        <el-button type="primary" @click="submitUnitConv">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__actions {
  margin-bottom: 12px;
}

.page__convert {
  margin-top: 12px;
}

.page__unit {
  margin-left: 6px;
}

.page__arrow {
  margin: 0 6px;
}
</style>
