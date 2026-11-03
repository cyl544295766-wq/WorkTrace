<script setup lang="ts">
import { RouterLink } from 'vue-router';

interface NavItem {
  to: string;
  label: string;
}

const mainNav: NavItem[] = [
  { to: '/today', label: '今日' },
  { to: '/tasks', label: '任务' },
  { to: '/notes', label: '笔记' },
  { to: '/time', label: '时间' },
  { to: '/reports', label: '报表' },
  { to: '/weekly', label: '周报' },
  { to: '/settings', label: '设置' },
];

const adminNav: NavItem[] = [
  { to: '/admin/users', label: '账号管理' },
  { to: '/admin/invites', label: '邀请码' },
];
</script>

<template>
  <aside class="wt-sidebar" aria-label="主导航">
    <RouterLink class="wt-brand" to="/today">WorkTrace</RouterLink>

    <nav class="wt-nav">
      <RouterLink v-for="item in mainNav" :key="item.to" class="wt-nav-link" :to="item.to">
        {{ item.label }}
      </RouterLink>
      <RouterLink v-for="item in adminNav" :key="item.to" class="wt-nav-link" :to="item.to">
        {{ item.label }}
      </RouterLink>
    </nav>

    <div class="wt-sidebar-foot">
      <span>M1 工程骨架</span>
      <span class="wt-sidebar-version num">v0.1.0</span>
    </div>
  </aside>
</template>

<style scoped>
.wt-sidebar-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
}

.wt-sidebar-version {
  color: var(--wt-text-3);
}
</style>
