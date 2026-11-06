import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由：登录页 + 任务列表主页。
 * history 模式配合 nginx 的 SPA 回退（try_files … /index.html），深链刷新不 404。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/Home.vue'),
      meta: { title: '首页' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/TaskList.vue'),
      meta: { title: '任务管理' },
    },
    {
      path: '/reports',
      name: 'reports',
      component: () => import('@/views/ReportGenerator.vue'),
      meta: { title: 'AI 报告生成' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/**
 * 认证守卫（登录态联动）：
 * - 未登录访问首页/任务/报告页 → 跳 /login?redirect=原路径
 * - 已登录访问 /login     → 回到 redirect 或首页
 * 登录态来自 Pinia user store（localStorage 持久化，配合 HttpOnly Cookie 会话）。
 */
router.beforeEach((to) => {
  const loggedIn = useUserStore().isLoggedIn

  if (to.name === 'login') {
    if (loggedIn) {
      const redirect = to.query.redirect
      return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : { name: 'home' }
    }
    return true
  }

  if (!loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

/** 同步文档标题（登录/任务页切换时浏览器标签随之变化） */
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · WorkTrace` : 'WorkTrace'
})

export default router
