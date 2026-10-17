#!/usr/bin/env node
/**
 * 前后端接口契约校验：把 `src/models/*.ts` 里实际调用的接口，与后端生成的契约清单逐条比对。
 *
 * 为什么需要它：前端把路径写错（拼错、少一段、动词用错）**在类型检查里是发现不了的**
 * （路径只是字符串），本地不联调就完全看不出来；等到联调时才一个个试，代价很高。
 * 这个脚本把"路径是否真实存在、HTTP 动词是否匹配"变成一次可重复执行的机械校验。
 *
 * 用法：
 *   pnpm check:api            # 默认比对 ../wms-platform/docs/frontend/api-contract.md
 *   node scripts/check-api-paths.mjs <contract.md 路径>
 *
 * 退出码：0 = 一致；1 = 存在对不上的调用。
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join, relative } from 'node:path'

/** 后端契约清单路径（可被参数覆盖）。 */
const contractPath = process.argv[2] ?? '../wms-platform/docs/frontend/api-contract.md'
const ROOT = process.cwd()
const MODELS = join(ROOT, 'src', 'models')

/**
 * 归一化路径：
 * 1. **去掉查询串**（`?enabled=true` 不是路径的一部分，契约清单里也不带）；
 * 2. 把 `{param}` 与模板变量 `${...}` 统一成 `{}`；
 * 3. 去掉尾部斜杠。
 *
 * @param path 原始路径
 * @returns 归一化路径
 */
function normalize(path) {
  return path
    .split('?')[0]
    .replace(/\$\{[^}]*\}/g, '{}')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\/+$/, '')
}

// 1. 读契约清单：| POST | `/api/v1/...` | `权限码` |
const contract = new Set()
const contractRaw = readFileSync(contractPath, 'utf8')
for (const line of contractRaw.split('\n')) {
  const match = line.match(/^\|\s*(GET|POST|PUT|PATCH|DELETE)\s*\|\s*`([^`]+)`\s*\|/)
  if (match) {
    contract.add(`${match[1]} ${normalize(match[2])}`)
  }
}

// 2. 扫前端调用：get(...) / post(...) / put(...) 里的字符串字面量或模板串
const calls = []
for (const name of readdirSync(MODELS)) {
  if (!name.endsWith('.ts') || name.endsWith('.spec.ts')) {
    continue
  }
  const file = join(MODELS, name)
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const content = readFileSync(file, 'utf8')
  const lines = content.split('\n')
  lines.forEach((line, index) => {
    // 只认 get</post</put< 这种请求封装的调用（含泛型）
    const match = line.match(/\b(get|post|put|patch|delete)(?:<[^>]*>)?\(\s*[`']([^`']+)[`']/)
    if (!match) {
      return
    }
    const method = match[1].toUpperCase()
    calls.push({ method, path: normalize(match[2]), at: `${rel}:${index + 1}`, raw: match[2] })
  })
}

// 3. 比对
const missing = []
for (const call of calls) {
  const key = `${call.method} ${call.path}`
  if (!contract.has(key)) {
    missing.push(call)
  }
}

console.log('# 前后端接口契约校验')
console.log('')
console.log(`契约清单：${contractPath}`)
console.log(`契约接口：${contract.size} 个；前端调用：${calls.length} 处`)
console.log('')
if (missing.length === 0) {
  console.log('✅ 前端调用的路径与动词全部存在于后端契约中')
} else {
  console.log('## 对不上的调用（需修复）')
  console.log('')
  for (const item of missing) {
    console.log(`- ${item.method} \`${item.raw}\` → ${item.at}（契约中不存在该接口）`)
  }
  console.log('')
  console.log('提示：若刚加/改了后端接口，先在 wms-platform 执行：')
  console.log('  perl scripts/export-api-contract.pl > docs/frontend/api-contract.md')
}
process.exit(missing.length ? 1 : 0)
