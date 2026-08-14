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
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(PROFILE_KEY, JSON.stringify(this.admin))
      return data
    },

    logout() {
      this.token = ''
      this.admin = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(PROFILE_KEY)
    },
  },
})
