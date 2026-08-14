import { defineStore } from 'pinia'
import { pubApi } from '../api'
import { TOKEN_KEY } from '../api/request'

const PROFILE_KEY = 'ets_admin_profile'

function readProfile() {
  try {
    return JSON.parse(localStorage.getItem(PROFILE_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    admin: readProfile(),
    validated: false,
  }),

  getters: {
    loggedIn: (s) => !!s.token,
    displayName: (s) => s.admin?.nickname || s.admin?.username || '管理员',
  },

  actions: {
    async login(username, password) {
      const data = await pubApi.adminLogin(username, password)
      this.token = data.token
      this.admin = data.admin || null
      this.validated = true
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(PROFILE_KEY, JSON.stringify(this.admin))
      return data
    },

    async fetchMe() {
      const admin = await pubApi.adminMe()
      this.admin = admin
      this.validated = true
      localStorage.setItem(PROFILE_KEY, JSON.stringify(admin))
      return admin
    },

    clearLocal() {
      this.token = ''
      this.admin = null
      this.validated = false
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(PROFILE_KEY)
    },

    async logout() {
      try {
        await pubApi.adminLogout()
      } catch {
        // 令牌可能已失效，本地照样清干净
      }
      this.clearLocal()
    },
  },
})
