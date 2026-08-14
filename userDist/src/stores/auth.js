import { defineStore } from 'pinia'
import { clearToken, getToken, setToken } from '@/api/http'
import { pubApi } from '@/api/pub'
import { authApi } from '@/api/user'
import { disconnect, reconnect } from '@/composables/useMarketSocket'

const USER_KEY = 'ets.user.profile'

function readCachedUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    user: readCachedUser(),
    checking: false,
    validated: false,
  }),

  getters: {
    logged: (s) => !!s.token,
    displayName: (s) => s.user?.nickname || s.user?.username || '未登录',
    orgName: (s) => s.user?.orgName || '',
  },

  actions: {
    apply(payload) {
      this.token = payload?.token || ''
      this.user = payload?.user || null
      setToken(this.token)
      if (this.user) localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      // 令牌换了，私有主题要用新身份重新鉴权
      reconnect()
    },

    async login(form) {
      this.apply(await pubApi.login(form))
      return this.user
    },

    async register(form) {
      this.apply(await pubApi.register(form))
      return this.user
    },

    async fetchMe() {
      this.checking = true
      try {
        const u = await authApi.me()
        this.user = u
        this.validated = true
        localStorage.setItem(USER_KEY, JSON.stringify(u))
        return u
      } finally {
        this.checking = false
      }
    },

    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 令牌可能已经在服务端失效，本地照样清干净
      }
      this.reset()
    },

    async changePassword(form) {
      await authApi.changePassword(form)
      this.reset()
    },

    reset() {
      this.token = ''
      this.user = null
      this.validated = false
      clearToken()
      localStorage.removeItem(USER_KEY)
      disconnect()
    },
  },
})
