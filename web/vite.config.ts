import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * - `@` 指向 `src/`（规范里的路径别名）；
 * - dev 代理把 `/api` 转到后端（默认 8080），前端本地不配 CORS，避免"开发能跑、生产跨域"。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 后端默认端口见 wms-core/src/main/resources/application.yml
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8088', // 默认走网关（8088）；直连单体用 VITE_API_TARGET=http://127.0.0.1:8080
        changeOrigin: true,
      },
    },
  },
})
