import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

export type Breakpoint = 'mobile' | 'tablet' | 'desktop' | 'wide';

function resolveBreakpoint(width: number): Breakpoint {
  if (width < 768) return 'mobile';
  if (width < 1200) return 'tablet';
  if (width < 1600) return 'desktop';
  return 'wide';
}

export const useUiStore = defineStore('ui', () => {
  const width = ref(typeof window === 'undefined' ? 1200 : window.innerWidth);
  const sidebarCollapsed = ref(false);

  const breakpoint = computed(() => resolveBreakpoint(width.value));
  const isMobile = computed(() => breakpoint.value === 'mobile');

  function syncWidth(nextWidth: number): void {
    width.value = nextWidth;
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value;
  }

  return { width, breakpoint, isMobile, sidebarCollapsed, syncWidth, toggleSidebar };
});
