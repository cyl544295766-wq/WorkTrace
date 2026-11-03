<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="邀请码"
    phase="后续批次"
    description="邀请码生成、列表、失效与使用记录将在业务批次实现。"
    :items="[
      '路由 meta.roles = ADMIN',
      '邀请码只允许管理员创建与撤销',
      '接口由后续批次提供',
    ]"
  />
</template>
