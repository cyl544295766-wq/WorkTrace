<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="设置"
    phase="后续批次"
    description="个人资料、通知偏好、外观与安全设置将在业务批次实现。"
    :items="[
      'ProfileForm / NotificationPrefForm',
      '修改密码入口为 /change-password',
      '主题切换预留 data-theme，但本期只交付深青主题',
    ]"
  />
</template>
