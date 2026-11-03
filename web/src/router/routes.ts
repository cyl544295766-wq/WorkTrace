import type { RouteRecordRaw } from 'vue-router';

/**
 * Route skeleton from design.md §7.2.
 * Every page is lazy loaded, and business pages stay placeholders in this batch.
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/today',
    children: [
      {
        path: 'today',
        name: 'today',
        component: () => import('@/views/today/TodayView.vue'),
        meta: { roles: 'USER', title: '今日工作台' },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('@/views/tasks/TasksView.vue'),
        meta: { roles: 'USER', title: '任务' },
      },
      {
        path: 'notes',
        name: 'notes',
        component: () => import('@/views/notes/NotesView.vue'),
        meta: { roles: 'USER', title: '笔记' },
      },
      {
        path: 'notes/:id',
        name: 'noteEdit',
        component: () => import('@/views/notes/NoteEditView.vue'),
        meta: { roles: 'USER', title: '笔记编辑' },
      },
      {
        path: 'time',
        name: 'time',
        component: () => import('@/views/time/TimeView.vue'),
        meta: { roles: 'USER', title: '时间记录' },
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/reports/ReportsView.vue'),
        meta: { roles: 'USER', title: '报表' },
      },
      {
        path: 'weekly',
        name: 'weekly',
        component: () => import('@/views/weekly/WeeklyView.vue'),
        meta: { roles: 'USER', title: '周报' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { roles: 'USER', title: '设置' },
      },
      {
        path: 'admin/users',
        name: 'adminUsers',
        component: () => import('@/views/admin/AdminUsersView.vue'),
        meta: { roles: 'ADMIN', title: '账号管理' },
      },
      {
        path: 'admin/invites',
        name: 'adminInvites',
        component: () => import('@/views/admin/AdminInvitesView.vue'),
        meta: { roles: 'ADMIN', title: '邀请码' },
      },
    ],
  },
  {
    path: '/',
    component: () => import('@/layouts/AuthLayout.vue'),
    children: [
      {
        path: 'login',
        name: 'login',
        component: () => import('@/views/auth/LoginView.vue'),
        meta: { roles: 'PUBLIC', title: '登录' },
      },
      {
        path: 'register',
        name: 'register',
        component: () => import('@/views/auth/RegisterView.vue'),
        meta: { roles: 'PUBLIC', title: '注册账号' },
      },
      {
        path: 'change-password',
        name: 'changePwd',
        component: () => import('@/views/auth/ChangePasswordView.vue'),
        meta: { roles: 'ANY', title: '修改密码' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { roles: 'PUBLIC', title: '404' },
  },
];
