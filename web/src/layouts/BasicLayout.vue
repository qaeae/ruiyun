<script setup lang="ts">
/**
 * 基础布局（R10：菜单按权限过滤；R11：跳转保留查询条件）。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { AccountMenuGroups } from '@/models/menu'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 可见菜单分组：**先按权限过滤，再去掉空分组**。
 *
 * 去掉空分组很重要：如果某个分组下的入口全部无权限，仍显示分组标题会让用户
 * 点开后发现"什么都没有"，比直接不显示更让人困惑。
 */
const menuGroups = computed(() =>
  AccountMenuGroups.map((group) => ({
    title: group.title,
    items: group.items.filter((item) => auth.can(item.perm)),
  })).filter((group) => group.items.length > 0),
)

/** 当前选中菜单。 */
const activeMenu = computed(() => route.path)

/**
 * 跳转菜单。
 *
 * @param path 目标路径
 */
function goto(path: string): void {
  router.push(path)
}

/** 退出登录。 */
function handleLogout(): void {
  auth.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout__aside">
      <div class="layout__brand">智能仓储平台</div>
      <el-menu :default-active="activeMenu" class="layout__menu">
        <template v-for="group in menuGroups" :key="group.title">
          <div class="layout__group">{{ group.title }}</div>
          <el-menu-item
            v-for="item in group.items"
            :key="item.path"
            :index="item.path"
            @click="goto(item.path)"
          >
            {{ item.title }}
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout__header">
        <span class="layout__title">{{ route.meta.title }}</span>
        <span class="layout__user">
          {{ auth.session?.username }}<el-tag v-if="auth.isAdmin" type="danger" size="small">超管</el-tag>
          <el-button link type="primary" @click="handleLogout">退出</el-button>
        </span>
      </el-header>
      <el-main class="layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout__aside {
  background: #001529;
}

.layout__brand {
  height: 56px;
  line-height: 56px;
  color: #fff;
  font-weight: 600;
  text-align: center;
  background: #002140;
}

.layout__menu {
  border-right: none;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ebeef5;
  background: #fff;
}

.layout__group {
  padding: 12px 20px 4px;
  color: #8c9aab;
  font-size: 12px;
  letter-spacing: 1px;
}

.layout__title {
  font-size: 16px;
  font-weight: 600;
}

.layout__user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #606266;
}

.layout__main {
  background: #f5f7fa;
}
</style>
