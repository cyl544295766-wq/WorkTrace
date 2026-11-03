<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="今日工作台"
    phase="后续批次"
    description="今日总览、待办列表、时间轴与最近笔记将在业务批次实现。当前页面用于验证布局、主题与路由骨架。"
    :items="[
      'OverviewCards / TodayTaskList / TimeAxis / RecentNotes',
      '360px 宽度下无横向滚动',
      '所有颜色来自 tokens.css，不在页面内硬编码',
    ]"
  />
</template>
