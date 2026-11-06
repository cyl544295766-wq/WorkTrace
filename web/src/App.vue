<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import NavBar from '@/components/NavBar.vue'

const route = useRoute()
</script>

<template>
  <!-- 应用外壳：登录页独立无导航，其余页面挂顶部导航串联 -->
  <NavBar v-if="route.name !== 'login'" />
  <RouterView />
</template>

<style>
/* 浏览器默认 body margin:8px 从未被重置——造成整页 8px 偏移 + 上下 16px 溢出 */
body {
  margin: 0;
}

/* 应用壳：列向弹性 + 最小满屏。页面根元素用 flex:1 即可按真实导航高度
   填满视口（无需 calc 减导航高度——导航实际高度含内边距并非 56px） */
#app {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
</style>
