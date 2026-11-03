<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="时间记录"
    phase="后续批次"
    description="时间记录表、手动录入、运行中计时条与周分布图将在业务批次实现。"
    :items="[
      'TimeEntryTable / ManualEntryDialog / RunningTimerBar',
      '同一用户同时只允许一个运行中计时器（由结构约束保证）',
      '计时状态跨页面可见性由后续批次实现',
    ]"
  />
</template>
