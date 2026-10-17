<script setup lang="ts">
/**
 * 登录页。
 *
 * 注意：登录接口**免幂等键**（会话建立前无从生成业务幂等键），
 * 但仍是写方法，因此请求层按路径豁免（见 utils/request.ts 的 IDEM_EXEMPT_PATHS）。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { LoginQo } from '@/models/auth'
import { useAuthStore } from '@/stores/auth'
import { withMask, MaskText } from '@/utils/mask'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 表单模型（R1：请求负载由 Qo 产生）。 */
const form = reactive(new LoginQo())

/** 表单引用。 */
const formRef = ref<FormInstance>()

/** 校验规则。 */
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }],
}

/**
 * 提交登录。
 */
async function handleSubmit(): Promise<void> {
  // 1. 前端先校验（减少无效请求；服务端仍会再校验）
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  // 2. 提交（遮罩防止重复点击 → 重复登录请求）
  const done = await withMask(async () => {
    await auth.login(form)
    return true
  }, MaskText.SUBMIT)
  if (!done) {
    return
  }
  // 3. 回跳原目标（守卫在跳转时写入）
  ElMessage.success('登录成功')
  const redirect = (route.query.redirect as string) || '/'
  await router.replace(redirect)
}
</script>

<template>
  <div class="login">
    <el-card class="login__card">
      <h2 class="login__title">智能仓储平台</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @keyup.enter="handleSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="口令" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入口令" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width: 100%" @click="handleSubmit">登录</el-button>
        </el-form-item>
      </el-form>
      <p class="login__tip">权限与可见仓库由登录返回的令牌快照决定；变更授权后请重新登录。</p>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: #f0f2f5;
}

.login__card {
  width: 380px;
}

.login__title {
  margin: 0 0 20px;
  text-align: center;
}

.login__tip {
  margin: 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
