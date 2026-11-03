<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const title = computed(() => route.meta.title ?? 'WorkTrace');
</script>

<template>
  <header class="wt-topbar">
    <div class="wt-topbar-heading">
      <p class="wt-topbar-kicker">WorkTrace</p>
      <h1 class="wt-topbar-title">{{ title }}</h1>
    </div>
    <div class="wt-topbar-tools">
      <span class="wt-topbar-note">M1 工程骨架 · 占位数据</span>
      <el-tag size="small" effect="plain" type="success">骨架</el-tag>
    </div>
  </header>
</template>

<style scoped>
.wt-topbar-heading {
  min-width: 0;
}

.wt-topbar-kicker {
  color: var(--wt-text-3);
  font-size: 11px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.wt-topbar-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
