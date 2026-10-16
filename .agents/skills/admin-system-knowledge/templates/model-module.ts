/**
 * {模块}模块模型（{module}）
 * Qo=查询表单；Vo=页面回显；Bo=数据提交；Dto=前端内部通用传输
 * 数据拷贝/映射统一使用 mapia
 *
 * 用法：复制到 src/models/pages/{module}.ts，替换 {模块}/{module}/xxx 占位符，
 *       并在 src/models/index.ts 中新增 export * from './pages/{module}.js'
 */
import { compileMapper, transform } from 'mapia'
import type { FormMode } from '../common.js'
import { FLAG } from '../common.js'

/** {模块}列表页查询条件快照（Qo，跳转子页面前保存，返回时恢复） */
export interface SysXxxPageQueryQo {
  xxxCode: string
  currentPage: number
  pageSize: number
}

/** {模块}（Vo，页面数据显示） */
export interface SysXxxVo {
  id?: string
  xxxCode: string
  xxxName: string
  /** 字典值字段：只声明数据类型，不写死字面量联合 */
  enableFlag: string
}

/** {模块}列表项（Vo，页面数据显示） */
export interface SysXxxListVo extends SysXxxVo {
  createTime?: string
}

/** {模块}查询（Qo，表单查询 + v-model 绑定） */
export class SysXxxQueryQo {
  xxxCode: string
  xxxName: string
  pageNum?: number
  pageSize?: number

  /**
   * 构造{模块}查询表单
   * @param init 初始值
   */
  constructor(init: Partial<SysXxxQueryQo> = {}) {
    // 1. 初始化查询字段默认值
    this.xxxCode = ''
    this.xxxName = ''
    // 2. 合并传入的初始值
    Object.assign(this, init)
  }

  /** 重置搜索条件 */
  reset(): void {
    // 1. 以全新实例覆盖当前字段
    Object.assign(this, new SysXxxQueryQo())
  }

  /**
   * 转换为{模块}查询参数（仅包含非空字段）
   * @param pageNum 页码
   * @param pageSize 每页大小
   * @returns 查询参数
   */
  toParams(pageNum?: number, pageSize?: number): SysXxxQueryQo {
    // 1. 创建空参数对象，按需写入分页
    const params = {} as SysXxxQueryQo
    if (pageNum !== undefined) params.pageNum = pageNum
    if (pageSize !== undefined) params.pageSize = pageSize
    // 2. 仅写入非空查询条件
    if (this.xxxCode) params.xxxCode = this.xxxCode
    if (this.xxxName) params.xxxName = this.xxxName
    return params
  }
}

/** {模块}编辑表单字段（模块私有，不导出） */
interface SysXxxEditFormData {
  xxxCode: string
  xxxName: string
  enableFlag: string
}

/** 列表行 → 编辑表单映射（模块私有，不导出） */
const xxxEditFormFromRowMapper = compileMapper<SysXxxListVo, SysXxxEditFormData>({
  xxxCode: 'xxxCode',
  xxxName: 'xxxName',
  // 字典标记：回调参数写 string，默认值引用常量
  enableFlag: transform((enableFlag?: string) => enableFlag || FLAG.YES)
})

/** {模块}编辑提交模型（Bo，页面双向绑定 + 提交） */
export class SysXxxEditBo {
  id?: string
  xxxCode: string
  xxxName: string
  enableFlag: string

  /** 构造{模块}编辑表单（新增默认值） */
  constructor() {
    // 1. 初始化字段默认值
    this.xxxCode = ''
    this.xxxName = ''
    this.enableFlag = FLAG.YES
  }

  /** 重置为新增默认值 */
  reset(): void {
    // 1. 以全新实例覆盖当前字段
    Object.assign(this, new SysXxxEditBo())
  }

  /**
   * 从列表行回填表单
   * @param row {模块}列表行
   */
  fill(row: SysXxxListVo): void {
    // 1. 由 mapia 完成字段映射并合并到表单
    Object.assign(this, xxxEditFormFromRowMapper.mapOne(row))
  }

  /**
   * 转换为{模块}编辑提交参数（仅包含非空字段）
   * @param id {模块}ID（编辑时传入）
   * @returns 提交参数
   */
  toParams(id?: string): SysXxxEditBo {
    // 1. 创建空参数对象并写入主键
    const params = {} as SysXxxEditBo
    params.id = id || undefined
    // 2. 写入必填字段
    params.xxxCode = this.xxxCode
    params.xxxName = this.xxxName
    params.enableFlag = this.enableFlag
    return params
  }
}

/** {模块}表单标题（按模式） */
export const XXX_FORM_TITLE: Record<FormMode, string> = {
  add: '新增{模块}',
  edit: '编辑{模块}',
  detail: '{模块}详情'
}
