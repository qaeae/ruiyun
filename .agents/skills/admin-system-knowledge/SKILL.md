---
name: admin-system-knowledge
description: singleProjectForce 项目知识库：Vue 3 + TypeScript 后台管理系统的整体规范（R1–R12 强制规则）、目录结构、models 规范（Qo/Vo/Bo/Dto）、数据流与请求约束、页面书写规范、加载与遮罩、常量与字典、类型规范、注释规范、路由权限、命令与测试。当需要修改本项目代码、新增页面/模块/组件、按根目录 接口文档.md 编写接口与模型类、编写 API 客户端、执行 pnpm 命令，或回答关于本项目结构/架构/规范/数据流的问题时，先读取本技能。
---

# 后台管理系统知识库（技能入口）

singleProjectForce 项目（package 名 `admin-system`）的知识库入口。本文件只做**文档地图 + 规则索引 + 任务索引**；强制规则以 [PROJECT-STANDARD.md](PROJECT-STANDARD.md) 为准。

## 何时使用（满足任一即先读取本技能）

- 修改/新增 `src/`、`public/`、`scripts/` 下任何代码（页面、组件、模型、API、工具、store、路由、配置）
- 按根目录 `接口文档.md` 对齐接口，编写 Qo/Vo/Bo/Dto 模型类
- 执行 `pnpm dev` / `pnpm build` / `pnpm test` / `pnpm build:icons` / `pnpm check`
- 涉及命名、目录结构、数据流、类型、权限、字典、图标库、动态路由、文件上传、页面书写样式等问题
- 新增/缺失接口需要生成代码或记录待补充清单

## 文档地图

| 文档 | 定位 | 何时读 |
|---|---|---|
| [PROJECT-STANDARD.md](PROJECT-STANDARD.md) | **项目整体规范（唯一权威）**：〇规则索引 + 一~十三章（技术栈、目录分层、命名、数据流与模型、TypeScript、页面要点、加载与遮罩、常量与字典、注释、路由权限、公共能力、命令、验收清单） | 任何开发/改造前必读 |
| [models-standard.md](models-standard.md) | **models 细则**：目录划分、文件结构顺序、Qo/Vo/Bo 类书写、mapia 映射、字段类型与命名类型清单、常量形态、通用结构、API 配套、测试、模块模板 | 新增/修改 `src/models/` 任何文件时 |
| [page-standard.md](page-standard.md) | **页面细则**：SFC 与 script 分区、列表页/表单页骨架、样式骨架、页面级交互、页面自检 | 新增或改造页面时 |
| [reference.md](reference.md) | **纯参考资料**：目录结构、命名速查、API/模型/常量/页面清单、交互速查、测试与提交 | 需要查清单时 |

## 知识库资产

| 资产 | 用途 |
|---|---|
| [templates/model-module.ts](templates/model-module.ts) | models 模块完整骨架：复制到 `src/models/pages/{module}.ts` 并替换占位符（快照 Qo + Vo + Query Qo + mapia mapper + Edit Bo + 表单标题） |
| [templates/list-page.vue](templates/list-page.vue) | 列表页完整骨架：复制到 `src/views/system/SysXxx.vue`（script 四分区 + 方法齐全 + 骨架类名 + 样式块） |
| [scripts/check-standard.mjs](scripts/check-standard.mjs) | 规范自检脚本：扫描 `src/` 检查 R1/R2/R5/R6/R7/R8 常见违规，输出「文件:行号 + 修复提示」，有违规则退出码 1 |
| [scripts/audit-standard.mjs](scripts/audit-standard.mjs) | 规范 ↔ 项目一致性审计：API 清单、models 导出登记、apiCode、页面注册与落地、命令表、图标清单、路径引用，输出差异报告 |

运行自检（在项目根目录）：

```bash
pnpm check:standard   # 代码是否符合规范（R1–R12 子集）
pnpm audit:standard   # 规范文档与项目是否一致（清单/登记/注册/命令/路径）
```

## 强制规则索引（R1–R12）

| 编号 | 规则一句话 | 详细 |
|---|---|---|
| R1 | 请求载荷必须由模型转换方法产生（`toParams()` / `toFormData()`），禁止内联对象字面量传 API | PROJECT-STANDARD 四.4.1 |
| R2 | 接口/类/常量统一放 `src/models/`，后缀 Qo/Vo/Bo/Dto；`.vue` 禁止 `interface`/`class` | 三、四.4.2 |
| R3 | `fill()` 用 mapia；数字字段 `Number(x) \|\| 0`；`toParams()` 仅非空字段 | 四.4.3 |
| R4 | 每次请求传 `apiCode`；字段以 `接口文档.md` 为准 | 四.4.4 |
| R5 | 页面 `loading = ref(true)` + 根容器 `v-loading`，资源就绪后才关闭；弹框打开即加载态 | 七.7.1 |
| R6 | 所有写操作用 `withMask(task, text?)`（删除「操作中...」/提交「提交中...」/启停「处理中...」/授权「授权中...」/刷新「刷新中...」/重置「重置中...」/详情「数据加载中...」） | 七.7.2 |
| R7 | 模板禁止业务字面量；字典内容（年龄/资源类型等）走 `dictStore` + 同步 getter，禁止硬编码文案 | 八 |
| R8 | 字典值属性只声明 `string`；字面量类型必须命名后引用（`FlagValue`、`TagType`…） | 五.5.2 |
| R9 | 状态/方法 JSDoc + 方法体 `// 1.` 步骤注释；模板分区块注释 | 九 |
| R10 | 页面组件在 `componentMap` 注册；`main.ts` 先 `preRegisterRoutes()`；按钮 `hasPermission` | 十 |
| R11 | 字典批量加载、跳转保留查询条件、平台切换刷新、图标库 | 十一 |
| R12 | 页面书写：SFC 顺序、script 四分区、骨架类名、方法顺序 | 六；page-standard |

> 代码评审/PR 可直接引用编号（如「符合 R5、R6」）。

## 常用命令

| 命令 | 用途 |
|---|---|
| `pnpm dev` | 启动开发服务器（5173，`predev` 自动生成图标清单） |
| `pnpm build` | 打包到 `dist/`（`prebuild` 自动生成图标清单） |
| `pnpm test` | Vitest 测试（非 watch：`pnpm exec vitest run`；模型测试 `src/models/__tests__/models.test.ts`） |
| `pnpm build:icons` | 生成 `public/icons/list.json` |
| `pnpm check` | `vue-tsc --noEmit` 类型检查（需先安装 `vue-tsc`；仅 .ts 可用 `pnpm exec tsc --noEmit`） |
| `pnpm check:standard` | 规范自检（R1/R2/R5/R6/R7/R8）：`node .agents/skills/admin-system-knowledge/scripts/check-standard.mjs` |
| `pnpm audit:standard` | 规范 ↔ 项目一致性审计（清单/登记/注册/命令/路径差异） |

## 常见任务索引

| 任务 | 步骤 | 参考 |
|---|---|---|
| 新增标准管理页面 | `views/.../Xxx.vue`（按 page-standard 骨架）→ `models/pages/{模块}.ts` → `api/modules/{模块}.ts` 并在 `api/index.ts` 导出 → `componentMap` 注册 → 必要时补 `PAGE_ROUTE_FALLBACK` | R12、R10；page-standard 全文 |
| 新增组件及其模型 | `components/{Name}/{Name}.vue` + `models/components/{name}.ts`（组件可引用页面模型） | R2；models-standard 二 |
| 对齐接口 | 先查 `接口文档.md`，再同步 `models/` → `api/modules/` | R4；PROJECT-STANDARD 四.4.4 |
| 新增写操作 | 模型产出参数 → `withMask` 包裹 → 步骤注释 | R6、R9 |
| 新增菜单图标 | `public/icons/svg/{iconCode}.svg` + `scripts/buildIcons.mjs` 的 `iconNames` 补中文名 → `pnpm build:icons` | R11；PROJECT-STANDARD 十一 |
| 新增字典/字典项 | 后端字典管理维护字典项；前端只需声明字典编码常量 + `loadDictItems` 预加载 + 同步 getter 渲染 | R7；PROJECT-STANDARD 八、十一 |
| 排查类型/控制台告警 | 数字字段归一化、字面量类型命名、模板魔法值、路由未注册 | R3、R8、R7、R10 |
