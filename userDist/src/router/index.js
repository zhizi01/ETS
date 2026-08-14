import { createRouter, createWebHistory } from 'vue-router'
import { getToken, setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', redirect: '/lobby' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/lobby',
    name: 'lobby',
    component: () => import('@/views/LobbyView.vue'),
    meta: { title: '模拟盘大厅' },
  },
  {
    path: '/sim/:simId',
    component: () => import('@/components/layout/AppLayout.vue'),
    props: true,
    children: [
      { path: '', redirect: (to) => `/sim/${to.params.simId}/terminal` },
      {
        path: 'accounts',
        name: 'accounts',
        component: () => import('@/views/AccountsView.vue'),
        meta: { title: '交易账户', nav: 'accounts' },
      },
      {
        path: 'terminal',
        name: 'terminal',
        component: () => import('@/views/TerminalView.vue'),
        meta: { title: '交易终端', nav: 'terminal' },
      },
      {
        path: 'listings',
        name: 'listings',
        component: () => import('@/views/ListingView.vue'),
        meta: { title: '挂牌摘牌', nav: 'listings' },
      },
      {
        path: 'negotiations',
        name: 'negotiations',
        component: () => import('@/views/NegotiationView.vue'),
        meta: { title: '双边协商', nav: 'negotiations' },
      },
      {
        path: 'spot',
        name: 'spot',
        component: () => import('@/views/SpotView.vue'),
        meta: { title: '日前现货', nav: 'spot' },
      },
      {
        path: 'assets',
        name: 'assets',
        component: () => import('@/views/AssetsView.vue'),
        meta: { title: '我的资产', nav: 'assets' },
      },
      {
        path: 'performance',
        name: 'performance',
        component: () => import('@/views/PerformanceView.vue'),
        meta: { title: '绩效与排行', nav: 'performance' },
      },
      {
        path: 'notices',
        name: 'notices',
        component: () => import('@/views/NoticeView.vue'),
        meta: { title: '公告与事件', nav: 'notices' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/lobby' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const logged = !!getToken()
  if (!to.meta?.public && !logged) {
    return { name: 'login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (to.name === 'login' && logged) return { name: 'lobby' }
  if (logged && !to.meta?.public) {
    const auth = useAuthStore()
    if (!auth.validated) {
      try {
        await auth.fetchMe()
      } catch {
        auth.reset()
        return { name: 'login', query: { redirect: to.fullPath } }
      }
    }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title ? `${title} · ETS 电力交易模拟器` : 'ETS 电力交易模拟器'
})

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

export default router
