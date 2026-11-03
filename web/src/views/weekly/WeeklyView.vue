<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="周报"
    phase="后续批次"
    description="周报自动草稿、可编辑区块、定稿与 Markdown 导出将在业务批次实现。"
    :items="[
      '支持 ?weekStart=2026-11-02（ISO 周一）',
      '自动区块不可删除，仅可编辑评价部分',
      '日报与周报共用的冻结数据口径由后续批次保证',
    ]"
  />
</template>
