<script setup lang="ts">
/**
 * 403 无权限页。
 *
 * 与 401 的区别很重要：**这里不是"重新登录能解决"的问题**，
 * 所以不要提供"去登录"按钮，而是引导去申请权限。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="无权限访问" sub-title="当前账号没有该功能的权限，请联系管理员授权">
    <template #extra>
      <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
