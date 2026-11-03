<script setup lang="ts">
// Importing the setup here keeps ECharts in the /reports lazy chunk only.
import '@/utils/echartsSetup';

import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="报表"
    phase="后续批次"
    description="ECharts 双指标趋势、标签分布与 Top 延迟列表将在业务批次实现；ECharts 只在本路由懒加载。"
    :items="[
      '完成趋势（双指标）与标签分布图',
      '360px 下只保留折线图 + 双指标卡片，横向滚动图表',
      'null 值必须断开折线，不得回退为 0',
    ]"
  />
</template>
