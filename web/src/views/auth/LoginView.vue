<script setup lang="ts">
import { RouterLink } from 'vue-router';

import AuthPlaceholder from '@/components/auth/AuthPlaceholder.vue';
</script>

<template>
  <AuthPlaceholder
    title="登录"
    description="账号登录、图形验证码与失败锁定将在 T05–T08 批次实现。当前仅提供认证布局占位。"
  >
    <RouterLink to="/today">
      <el-button type="primary">查看工程骨架</el-button>
    </RouterLink>
    <RouterLink class="wt-auth-link" to="/register">注册账号</RouterLink>
  </AuthPlaceholder>
</template>
