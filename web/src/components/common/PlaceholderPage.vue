<script setup lang="ts">
defineProps<{
  title: string;
  phase: string;
  description: string;
  items?: string[];
}>();
</script>

<template>
  <section class="wt-placeholder">
    <div class="wt-placeholder-meta">
      <el-tag size="small" effect="plain">{{ phase }}</el-tag>
      <span>M1 工程骨架 · 占位页面</span>
    </div>
    <h1 class="wt-placeholder-title">{{ title }}</h1>
    <p class="wt-placeholder-text">{{ description }}</p>
    <ul v-if="items?.length" class="wt-placeholder-list">
      <li v-for="item in items" :key="item">{{ item }}</li>
    </ul>
    <slot />
  </section>
</template>

<style scoped>
.wt-placeholder-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: var(--wt-text-2);
}
</style>
