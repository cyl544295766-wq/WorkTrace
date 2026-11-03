<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
}>();
</script>

<template>
  <section class="wt-auth-panel">
    <p class="wt-auth-brand">WorkTrace</p>
    <h1 class="wt-auth-title">{{ title }}</h1>
    <p class="wt-auth-hint">{{ description }}</p>
    <div class="wt-auth-actions">
      <slot />
    </div>
    <p class="wt-auth-foot">M1 工程骨架 · 认证模块尚未实现</p>
  </section>
</template>
