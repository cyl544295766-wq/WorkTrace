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
