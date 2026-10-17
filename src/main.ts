import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from '@/App.vue'
import router from '@/router'
import { configureAuthBridge } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(ElementPlus)
app.use(router)

// 1. 把 token 提供者与 401 处理注入请求层（避免 request ↔ store 循环依赖）
const auth = useAuthStore(pinia)
configureAuthBridge(
  () => auth.session?.token ?? null,
  () => {
    // 1.1 401：清会话并跳登录（带上原目标，登录后回跳）
    auth.logout()
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  },
)

app.mount('#app')
