#!/usr/bin/env node
/**
 * 规范自检脚本（PROJECT-STANDARD R1–R12 的可执行子集）
 *
 * 用法（在项目根目录执行）：
 *   node .agents/skills/admin-system-knowledge/scripts/check-standard.mjs
 *   pnpm check:standard   （package.json 中可配置）
 *
 * 退出码：0 = 通过；1 = 存在违规项
 * 检查范围：src/ 下的 .vue 与 .ts（排除 src/auto-imports.d.ts、src/components.d.ts）
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, extname } from 'node:path'

/** 项目根目录（约定在项目根执行） */
const ROOT = process.cwd()
const SRC = join(ROOT, 'src')
const IGNORE_FILES = ['auto-imports.d.ts', 'components.d.ts']

/** 递归收集目标文件 */
function walk(dir, out = []) {
  let entries = []
  try {
    entries = readdirSync(dir)
  } catch {
    return out
  }
  for (const name of entries) {
    const full = join(dir, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      if (name === 'node_modules' || name === 'dist') continue
      walk(full, out)
    } else {
      const ext = extname(name)
      if ((ext === '.vue' || ext === '.ts') && !IGNORE_FILES.includes(name) && !name.endsWith('.d.ts')) {
        out.push(full)
      }
    }
  }
  return out
}

/** 提取 .vue 的模板区间行内容 */
function templateLines(content) {
  const lines = content.split('\n')
  let start = -1
  let end = -1
  lines.forEach((line, i) => {
    if (start === -1 && line.includes('<template>')) start = i
    if (start !== -1 && line.includes('</template>') && i > start) end = i
  })
  if (start === -1) return []
  return lines.slice(start, end === -1 ? lines.length : end + 1).map((text, i) => ({ text, line: start + i + 1 }))
}

/** 业务字面量（模板禁写） */
const LITERAL_FLAGS = ['Y', 'N', 'M', 'F', 'C', 'P', 'DIR', 'PAGE', 'BUTTON']
/** 写操作 API 方法名 */
const WRITE_METHODS = ['edit', 'deleteBatch', 'enableOpt', 'authRole', 'authResource', 'refreshPermission', 'resetPwd', 'flushApis', 'updateInfo', 'updatePwd']

const violations = []
const hints = []

for (const file of walk(SRC)) {
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const content = readFileSync(file, 'utf8')
  const lines = content.split('\n')
  const isVue = file.endsWith('.vue')
  const isModel = rel.startsWith('src/models/')

  lines.forEach((line, i) => {
    const no = i + 1
    const trimmed = line.trim()

    // R2：.vue 中禁止定义 interface / class
    if (isVue && /^(export\s+)?(interface|class)\s+[A-Za-z_$]/.test(trimmed)) {
      violations.push(`R2  .vue 中禁止定义 interface/class → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // R1：禁止内联对象字面量直接传 API
    if (/\w+Api\.\w+\(\s*\{/.test(line)) {
      violations.push(`R1  内联对象字面量不得直接传 API（应由模型 toParams/toFormData 产出）→ ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // R6：写操作必须用 withMask 包裹
    const writeRe = new RegExp(`await\\s+\\w+Api\\.(${WRITE_METHODS.join('|')})\\(`)
    if (writeRe.test(line) && !line.includes('withMask')) {
      violations.push(`R6  写操作需用 withMask(task, text?) 包裹 → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // R8：models 中禁止内联字面量类型（须命名后引用）
    if (isModel && !/^(export\s+)?type\s/.test(trimmed)) {
      const inlineUnion = /[:<(,]\s*'[^']+'\s*\|\s*'[^']+'/.test(line)
      if (inlineUnion) {
        violations.push(`R8  models 中内联字面量类型须命名后引用（如 FlagValue/TagType）→ ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
      }
    }

    // R5：列表页 loading 初始态（提示级）
    if (!isVue && false) return
  })

  // R7：模板业务字面量检查（仅 .vue 模板区间）
  if (isVue) {
    for (const { text, line } of templateLines(content)) {
      const flagValueRe = new RegExp(`(value|:value)="(${LITERAL_FLAGS.join('|')})"`)
      if (flagValueRe.test(text)) {
        violations.push(`R7  模板下拉/属性不得写业务字面量（改用 models 常量或字典选项）→ ${rel}:${line}\n    ${text.trim().slice(0, 100)}`)
      }
      const compareRe = new RegExp(`={2,3}\\s*'(${LITERAL_FLAGS.join('|')})'`)
      if (compareRe.test(text)) {
        violations.push(`R7  模板比较不得写业务字面量（改用常量或取用函数）→ ${rel}:${line}\n    ${text.trim().slice(0, 100)}`)
      }
      const ternaryCnRe = /\?\s*'[^']*[\u4e00-\u9fa5][^']*'\s*:/
      if (ternaryCnRe.test(text)) {
        violations.push(`R7  模板三元不得内联中文文案（改用 models 映射/computed）→ ${rel}:${line}\n    ${text.trim().slice(0, 100)}`)
      }
    }
  }

  // R5：页面初始加载态（提示级）
  if (rel.startsWith('src/views/') && isVue) {
    const hasFetch = /async function fetch(List|Tree)\(/.test(content)
    if (hasFetch) {
      if (!/v-loading="loading"/.test(content)) {
        hints.push(`R5  列表页根容器建议挂 v-loading="loading" → ${rel}`)
      }
      if (!/const loading = ref\(true\)/.test(content)) {
        hints.push(`R5  列表页 loading 建议初始为 true（onMounted 资源就绪后 finally 关闭）→ ${rel}`)
      }
    }
  }
}

/** 输出报告 */
const report = []
report.push('# 规范自检报告（PROJECT-STANDARD R1–R12 子集）')
report.push('')
report.push(`扫描目录：src/　违规：${violations.length}　提示：${hints.length}`)
report.push('')
if (violations.length) {
  report.push('## 违规（需修复）')
  report.push('')
  violations.forEach((v) => report.push(`- ${v}`))
  report.push('')
}
if (hints.length) {
  report.push('## 提示（建议确认）')
  report.push('')
  hints.forEach((h) => report.push(`- ${h}`))
  report.push('')
}
if (!violations.length && !hints.length) {
  report.push('✅ 未发现违规项')
  report.push('')
}
console.log(report.join('\n'))
process.exit(violations.length ? 1 : 0)
