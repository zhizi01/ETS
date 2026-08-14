import { createRouter, createWebHashHistory } from 'vue-router'
import { TOKEN_KEY } from '../api/request'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '管理员登录' },
  },
  {
    path: '/',
    component: () => import('../layout/AppShell.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { title: '监控大屏', group: '运行监控' },
      },
      {
        path: 'sims',
        name: 'sims',
        component: () => import('../views/SimsView.vue'),
        meta: { title: '模拟盘管理', group: '演练组织' },
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('../views/SessionsView.vue'),
        meta: { title: '交易场次', group: '演练组织' },
      },
      {
        path: 'robots',
        name: 'robots',
        component: () => import('../views/RobotsView.vue'),
        meta: { title: '机器人管理', group: '演练组织' },
      },
      {
        path: 'members',
        name: 'members',
        component: () => import('../views/MembersView.vue'),
        meta: { title: '参与者管理', group: '市场主体' },
      },
      {
        path: 'intervene',
        name: 'intervene',
        component: () => import('../views/InterveneView.vue'),
        meta: { title: '市场干预', group: '市场主体' },
      },
      {
        path: 'settle',
        name: 'settle',
        component: () => import('../views/SettleView.vue'),
        meta: { title: '结算管理', group: '清算与运维' },
      },
      {
        path: 'sys',
        name: 'sys',
        component: () => import('../views/SysView.vue'),
        meta: { title: '系统监控', group: '清算与运维' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const logged = !!localStorage.getItem(TOKEN_KEY)
  if (!to.meta?.public && !logged) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (to.path === '/login' && logged) {
    return { path: '/dashboard' }
  }
  if (logged && !to.meta?.public) {
    const auth = useAuthStore()
    if (!auth.validated) {
      try {
        await auth.fetchMe()
      } catch {
        auth.clearLocal()
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 电力交易模拟器管理端` : '电力交易模拟器 · 管理端'
  sessionStorage.removeItem(RELOAD_FLAG)
})

const RELOAD_FLAG = 'ets_admin_chunk_reload'

/**
 * 重新发版后 index.html 仍指向旧的 chunk 文件名，懒加载会直接 404，
 * 表现为点了菜单没反应。这里整页重载一次去取新资源，并用标记位防止死循环。
 */
router.onError((err, to) => {
  const msg = String(err?.message || err || '')
  const isChunkMiss = /dynamically imported module|Importing a module script failed|error loading/i.test(msg)
  if (isChunkMiss && !sessionStorage.getItem(RELOAD_FLAG)) {
    sessionStorage.setItem(RELOAD_FLAG, '1')
    window.location.hash = `#${to.fullPath}`
    window.location.reload()
    return
  }
  console.error('[router] 路由跳转失败：', err)
})

export default router
