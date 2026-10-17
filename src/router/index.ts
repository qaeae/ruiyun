/**
 * 路由与守卫（规范 R10：路由权限、按钮 `hasPermission`）。
 *
 * 守卫做两件事：
 * 1. **未登录 → 跳登录**（并把原目标放到 `redirect`，登录后回跳）；
 * 2. **无权限 → 跳 403 页**（不是跳登录：登录了也解决不了权限问题）。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 路由表（`meta.perm` 为进入页面所需权限码；空表示登录即可访问）。 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/inventory/stock',
    children: [
      // 库存作业
      {
        path: 'inventory/stock',
        name: 'StockList',
        component: () => import('@/views/inventory/StockListView.vue'),
        meta: { title: '库存查询', perm: 'stock:query' },
      },
      {
        path: 'inventory/adjust',
        name: 'StockAdjust',
        component: () => import('@/views/inventory/StockAdjustView.vue'),
        meta: { title: '库存调整（需复核）', perm: 'stock:adjust' },
      },
      // 出库作业
      {
        path: 'outbound/orders',
        name: 'OutOrderList',
        component: () => import('@/views/outbound/OutOrderListView.vue'),
        meta: { title: '出库单', perm: 'outbound:order:query' },
      },
      {
        path: 'outbound/orders/:doNo',
        name: 'OutOrderDetail',
        component: () => import('@/views/outbound/OutOrderDetailView.vue'),
        meta: { title: '出库单作业', perm: 'outbound:order:query' },
      },
      // 复核
      {
        path: 'approvals/pending',
        name: 'ApprovalPending',
        component: () => import('@/views/approval/ApprovalPendingView.vue'),
        meta: { title: '复核待办', perm: 'auth:approval:review' },
      },
      {
        path: 'approvals/mine',
        name: 'MyApprovals',
        component: () => import('@/views/approval/MyApprovalsView.vue'),
        meta: { title: '我的申请', perm: 'auth:approval:apply' },
      },
      // 财务
      {
        path: 'finance/reconcile-bills',
        name: 'ReconcileBills',
        component: () => import('@/views/finance/ReconcileBillListView.vue'),
        meta: { title: '对账单', perm: 'finance:query' },
      },
      {
        path: 'finance/settle-batches',
        name: 'SettleBatches',
        component: () => import('@/views/finance/SettleBatchListView.vue'),
        meta: { title: '结算批次', perm: 'finance:query' },
      },
      {
        path: 'finance/advance-payments',
        name: 'AdvancePayments',
        component: () => import('@/views/finance/AdvancePaymentView.vue'),
        meta: { title: '垫付台账', perm: 'finance:query' },
      },
      {
        path: 'finance/daily-reconciliation',
        name: 'DailyReconciliation',
        component: () => import('@/views/finance/DailyReconciliationView.vue'),
        meta: { title: '日终勾稽', perm: 'finance:query' },
      },
      {
        path: 'finance/fallback-deductions',
        name: 'FallbackDeductions',
        component: () => import('@/views/finance/FallbackDeductionView.vue'),
        meta: { title: '兜底扣减', perm: 'finance:query' },
      },
      // 主数据
      {
        path: 'master/data',
        name: 'MasterData',
        component: () => import('@/views/master/MasterDataView.vue'),
        meta: { title: '主数据', perm: 'master:query' },
      },
      {
        path: 'master/devices',
        name: 'DeviceList',
        component: () => import('@/views/master/DeviceListView.vue'),
        meta: { title: '设备档案', perm: 'master:query' },
      },
      // 运维
      {
        path: 'ops/jobs',
        name: 'JobMonitor',
        component: () => import('@/views/ops/JobMonitorView.vue'),
        meta: { title: '任务运维', perm: 'ops:job:query' },
      },
      {
        path: 'forbidden',
        name: 'Forbidden',
        component: () => import('@/views/error/ForbiddenView.vue'),
        meta: { title: '无权限' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

/** 路由实例。 */
export const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  // 1. 公开页面直接放行
  if (to.meta.public === true) {
    // 1.1 已登录访问登录页 → 回首页（避免"登录后还停在登录页"的困惑）
    if (to.path === '/login' && auth.isLogin) {
      return { path: '/' }
    }
    return true
  }
  // 2. 未登录 → 登录页（记住原目标）
  if (!auth.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 3. 权限不足 → 403 页（**不跳登录**）
  const perm = to.meta.perm as string | undefined
  if (perm && !auth.can(perm)) {
    return { path: '/forbidden' }
  }
  return true
})

export default router
