<script setup lang="ts">
/**
 * 设备档案页（登记 / 列表 / 启停）。
 *
 * 界面口径：
 * - 设备是"现场资产"：`device_code` 是 PDA 请求头 `X-Device-Code` 的取值，
 *   **停用后携带该编号的请求会被后端 403 + 10006 拒绝**（所以停用前要确认设备确实不在用了）；
 * - `last_online_at` 只是登记用途（**不做心跳**），因此界面不要把"很久没在线"当成故障判断依据。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DeviceRegisterQo,
  DeviceType,
  DeviceTypeLabel,
  MasterApi,
  toggleLabel,
  type DeviceVo,
} from '@/models/master'
import { withMask, MaskText } from '@/utils/mask'
import { showError } from '@/utils/request'

/** 查询条件。 */
const query = reactive<{ deviceType?: string; warehouseId?: string }>({})

/** 列表。 */
const rows = ref<DeviceVo[]>([])

/** 加载态。 */
const loading = ref(true)

/** 登记弹窗。 */
const registerVisible = ref(false)

/** 登记表单。 */
const registerForm = reactive(new DeviceRegisterQo())

/** 设备类型选项。 */
const deviceTypes = Object.values(DeviceType)

/**
 * 加载列表。
 */
async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await MasterApi.listDevices(query.deviceType, query.warehouseId)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 登记设备。
 */
async function submitRegister(): Promise<void> {
  if (!registerForm.deviceCode.trim()) {
    ElMessage.warning('设备编号不能为空')
    return
  }
  const done = await withMask(() => MasterApi.registerDevice(registerForm), MaskText.SUBMIT)
  if (done === undefined) {
    return
  }
  ElMessage.success('设备已登记')
  registerVisible.value = false
  loadList()
}

/**
 * 启停设备。
 *
 * @param row 设备
 */
async function handleToggle(row: DeviceVo): Promise<void> {
  const disable = row.status === '启用'
  if (disable) {
    const confirmed = await ElMessageBox.confirm(
      `停用后，携带 ${row.deviceCode} 的 PDA 请求会被拒绝（403 / 10006）。确认停用？`,
      '停用设备',
      { type: 'warning' },
    ).catch(() => false)
    if (!confirmed) {
      return
    }
  }
  const done = await withMask(() => MasterApi.changeDeviceStatus(row.deviceCode, !disable), MaskText.TOGGLE)
  if (done === undefined) {
    return
  }
  ElMessage.success(disable ? '已停用' : '已启用')
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="类型">
          <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 150px">
            <el-option
              v-for="item in deviceTypes"
              :key="item"
              :label="DeviceTypeLabel[item]"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input v-model="query.warehouseId" placeholder="仓库 ID（可空）" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="registerVisible = true">登记设备</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="device_code 即 PDA 请求头 X-Device-Code 的取值；「最近在线」仅登记用途，不做心跳，不能作为故障判断依据"
      />
    </el-card>

    <el-card shadow="never" class="page__table">
      <el-table :data="rows" border stripe>
        <el-table-column prop="deviceCode" label="设备编号" width="150" />
        <el-table-column prop="deviceType" label="类型" width="100" />
        <el-table-column prop="deviceTypeLabel" label="类型名称" width="110" />
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="warehouseId" label="仓库" width="90" />
        <el-table-column prop="locationDesc" label="摆放位置" width="160" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '启用' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastOnlineAt" label="最近在线（仅登记）" width="180" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.status === '启用' ? 'danger' : 'success'" @click="handleToggle(row)">
              {{ toggleLabel(row.status) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="registerVisible" title="登记设备" width="480px">
      <el-form :model="registerForm" label-width="96px">
        <el-form-item label="设备编号">
          <el-input v-model="registerForm.deviceCode" placeholder="仅大写字母、数字与短横线，如 PDA-A-007" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="registerForm.deviceType" style="width: 100%">
            <el-option
              v-for="item in deviceTypes"
              :key="item"
              :label="DeviceTypeLabel[item]"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="registerForm.name" />
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input v-model="registerForm.warehouseId" placeholder="仓库 ID（可空）" />
        </el-form-item>
        <el-form-item label="摆放位置">
          <el-input v-model="registerForm.locationDesc" placeholder="如 一号库 A 区入口" />
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
