<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="任务"
    phase="后续批次"
    description="任务列表、筛选、快速添加、内联编辑、标签、子任务与计时按钮将在业务批次实现。"
    :items="[
      '支持 ?filter=today|overdue|week|nodate|done',
      'TaskRow（桌面）与 TaskCard（移动）按断点切换',
      '任务详情与接口由后续批次提供',
    ]"
  />
</template>
