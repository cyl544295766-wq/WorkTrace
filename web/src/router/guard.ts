import type { Router } from 'vue-router';

/**
 * T03 only wires document titles.
 * Login, forced password change and role checks belong to T05–T08 (auth) and
 * stay reserved here so the route meta contract does not change later.
 */
export function setupGuards(router: Router): void {
  router.beforeEach((to) => {
    const title = to.meta.title;
    document.title = title ? `${title} · WorkTrace` : 'WorkTrace';
    return true;
  });
}
