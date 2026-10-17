<script setup lang="ts">
/**
 * 出库单详情 / 作业页（API-OUT-02…12 全部作业动作 + API-OUT-13 详情与轨迹）。
 *
 * 这一页是仓内作业的"操作台"，设计上守三条：
 *
 * 1. **按钮由状态机决定**：可见动作来自 `allowedActions(status)`，权限再过滤一层（`auth.can`）。
 *    两者都没有就不渲染——不让用户点一个注定 409 或 403 的按钮。
 * 2. **不可逆动作要二次确认**：装车（库存出账）、取消（回滚库存与冻结）、拦截（作废面单）都要确认，
 *    且装车/取消/拦截的确认框里写清"会发生什么"，避免误触。
 * 3. **失败原因原样展示**：后端对 409 返回的是可直接给用户看的中文（例如"不可取消；如需退回请走退货入库流程"），
 *    `showError` 直接弹原文，前端不再编一套文案（两套文案迟早不一致）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  MeasureQo,
  OUT_ORDER_ACTION_LABEL,
  OUT_ORDER_ACTION_PERM,
  OutboundApi,
  OutOrderLineQo,
  PackQo,
  ReasonQo,
  RemarkQo,
  allowedActions,
  handoverLabel,
  sameDayLabel,
  statusTagType,
  type OutOrderAction,
  type OutOrderDetailVo,
  type OutOrderLineInput,
  type OutStatusLogVo,
} from '@/models/outbound'
import { formatQty, formatTime } from '@/models/common'
import { useAuthStore } from '@/stores/auth'
import { MaskText, showSuccess, withMask } from '@/utils/mask'
import { showError } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 出库单号（路由参数）。 */
const doNo = computed(() => String(route.params.doNo ?? ''))

/** 详情。 */
const detail = ref<OutOrderDetailVo | null>(null)

/** 状态轨迹。 */
const logs = ref<OutStatusLogVo[]>([])

/** 加载态（R5）。 */
const loading = ref(true)

/** 作业弹窗是否打开（el-dialog 的 v-model 需要 boolean）。 */
const actionVisible = ref(false)

/** 当前作业动作（空串 = 未选择）。 */
const actionMode = ref<OutOrderAction | ''>('')

/** 作业弹窗里的行输入（派发/拣货/缺货/拆单共用）。 */
const lineInputs = ref<OutOrderLineInput[]>([])

/** 说明/原因（交接/装车/取消/拦截/缺货/拆单共用）。 */
const remark = ref('')

/** 称重表单。 */
const measureForm = reactive(new MeasureQo())

/** 打包表单。 */
const packForm = reactive(new PackQo())

/** 允许的动作（状态机 + 权限双重过滤）。 */
const visibleActions = computed<OutOrderAction[]>(() => {
  const status = detail.value?.status ?? ''
  return allowedActions(status).filter((action) => auth.can(OUT_ORDER_ACTION_PERM[action]))
})

/**
 * 加载详情与轨迹。
 */
async function loadAll(): Promise<void> {
  loading.value = true
  try {
    // 1. 详情（缺失说明单号不对或已删除，直接提示）
    detail.value = await OutboundApi.detail(doNo.value)
    // 2. 轨迹（次要信息，失败不阻塞主信息）
    logs.value = await OutboundApi.statusLogs(doNo.value).catch(() => [])
  } catch (error) {
    detail.value = null
    logs.value = []
    showError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 打开作业弹窗，并按当前明细预填行输入。
 *
 * <p>预填规则体现业务口径：拣货默认"实拣 = 应发"（不等就要走缺货上报）、拆单默认"发货 = 应发"，
 * 这样正常路径只需点确认，异常路径才动手改数字。
 *
 * @param mode 动作
 */
function openAction(mode: OutOrderAction): void {
  const items = detail.value?.items ?? []
  remark.value = ''
  Object.assign(measureForm, new MeasureQo())
  Object.assign(packForm, new PackQo())
  measureForm.actualWeight = detail.value?.expectedWeight != null ? String(detail.value.expectedWeight) : ''
  measureForm.actualVolume = detail.value?.expectedVolume != null ? String(detail.value.expectedVolume) : ''
  packForm.packageCount = detail.value?.packageCount != null ? String(detail.value.packageCount) : ''

  if (mode === 'dispatch') {
    lineInputs.value = items.map((item) => ({ lineNo: item.lineNo, value: '' }))
  } else if (mode === 'pick' || mode === 'partial') {
    lineInputs.value = items.map((item) => ({ lineNo: item.lineNo, value: String(item.qty) }))
  } else if (mode === 'shortage') {
    lineInputs.value = items.map((item) => ({ lineNo: item.lineNo, value: '' }))
  } else {
    lineInputs.value = []
  }
  actionMode.value = mode
  actionVisible.value = true
}

/**
 * 提交当前作业弹窗里的动作。
 */
async function submitAction(): Promise<void> {
  const mode = actionMode.value
  if (!mode || !detail.value) {
    return
  }
  // 1. 行输入校验（派发要求每行都填库位；数值类要求 > 0）
  if (mode === 'dispatch' && lineInputs.value.some((line) => !line.value.trim())) {
    ElMessage.warning('每行都要填拣货库位编码（出库扣减必须落到具体库位）')
    return
  }
  if ((mode === 'pick' || mode === 'partial') && lineInputs.value.some((line) => !line.value)) {
    ElMessage.warning('请填写每行的数量')
    return
  }
  // 2. 说明类必填校验（取消/拦截必须写原因，便于事后追责）
  if ((mode === 'cancel' || mode === 'hold') && !remark.value.trim()) {
    ElMessage.warning(mode === 'cancel' ? '请填写取消原因' : '请填写拦截原因')
    return
  }
  // 3. 不可逆动作二次确认
  if (mode === 'load') {
    const ok = await ElMessageBox.confirm(
      '装车确认会**扣减库存并出账**，之后不能取消（只能走退货入库）。确认装车？',
      '装车确认（不可逆）',
      { type: 'warning', confirmButtonText: '确认装车', cancelButtonText: '取消' },
    ).catch(() => false)
    if (!ok) {
      return
    }
  }
  if (mode === 'cancel') {
    const ok = await ElMessageBox.confirm(
      '取消会按回滚矩阵释放预占/库位分配、作废面单并释放冻结。已出库的单不能取消。确认取消？',
      '取消出库单',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    ).catch(() => false)
    if (!ok) {
      return
    }
  }
  if (mode === 'hold') {
    const ok = await ElMessageBox.confirm(
      '拦截会把单据转为「已拦截」终态，并回滚预占、释放冻结、作废面单。确认拦截？',
      '异常拦截',
      { type: 'warning', confirmButtonText: '确认拦截', cancelButtonText: '返回' },
    ).catch(() => false)
    if (!ok) {
      return
    }
  }

  // 4. 按动作调用（全部经网关 + 幂等键由 request.ts 生成）
  const target = doNo.value
  const done = await withMask(async () => {
    switch (mode) {
      case 'dispatch':
        return OutboundApi.dispatch(target, lineInputs.value.map((line) => new OutOrderLineQo(line.lineNo, line.value)))
      case 'pick':
        return OutboundApi.pickComplete(target, lineInputs.value.map((line) => new OutOrderLineQo(line.lineNo, line.value)))
      case 'shortage':
        return OutboundApi.shortage(
          target,
          lineInputs.value.filter((line) => line.value).map((line) => new OutOrderLineQo(line.lineNo, line.value)),
          remark.value.trim(),
        )
      case 'measure':
        return OutboundApi.measure(target, measureForm)
      case 'pack':
        return OutboundApi.pack(target, packForm)
      case 'handover':
        return OutboundApi.handover(target, new RemarkQo(remark.value))
      case 'load':
        return OutboundApi.load(target, new RemarkQo(remark.value))
      case 'cancel':
        return OutboundApi.cancel(target, new ReasonQo(remark.value))
      case 'hold':
        return OutboundApi.hold(target, new ReasonQo(remark.value))
      case 'partial':
        return OutboundApi.partialShip(
          target,
          lineInputs.value.map((line) => new OutOrderLineQo(line.lineNo, line.value)),
          remark.value.trim(),
        )
      default:
        return undefined
    }
  }, MaskText.SUBMIT)

  if (!done) {
    return
  }
  // 5. 拆单要单独提示"余量去了哪张新单"，否则用户会以为货丢了
  if (mode === 'partial' && 'newDoNo' in done) {
    showSuccess(`已部分发货，余量生成新单 ${done.newDoNo}`)
  } else {
    showSuccess(`${OUT_ORDER_ACTION_LABEL[mode]}已完成`)
  }
  actionMode.value = ''
  actionVisible.value = false
  await loadAll()
}

/**
 * 恢复拣货（无入参，直接确认执行）。
 */
async function handleResume(): Promise<void> {
  const ok = await ElMessageBox.confirm('补货到位后恢复拣货会重新预占库存，可能因库存不足失败。确认？', '恢复拣货', {
    type: 'warning',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  }).catch(() => false)
  if (!ok) {
    return
  }
  const done = await withMask(() => OutboundApi.resumePicking(doNo.value), MaskText.SUBMIT)
  if (!done) {
    return
  }
  showSuccess('已恢复拣货')
  await loadAll()
}

/**
 * 派发/拣货等弹窗的标题。
 *
 * @returns 标题
 */
function dialogTitle(): string {
  return actionMode.value ? OUT_ORDER_ACTION_LABEL[actionMode.value] : ''
}

/**
 * 是否需要"行输入"区域。
 *
 * @returns 需要返回 true
 */
function needLines(): boolean {
  return actionMode.value === 'dispatch' || actionMode.value === 'pick'
    || actionMode.value === 'shortage' || actionMode.value === 'partial'
}

/**
 * 行输入列标题。
 *
 * @returns 标题
 */
function lineColumnLabel(): string {
  switch (actionMode.value) {
    case 'dispatch':
      return '拣货库位编码'
    case 'pick':
      return '实拣数量'
    case 'shortage':
      return '缺货数量'
    default:
      return '本次发货数量'
  }
}

/** 是否需要填写说明/原因。 */
const needRemark = computed(() =>
  ['handover', 'load', 'cancel', 'hold', 'shortage', 'partial'].includes(actionMode.value as string),
)

/** 说明字段的标题（取消/拦截必须写原因）。 */
const remarkLabel = computed(() =>
  actionMode.value === 'cancel' ? '取消原因' : actionMode.value === 'hold' ? '拦截原因' : '说明',
)

/** 说明是否必填（只有取消/拦截强制）。 */
const remarkRequired = computed(() => actionMode.value === 'cancel' || actionMode.value === 'hold')

/** 说明输入框的占位文案（R7：不在模板里写三元）。 */
const remarkPlaceholder = computed(() =>
  remarkRequired.value ? '必填：便于事后追责与解释' : '可空',
)

/** 返回列表。 */
function goBack(): void {
  router.push({ name: 'OutOrderList' })
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading" class="page">
    <el-card shadow="never">
      <template #header>
        <div class="page__head">
          <span class="page__no">{{ detail?.doNo ?? doNo }}</span>
          <el-tag v-if="detail" :type="statusTagType(detail.status)" size="small">{{ detail.status }}</el-tag>
          <el-tag v-if="detail" :type="detail.isSameDay ? 'success' : 'info'" size="small">
            {{ sameDayLabel(detail.isSameDay) }}
          </el-tag>
          <el-button link type="primary" @click="goBack">返回列表</el-button>
        </div>
      </template>

      <!-- 作业按钮：状态机 × 权限 -->
      <div class="page__actions">
        <el-button
          v-for="action in visibleActions.filter((item) => item !== 'resume')"
          :key="action"
          :type="action === 'load' ? 'success' : action === 'cancel' || action === 'hold' ? 'danger' : 'primary'"
          size="small"
          @click="openAction(action)"
        >
          {{ OUT_ORDER_ACTION_LABEL[action] }}
        </el-button>
        <el-button
          v-if="visibleActions.includes('resume')"
          type="primary"
          size="small"
          @click="handleResume"
        >
          恢复拣货
        </el-button>
        <span v-if="visibleActions.length === 0" class="page__hint">
          当前状态没有可执行的作业动作（终态或权限不足）
        </span>
      </div>

      <!-- 概要 -->
      <el-descriptions v-if="detail" :column="3" border class="page__block">
        <el-descriptions-item label="来源">{{ detail.sourceType }}</el-descriptions-item>
        <el-descriptions-item label="上游单号">{{ detail.sourceOrderNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="承运商">{{ detail.carrierCode }}</el-descriptions-item>
        <el-descriptions-item label="仓库 ID">{{ detail.warehouseId }}</el-descriptions-item>
        <el-descriptions-item label="商家 ID">{{ detail.merchantId }}</el-descriptions-item>
        <el-descriptions-item label="件数">{{ detail.packageCount ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="应收重量">{{ formatQty(detail.expectedWeight) }} kg</el-descriptions-item>
        <el-descriptions-item label="实称重量">
          {{ formatQty(detail.actualWeight) }} kg
          <el-tag v-if="detail.weightChecked" type="success" size="small">已称重</el-tag>
          <el-tag v-else type="warning" size="small">未称重</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="实量体积">{{ formatQty(detail.actualVolume) }} m³</el-descriptions-item>
        <el-descriptions-item label="面单打印">{{ detail.labelPrintCount }} 次</el-descriptions-item>
        <el-descriptions-item label="扫码交接">
          {{ handoverLabel(detail.handoverConfirmed) }}
        </el-descriptions-item>
        <el-descriptions-item label="取消时点">{{ detail.cancelStage || '-' }}</el-descriptions-item>
        <el-descriptions-item label="建单时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ formatTime(detail.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="3">{{ detail.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 收件人（只有掩码） -->
    <el-card v-if="detail" shadow="never" class="page__block">
      <template #header>
        <span>收件人（接口只返回掩码，明文不出后端）</span>
      </template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="姓名">{{ detail.receiver.nameMasked }}</el-descriptions-item>
        <el-descriptions-item label="电话">{{ detail.receiver.phoneMasked }}</el-descriptions-item>
        <el-descriptions-item label="地址">{{ detail.receiver.addressMasked }}</el-descriptions-item>
        <el-descriptions-item label="省/市/区">
          {{ detail.receiver.province || '-' }} / {{ detail.receiver.city || '-' }} /
          {{ detail.receiver.district || '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 明细 -->
    <el-card v-if="detail" shadow="never" class="page__block">
      <template #header>
        <span>出库明细（行号是后续作业动作的定位键）</span>
      </template>
      <el-table :data="detail.items" border stripe>
        <el-table-column prop="lineNo" label="行号" width="70" />
        <el-table-column prop="skuCode" label="商品条码" width="160" />
        <el-table-column prop="skuName" label="名称" show-overflow-tooltip />
        <el-table-column label="应发" width="100">
          <template #default="{ row }">{{ formatQty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="实拣" width="100">
          <template #default="{ row }">{{ formatQty(row.pickedQty) }}</template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="拣货库位" width="220">
          <template #default="{ row }">{{ row.locationId || '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 状态轨迹 -->
    <el-card v-if="detail" shadow="never" class="page__block">
      <template #header>
        <span>状态轨迹（取消/拦截的说明写在这里，可追溯）</span>
      </template>
      <el-timeline>
        <el-timeline-item
          v-for="(log, index) in logs"
          :key="`${log.event}-${index}`"
          :timestamp="`${formatTime(log.createdAt)} · ${log.event}`"
          placement="top"
        >
          <div class="page__log">
            <span>{{ log.fromStatus || '—' }} → {{ log.toStatus }}</span>
            <el-tag v-if="log.remark" size="small" type="info">{{ log.remark }}</el-tag>
            <span v-if="log.deviceCode" class="page__hint">设备 {{ log.deviceCode }}</span>
            <span v-if="log.traceId" class="page__hint">traceId {{ log.traceId }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <!-- 作业弹窗（一个弹窗按动作切换表单，避免十个弹窗） -->
    <el-dialog v-model="actionVisible" :title="dialogTitle()" width="640px">
      <el-alert
        v-if="actionMode === 'load'"
        type="error"
        :closable="false"
        show-icon
        title="装车是库存出账点：提交后单据转「已出库」，不能取消"
      />
      <el-alert
        v-else-if="actionMode === 'pick'"
        type="info"
        :closable="false"
        show-icon
        title="实拣必须等于应发；不等请改用「缺货上报」（否则库存与订单会永久对不上）"
      />
      <el-alert
        v-else-if="actionMode === 'measure'"
        type="info"
        :closable="false"
        show-icon
        title="实称与应收偏差超过 20% 会被后端拦停（20003），请复称或先走异常拦截"
      />
      <el-alert
        v-else-if="actionMode === 'partial'"
        type="warning"
        :closable="false"
        show-icon
        title="原单按本次发货量出账并转「已出库」，余量生成新的「待接单」单"
      />

      <!-- 行输入 -->
      <el-table v-if="needLines()" :data="lineInputs" border class="page__lines">
        <el-table-column prop="lineNo" label="行号" width="80" />
        <el-table-column :label="lineColumnLabel()">
          <template #default="{ row }">
            <el-input v-model="row.value" placeholder="必填" />
          </template>
        </el-table-column>
        <el-table-column label="应发" width="100">
          <template #default="{ row }">
            {{ formatQty(detail?.items.find((item) => item.lineNo === row.lineNo)?.qty) }}
          </template>
        </el-table-column>
      </el-table>

      <!-- 称重 -->
      <el-form v-if="actionMode === 'measure'" :model="measureForm" label-width="100px">
        <el-form-item label="实称重量">
          <el-input v-model="measureForm.actualWeight" placeholder="kg" />
        </el-form-item>
        <el-form-item label="实量体积">
          <el-input v-model="measureForm.actualVolume" placeholder="m³，可空" />
        </el-form-item>
        <el-form-item label="件数">
          <el-input v-model="measureForm.packageCount" placeholder="可空" />
        </el-form-item>
      </el-form>

      <!-- 打包 -->
      <el-form v-if="actionMode === 'pack'" :model="packForm" label-width="100px">
        <el-form-item label="运单号">
          <el-input v-model="packForm.waybillNo" placeholder="扫面单得到的运单号" />
        </el-form-item>
        <el-form-item label="件数">
          <el-input v-model="packForm.packageCount" placeholder="可空" />
        </el-form-item>
      </el-form>

      <!-- 说明 / 原因 -->
      <el-form v-if="needRemark" label-width="100px" class="page__remark">
        <el-form-item :label="remarkLabel">
          <el-input
            v-model="remark"
            type="textarea"
            :rows="3"
            :placeholder="remarkPlaceholder"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAction">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page__no {
  font-weight: 600;
  font-size: 15px;
}

.page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.page__block {
  margin-top: 12px;
}

.page__hint {
  color: #909399;
  font-size: 12px;
}

.page__log {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.page__lines {
  margin-top: 12px;
}

.page__remark {
  margin-top: 12px;
}
</style>
