<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <div class="wt-auth-shell">
    <section class="wt-auth-panel wt-notfound">
      <p class="wt-auth-brand">WorkTrace</p>
      <p class="wt-notfound-code num">404</p>
      <h1 class="wt-auth-title">页面不存在</h1>
      <p class="wt-auth-hint">请求的地址不在当前路由骨架中，或已被移除。</p>
      <div class="wt-auth-actions">
        <el-button type="primary" @click="router.push('/today')">返回今日工作台</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.wt-notfound-code {
  margin: 4px 0 0;
  color: var(--wt-primary);
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
}
</style>
