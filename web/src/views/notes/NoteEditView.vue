<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import PlaceholderPage from '@/components/common/PlaceholderPage.vue';

const route = useRoute();
const noteId = computed(() => String(route.params.id ?? ''));
</script>

<template>
  <PlaceholderPage
    title="笔记编辑"
    phase="后续批次"
    description="Markdown 编辑器、预览、自动保存与离开提醒将在业务批次实现。"
    :items="[
      `当前路由参数 id = ${noteId}`,
      '编辑内容只保留在当前会话，不进入全局 store',
      '保存接口由后续批次提供',
    ]"
  />
</template>
