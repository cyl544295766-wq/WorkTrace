<script setup lang="ts">
import { useDark, useToggle } from '@vueuse/core'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

// 暗黑主题开关：跟随系统偏好，切换时给 <html> 加/移除 dark class
const isDark = useDark()
const toggleDark = useToggle(isDark)

const userStore = useUserStore()
</script>

<template>
  <div class="app">
    <h1>WorkTrace</h1>
    <p>基础环境就绪：Element Plus 全量注册、图标全局注册、明暗主题、Pinia 持久化、Axios 拦截器。</p>

    <el-space>
      <el-button type="primary" :icon="isDark ? Sunny : Moon" @click="toggleDark()">
        {{ isDark ? '切换浅色' : '切换深色' }}
      </el-button>
      <el-button :icon="userStore.isLoggedIn ? undefined : undefined">
        {{ userStore.isLoggedIn ? `已登录：${userStore.user?.nickname}` : '未登录' }}
      </el-button>
    </el-space>
  </div>
</template>

<style scoped>
.app {
  max-width: 720px;
  margin: 80px auto;
  padding: 0 24px;
}
</style>
