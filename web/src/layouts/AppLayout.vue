<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue';
import { RouterView } from 'vue-router';

import { useUiStore } from '@/stores/ui';

import SideNav from './SideNav.vue';
import TopBar from './TopBar.vue';

const ui = useUiStore();

function syncViewport(): void {
  ui.syncWidth(window.innerWidth);
}

onMounted(() => {
  syncViewport();
  window.addEventListener('resize', syncViewport, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport);
});
</script>

<template>
  <div class="wt-shell">
    <SideNav />
    <div class="wt-main">
      <TopBar />
      <main class="wt-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
