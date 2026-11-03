<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="账号管理"
    phase="后续批次"
    description="管理员账号列表、角色分配、禁用与重置密码将在业务批次实现。"
    :items="[
      '路由 meta.roles = ADMIN',
      '前端角色校验只做体验边界，后端 /api/admin/** 必须再次校验',
      '账号接口由后续批次提供',
    ]"
  />
</template>
