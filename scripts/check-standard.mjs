#!/usr/bin/env node
/**
 * 规范自检脚本（**项目化改写版**）。
 *
 * 来源：`.agents/skills/admin-system-knowledge/scripts/check-standard.mjs`（外部项目的自检脚本）。
 * 本项目按 `docs/frontend/W5-FRONTEND-KICKOFF.md` §五 做了改写，**差异如下**：
 *
 * | 项 | 外部项目 | 本项目 |
 * |----|---------|--------|
 * | 成功码 | `code === 200` | **`code === 0`**（禁出现 200 判定） |
 * | 请求标识 | 每次请求传 `apiCode` | **禁 `apiCode`**；幂等键由 `utils/request.ts` 统一生成 |
 * | 幂等键 | —— | 业务代码**禁手写 `Idempotency-Key`**（否则各处实现不一致，重试复用会漏） |
 * | 写方法名 | `edit/deleteBatch/...` | 本项目的方法名（`submitAdjust/approve/reject/...`） |
 *
 * 用法：
 *   pnpm check:standard
 *
 * 退出码：0 = 通过；1 = 存在违规项。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, extname } from 'node:path'

/** 项目根目录（约定在项目根执行）。 */
const ROOT = process.cwd()
const SRC = join(ROOT, 'src')

/** 递归收集目标文件。 */
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
      if ((ext === '.vue' || ext === '.ts') && !name.endsWith('.d.ts')) {
        out.push(full)
      }
    }
  }
  return out
}

/** 提取 .vue 的模板区间行内容。 */
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

/** 本项目写操作的方法名（用于 R6 遮罩检查）。 */
const WRITE_METHODS = [
  'submitAdjust',
  'approve',
  'reject',
  'withdraw',
  'create',
  'update',
  'remove',
  'dispatch',
  'run',
  'setEnabled',
]

const violations = []
const hints = []

for (const file of walk(SRC)) {
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const content = readFileSync(file, 'utf8')
  const lines = content.split('\n')
  const isVue = file.endsWith('.vue')
  const isModel = rel.startsWith('src/models/')
  const isSpec = rel.endsWith('.spec.ts') || rel.endsWith('.test.ts')

  lines.forEach((line, i) => {
    const no = i + 1
    const trimmed = line.trim()

    // R2：.vue 中禁止定义 interface / class（类型定义必须放 models）
    if (isVue && /^(export\s+)?(interface|class)\s+[A-Za-z_$]/.test(trimmed)) {
      violations.push(`R2  .vue 中禁止定义 interface/class → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // R1：禁止内联对象字面量直接传 API（应由模型 toParams/toFormData 产出）
    if (!isSpec && /\w+Api\.\w+\(\s*\{/.test(line)) {
      violations.push(
        `R1  内联对象字面量不得直接传 API（应由模型 toParams/toFormData 产出）→ ${rel}:${no}\n    ${trimmed.slice(0, 100)}`,
      )
    }

    // R6：写操作必须用 withMask 包裹
    // **测试文件除外**：R6 防的是"用户连点导致重复提交"，测试里没有界面可点；
    // 且 withMask 会调 Element Plus 的 ElLoading（需要 DOM），在 Node 环境里反而跑不起来。
    const writeRe = new RegExp(`await\\s+\\w+Api\\.(${WRITE_METHODS.join('|')})\\(`)
    if (!isSpec && writeRe.test(line) && !line.includes('withMask')) {
      violations.push(`R6  写操作需用 withMask(task, text?) 包裹 → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // R8：models 中禁止内联字面量类型（须命名后引用）
    if (isModel && !/^(export\s+)?type\s/.test(trimmed)) {
      if (/[:<(,]\s*'[^']+'\s*\|\s*'[^']+'/.test(line)) {
        violations.push(
          `R8  models 中内联字面量类型须命名后引用（如 FlagValue/TagType）→ ${rel}:${no}\n    ${trimmed.slice(0, 100)}`,
        )
      }
    }

    // F4-1：禁止按 HTTP 200 判成功（本项目成功码是 0）
    if (/code\s*[!=]==?\s*200/.test(line) || /CODE_SUCCESS\s*=\s*200/.test(line)) {
      violations.push(
        `F4  成功码必须是 0（本项目口径），禁止出现 200 判定 → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`,
      )
    }

    // F4-2：禁止 apiCode（本项目无此体系）
    if (/apiCode/i.test(line)) {
      violations.push(`F4  本项目无 apiCode 体系，请删除 → ${rel}:${no}\n    ${trimmed.slice(0, 100)}`)
    }

    // F4-3：业务代码禁止手写幂等键头（必须由 utils/request.ts 统一生成，否则重试复用会漏）
    //
    // 注意：**注释行不算违规**。文档里提到这个头名是正常的（本文件的说明、idem.ts 的模块注释都提到它），
    // 若把注释也判违规，脚本会天天报假警，最后没人看它。
    const isCommentLine = /^\s*(\*|\/\/|\/\*)/.test(line)
    if (!isSpec && !isCommentLine && rel !== 'src/utils/request.ts' && /['"`]Idempotency-Key['"`]/.test(line)) {
      violations.push(
        `F4  幂等键禁止在业务代码手写（应由 utils/request.ts 统一生成并复用）→ ${rel}:${no}\n    ${trimmed.slice(0, 100)}`,
      )
    }
  })

  // R7：模板业务字面量检查（仅 .vue 模板区间）
  if (isVue) {
    for (const { text, line } of templateLines(content)) {
      const compareRe = /={2,3}\s*'(Y|N|M|F|C|P|DIR|PAGE|BUTTON)'/
      if (compareRe.test(text)) {
        violations.push(`R7  模板比较不得写业务字面量（改用常量或取用函数）→ ${rel}:${line}\n    ${text.trim().slice(0, 100)}`)
      }
      const ternaryCnRe = /\?\s*'[^']*[\u4e00-\u9fa5][^']*'\s*:/
      if (ternaryCnRe.test(text)) {
        violations.push(`R7  模板三元不得内联中文文案（改用 models 映射/computed）→ ${rel}:${line}\n    ${text.trim().slice(0, 100)}`)
      }
    }
  }

  // R5：列表页初始加载态（提示级）
  if (rel.startsWith('src/views/') && isVue) {
    const hasFetch = /async function load(List|Tree|Data)?\(/.test(content)
    if (hasFetch) {
      if (!/v-loading="loading"/.test(content)) {
        hints.push(`R5  列表页根容器建议挂 v-loading="loading" → ${rel}`)
      }
      if (!/const loading = ref\(true\)/.test(content)) {
        hints.push(`R5  列表页 loading 建议初始为 true（onReady 后 finally 关闭）→ ${rel}`)
      }
    }
  }
}

// F4-4：接入契约的**唯一实现处**必须存在，且成功码为 0
const requestFile = join(SRC, 'utils', 'request.ts')
if (!existsSync(requestFile)) {
  violations.push('F4  缺少 src/utils/request.ts（接入契约的唯一实现处：code===0 / 幂等键 / 401-403 分支）')
} else if (!/CODE_SUCCESS\s*=\s*0\b/.test(readFileSync(requestFile, 'utf8'))) {
  violations.push('F4  src/utils/request.ts 必须显式声明 `CODE_SUCCESS = 0`')
}

/** 输出报告。 */
const report = []
report.push('# 规范自检报告（R1–R12 子集 + 本项目 F4 改写）')
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
