<script setup lang="ts">
import PlaceholderPage from '@/components/common/PlaceholderPage.vue';
</script>

<template>
  <PlaceholderPage
    title="笔记"
    phase="后续批次"
    description="Markdown 笔记列表、搜索与编辑入口将在业务批次实现；移动端使用全宽列表。"
    :items="[
      'NotesView / NoteEditView 路由已拆分',
      'Markdown 渲染统一经过 markdown-it + DOMPurify',
      '自动保存与编辑器属于后续批次',
    ]"
  />
</template>
