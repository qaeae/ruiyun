/**
 * 菜单模型（R2：类型与常量统一放 `src/models/`，`.vue` 内禁止 `interface`）。
 *
 * 菜单按**端**分组展示权限过滤后的入口：无权限的入口不显示，而不是点进去报 403。
 */

/** 菜单项。 */
export interface MenuItem {
  /** 路由路径。 */
  path: string
  /** 标题。 */
  title: string
  /** 进入页面所需权限码（无此权限则不显示入口）。 */
  perm: string
}

/** 菜单分组。 */
export interface MenuGroup {
  /** 分组标题。 */
  title: string
  /** 分组内菜单。 */
  items: MenuItem[]
}

/**
 * 平台菜单（按权限过滤后展示）。
 *
 * 权限码与后端 `PermissionCodes` 一一对应，见 `docs/frontend/api-contract.md`。
 * 分组顺序即"日常使用顺序"：库存作业 → 复核 → 财务 → 主数据 → 运维。
 */
export const AccountMenuGroups: MenuGroup[] = [
  {
    title: '库存作业',
    items: [
      { path: '/inventory/stock', title: '库存查询', perm: 'stock:query' },
      { path: '/inventory/adjust', title: '库存调整（需复核）', perm: 'stock:adjust' },
    ],
  },
  {
    title: '出库作业',
    items: [
      { path: '/outbound/orders', title: '出库单', perm: 'outbound:order:query' },
    ],
  },
  {
    title: '复核',
    items: [
      { path: '/approvals/pending', title: '复核待办', perm: 'auth:approval:review' },
      { path: '/approvals/mine', title: '我的申请', perm: 'auth:approval:apply' },
    ],
  },
  {
    title: '财务',
    items: [
      { path: '/finance/reconcile-bills', title: '对账单', perm: 'finance:query' },
      { path: '/finance/settle-batches', title: '结算批次', perm: 'finance:query' },
      { path: '/finance/advance-payments', title: '垫付台账', perm: 'finance:query' },
      { path: '/finance/daily-reconciliation', title: '日终勾稽', perm: 'finance:query' },
      { path: '/finance/fallback-deductions', title: '兜底扣减', perm: 'finance:query' },
    ],
  },
  {
    title: '主数据',
    items: [
      { path: '/master/data', title: '主数据', perm: 'master:query' },
      { path: '/master/devices', title: '设备档案', perm: 'master:query' },
    ],
  },
  {
    title: '运维',
    items: [{ path: '/ops/jobs', title: '任务运维', perm: 'ops:job:query' }],
  },
]
