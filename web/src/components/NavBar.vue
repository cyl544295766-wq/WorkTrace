<script setup lang="ts">
import { RouterLink, useRouter } from 'vue-router'
import { SwitchButton, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

function logout(): void {
  userStore.clear()
  router.push('/login')
}
</script>

<template>
  <header class="navbar">
    <div class="navbar__left">
      <RouterLink class="navbar__brand" to="/tasks">WorkTrace</RouterLink>
      <nav class="navbar__menu">
        <RouterLink class="navbar__item" to="/tasks">任务管理</RouterLink>
        <RouterLink class="navbar__item" to="/reports">AI 报告</RouterLink>
      </nav>
    </div>

    <div class="navbar__right">
      <el-icon :size="16"><User /></el-icon>
      <span class="navbar__name">{{ userStore.user?.nickname || userStore.user?.username }}</span>
      <el-button text :icon="SwitchButton" @click="logout">退出登录</el-button>
    </div>
  </header>
</template>

<style scoped>
.navbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 24px;
  min-height: 56px;
  padding: 6px 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.navbar__left {
  display: flex;
  align-items: center;
  gap: 24px;
}

.navbar__brand {
  color: var(--el-color-primary);
  font-size: 18px;
  font-weight: 700;
  text-decoration: none;
}

.navbar__menu {
  display: flex;
  gap: 4px;
}

.navbar__item {
  padding: 6px 14px;
  border-radius: 6px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  text-decoration: none;
  transition: background 0.2s, color 0.2s;
}

.navbar__item:hover {
  background: var(--el-fill-color);
  color: var(--el-color-primary);
}

.navbar__item.router-link-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.navbar__right {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.navbar__name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
