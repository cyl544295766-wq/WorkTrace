<script setup lang="ts">
import { RouterLink } from 'vue-router';

import AuthPlaceholder from '@/components/auth/AuthPlaceholder.vue';
</script>

<template>
  <AuthPlaceholder
    title="修改密码"
    description="首次登录强制改密与密码强度校验将在 T05–T08 批次实现。当前仅提供认证布局占位。"
  >
    <RouterLink to="/login">
      <el-button type="primary">返回登录</el-button>
    </RouterLink>
    <RouterLink class="wt-auth-link" to="/today">查看工程骨架</RouterLink>
  </AuthPlaceholder>
</template>
