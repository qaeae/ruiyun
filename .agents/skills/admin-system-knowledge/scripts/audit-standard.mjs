#!/usr/bin/env node
/**
 * 规范 ↔ 项目 一致性审计脚本（知识库资产）
 *
 * 用法（在项目根目录执行）：
 *   pnpm audit:standard
 *   node .agents/skills/admin-system-knowledge/scripts/audit-standard.mjs
 *
 * 审计维度：
 *   1. R4  每个 API 方法是否都传 apiCode
 *   2.      reference.md 的 API 清单 ↔ src/api/modules 实际方法
 *   3.      models 导出符号 ↔ reference.md 登记情况
 *   4.      规范提及的关键符号（常量/取用函数/store 方法）是否存在于代码
 *   5.      规范命令表 ↔ package.json scripts
 *   6.      图标清单 list.json ↔ public/icons/svg 源文件
 *   7. R10 页面注册（componentMap / 静态路由）
 *   8. R5/R12 列表页落地（loading 初始态、v-loading、骨架类名、分区注释）
 *   9.      规范引用的项目路径是否存在（排除占位示例与禁止路径）
 *
 * 退出码：0 = 无差异；1 = 存在需调整项
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = process.cwd()
const KB = join(ROOT, '.agents/skills/admin-system-knowledge')
const SRC = join(ROOT, 'src')
const report = { ok: [], issues: [], hints: [] }

const read = (p) => readFileSync(p, 'utf8')
const listFiles = (dir, filter, out = []) => {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) listFiles(full, filter, out)
    else if (filter(name)) out.push(full)
  }
  return out
}

const docsText = ['SKILL.md', 'PROJECT-STANDARD.md', 'models-standard.md', 'page-standard.md', 'reference.md']
  .map((f) => read(join(KB, f)))
  .join('\n')

// ===== 1. apiCode 完整性（按方法体统计） =====
const apiDir = join(SRC, 'api/modules')
const apiActual = {}
const noApiCode = []
for (const f of readdirSync(apiDir).filter((n) => n.endsWith('.ts'))) {
  const content = read(join(apiDir, f))
  const mod = f.replace('.ts', '')
  const fnRe = /^\s{2}(?:async\s+)?(\w+)\s*[(<][\s\S]*?\n\s{2}\},?$/gm
  const methods = []
  for (const m of content.matchAll(fnRe)) {
    const body = m[0]
    methods.push(m[1])
    const reqCount = (body.match(/\brequest\s*[<(]/g) || []).length
    const apiCodeCount = (body.match(/apiCode\s*:/g) || []).length
    if (reqCount > 0 && apiCodeCount < reqCount) noApiCode.push(`${mod}.${m[1]}（request ${reqCount} 次，apiCode ${apiCodeCount} 次）`)
  }
  apiActual[mod] = [...new Set(methods)]
}
if (noApiCode.length) report.issues.push(`缺少 apiCode 的请求（R4）：\n    ${noApiCode.join('\n    ')}`)
else report.ok.push(`所有 API 方法均传 apiCode（R4，共 ${Object.values(apiActual).flat().length} 个方法）`)

// ===== 2. API 清单比对（reference.md ↔ 代码） =====
const refText = read(join(KB, 'reference.md'))
const apiTable = [...refText.matchAll(/^\|\s*[^|]+\|\s*`src\/api\/modules\/(\w+)\.ts`\s*\|\s*([^|]+)\|/gm)]
const apiDiff = []
for (const [, mod, methods] of apiTable) {
  const declared = [...methods.matchAll(/`(\w+)`/g)].map((m) => m[1])
  const actual = apiActual[mod] || []
  for (const d of declared) if (!actual.includes(d)) apiDiff.push(`${mod}.${d}（清单有、代码无）`)
  for (const a of actual) if (!declared.includes(a)) apiDiff.push(`${mod}.${a}（代码有、清单无）`)
}
const docMods = apiTable.map((m) => m[1])
for (const m of Object.keys(apiActual)) if (!docMods.includes(m)) apiDiff.push(`api/modules/${m}.ts（代码有、清单无）`)
if (apiDiff.length) report.issues.push(`API 清单与代码不一致：\n    ${apiDiff.join('\n    ')}`)
else report.ok.push('API 清单与代码完全一致')

// ===== 3. models 导出符号 vs reference.md 登记 =====
const modelFiles = listFiles(join(SRC, 'models'), (n) => n.endsWith('.ts') && !n.includes('__tests__'))
const allExports = []
const unlisted = []
for (const f of modelFiles) {
  const c = read(f)
  const names = [...c.matchAll(/^export\s+(?:interface|class|const|type|function)\s+(\w+)/gm)].map((m) => m[1])
  allExports.push(...names)
  const rel = relative(ROOT, f).replace(/\\/g, '/')
  for (const n of names) if (!refText.includes(n)) unlisted.push(`${rel} → ${n}`)
}
if (unlisted.length) report.issues.push(`models 导出未登记到 reference.md：\n    ${unlisted.join('\n    ')}`)
else report.ok.push(`models 的 ${allExports.length} 个导出符号均已登记 reference.md`)

// ===== 4. 规范提及的关键符号是否存在于代码 =====
const keySymbols = [
  'withMask', 'hasPermission', 'showConfirm', 'showSuccess', 'showError',
  'getEnableFlagView', 'getEnableFlagToggle', 'getVisitFlagView', 'getAuthCheckView',
  'getFormTitle', 'getFormFooterText', 'getSubmitResultText',
  'getResourceTypeStyle', 'getResourceTreeIndent', 'getApiStyleText',
  'ENABLE_FLAG_OPTIONS', 'YES_NO_OPTIONS', 'VISIT_FLAG_OPTIONS',
  'DICT_CODE', 'RESOURCE_TYPE_DICT_CODE', 'FLAG', 'FORM_MODE', 'EMPTY_TEXT',
  'DEFAULT_PLATFORM_CODE', 'FlagValue', 'SexValue', 'SortOrder', 'TagType', 'ButtonType',
  'ResourceType', 'ApiStyle', 'FileListType', 'UserQueryType', 'ResourceTypeStyle',
  'loadDictItems', 'getDictItemsSync', 'getDictItemNameSync', 'getDictItemOptions',
  'saveQuery', 'takeQuery', 'clearQuery', 'preRegisterRoutes'
]
const srcText = listFiles(SRC, (n) => n.endsWith('.ts') || n.endsWith('.vue')).map((f) => read(f)).join('\n')
const staleSymbols = keySymbols.filter((s) => docsText.includes(s) && !srcText.includes(s))
if (staleSymbols.length) report.issues.push(`规范提及但代码中不存在的符号：${staleSymbols.join('、')}`)
else report.ok.push(`规范提及的 ${keySymbols.length} 个关键符号均存在于代码`)

// ===== 5. 规范命令表 vs package.json =====
const pkg = JSON.parse(read(join(ROOT, 'package.json')))
const pkgScripts = Object.keys(pkg.scripts || {})
const cmdInDoc = [...docsText.matchAll(/`pnpm ([\w:]+)`/g)].map((m) => m[1])
const cmdMissing = [...new Set(cmdInDoc)].filter((c) => !pkgScripts.includes(c) && c !== 'exec')
if (cmdMissing.length) report.issues.push(`规范命令在 package.json 中不存在：${cmdMissing.join('、')}`)
else report.ok.push('规范命令表中的命令均已在 package.json 定义')

// ===== 6. 图标清单 vs svg 源文件 =====
const svgDir = join(ROOT, 'public/icons/svg')
const listJson = join(ROOT, 'public/icons/list.json')
if (existsSync(svgDir) && existsSync(listJson)) {
  const svgs = readdirSync(svgDir).filter((n) => n.endsWith('.svg')).map((n) => n.replace('.svg', ''))
  const listed = [...read(listJson).matchAll(/"iconCode":\s*"([^"]+)"/g)].map((m) => m[1])
  const iconDiff = []
  for (const s of svgs) if (!listed.includes(s)) iconDiff.push(`${s}.svg 未在 list.json（需执行 pnpm build:icons）`)
  for (const l of listed) if (!svgs.includes(l)) iconDiff.push(`list.json 中的 ${l} 无对应 svg`)
  if (iconDiff.length) report.hints.push(`图标清单差异：\n    ${iconDiff.join('\n    ')}`)
  else report.ok.push(`图标清单一致（${svgs.length} 个 svg）`)
}

// ===== 7. 页面注册（R10） =====
const routerText = read(join(SRC, 'router/index.ts'))
const cmBlock = routerText.slice(routerText.indexOf('const componentMap'), routerText.indexOf('const staticRoutes'))
const registered = [...cmBlock.matchAll(/(\w+):\s*\(\)\s*=>/g)].map((m) => m[1])
const staticPages = [...routerText.matchAll(/component:\s*\(\)\s*=>\s*import\('@\/views\/([\w/]+)\.vue'\)/g)].map((m) => m[1].split('/').pop())
const pageNames = readdirSync(join(SRC, 'views/system')).filter((n) => n.endsWith('.vue')).map((n) => n.replace('.vue', ''))
const unregistered = pageNames.filter((n) => !registered.includes(n) && !staticPages.includes(n))
if (unregistered.length) report.issues.push(`页面未注册（R10）：${unregistered.join('、')}`)
else report.ok.push(`页面注册齐全（componentMap ${registered.length} + 静态路由 ${staticPages.length}）`)

// ===== 8. 列表页规范落地（R5/R12） =====
const layoutIssues = []
for (const f of listFiles(join(SRC, 'views'), (n) => n.endsWith('.vue'))) {
  const rel = relative(ROOT, f).replace(/\\/g, '/')
  const c = read(f)
  if (!/async function fetch(List|Tree)\(/.test(c)) continue
  const miss = []
  if (!/v-loading="loading"/.test(c)) miss.push('缺 v-loading="loading"')
  if (!/const loading = ref\(true\)/.test(c)) miss.push('loading 未初始化为 true')
  if (!/class="page-container"/.test(c)) miss.push('缺 .page-container')
  if (!/class="table-card"/.test(c)) miss.push('缺 .table-card')
  for (const s of ['页面状态', '业务方法', '生命周期']) if (!c.includes(`// ==================== ${s}`)) miss.push(`缺分区：${s}`)
  if (miss.length) layoutIssues.push(`${rel} → ${miss.join('；')}`)
}
if (layoutIssues.length) report.issues.push(`页面规范落地缺项（R5/R12）：\n    ${layoutIssues.join('\n    ')}`)
else report.ok.push('所有列表页符合 R5/R12（加载态 + 骨架类名 + 分区注释）')

// ===== 9. 规范引用的项目路径 =====
const pathRefs = [...new Set([...docsText.matchAll(/`((?:src|public|scripts)\/[\w./-]+)`/g)].map((m) => m[1]))]
const placeholders = pathRefs.filter((p) => /Xxx|\{|\}/.test(p))
const forbidden = pathRefs.filter((p) => p === 'src/types/')
const missingPaths = pathRefs.filter((p) => !placeholders.includes(p) && !forbidden.includes(p) && !existsSync(join(ROOT, p)))
if (missingPaths.length) report.issues.push(`规范引用的路径不存在：\n    ${missingPaths.join('\n    ')}`)
else report.ok.push(`规范引用的 ${pathRefs.length - placeholders.length - forbidden.length} 个路径均存在（排除 ${placeholders.length} 个占位示例、${forbidden.length} 个禁止路径）`)

// ===== 输出 =====
const out = ['# 规范 ↔ 项目 一致性审计报告', '']
out.push(`✅ 通过：${report.ok.length}　❗需调整：${report.issues.length}　💡提示：${report.hints.length}`, '')
if (report.ok.length) { out.push('## 通过'); report.ok.forEach((x) => out.push(`- ${x}`)); out.push('') }
if (report.issues.length) { out.push('## 需调整'); report.issues.forEach((x) => out.push(`- ${x}`)); out.push('') }
if (report.hints.length) { out.push('## 提示'); report.hints.forEach((x) => out.push(`- ${x}`)); out.push('') }
console.log(out.join('\n'))
process.exit(report.issues.length ? 1 : 0)
