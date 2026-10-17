/// <reference types="vite/client" />

/** `.vue` 单文件组件类型声明（vue-tsc 需要）。 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
