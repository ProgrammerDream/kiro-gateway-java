import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/admin/dashboard' },
  { path: '/admin', redirect: '/admin/dashboard' },
  { path: '/admin/login', component: () => import('../views/LoginView.vue') },
  { path: '/admin/dashboard', component: () => import('../views/DashboardView.vue') },
  { path: '/admin/accounts', component: () => import('../views/AccountsView.vue') },
  { path: '/admin/conversations', component: () => import('../views/ConversationsView.vue') },
  { path: '/admin/conversations/:traceId', component: () => import('../views/ConversationDetailView.vue') },
  { path: '/admin/app-logs', component: () => import('../views/AppLogsView.vue') },
  { path: '/admin/settings', component: () => import('../views/SettingsView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  if (to.path === '/admin/login') return next()
  const token = localStorage.getItem('admin_token')
  if (!token) return next('/admin/login')
  next()
})

export default router
