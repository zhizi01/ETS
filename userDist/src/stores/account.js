import { defineStore } from 'pinia'
import { accountApi } from '@/api/user'

const ACCT_KEY = 'ets.user.accountId'

export const useAccountStore = defineStore('account', {
  state: () => ({
    accounts: [],
    loading: false,
    loaded: false,
    currentId: localStorage.getItem(ACCT_KEY) || '',
    summary: null,
    positions: [],
  }),

  getters: {
    current: (s) => s.accounts.find((a) => a.id === s.currentId) || null,
    hasAccounts: (s) => s.accounts.length > 0,
    /** 顶栏切换器用 */
    switcherOptions: (s) =>
      s.accounts.map((a) => ({
        value: a.id,
        label: `${a.code} ${a.name}`,
        memberType: a.memberType,
      })),
    /** 开盘后账户参数被冻结，账户管理页据此整体只读 */
    anyLocked: (s) => s.accounts.some((a) => a.paramsLocked),
    equity: (s) => s.summary?.equity ?? s.accounts.find((a) => a.id === s.currentId)?.equity ?? 0,
    todayPnl: (s) => s.summary?.todayPnl ?? 0,
    totalPnl: (s) => s.summary?.totalPnl ?? 0,
    available: (s) => s.summary?.available ?? 0,
  },

  actions: {
    async load(simId) {
      if (!simId) {
        this.accounts = []
        this.loaded = true
        return []
      }
      this.loading = true
      try {
        this.accounts = (await accountApi.list(simId)) || []
        this.loaded = true
        this.ensureCurrent()
        return this.accounts
      } finally {
        this.loading = false
      }
    },

    /** 保证 currentId 始终指向一个真实存在的账户 */
    ensureCurrent() {
      if (this.currentId && this.accounts.some((a) => a.id === this.currentId)) return
      this.setCurrent(this.accounts[0]?.id || '')
    },

    setCurrent(id) {
      this.currentId = id || ''
      if (id) localStorage.setItem(ACCT_KEY, id)
      else localStorage.removeItem(ACCT_KEY)
      this.summary = null
      this.positions = []
    },

    async loadSummary(id = this.currentId) {
      if (!id) {
        this.summary = null
        return null
      }
      this.summary = await accountApi.summary(id)
      return this.summary
    },

    async loadPositions(id = this.currentId) {
      if (!id) {
        this.positions = []
        return []
      }
      this.positions = (await accountApi.positions(id)) || []
      return this.positions
    },

    async refreshCurrent() {
      if (!this.currentId) return
      await Promise.allSettled([this.loadSummary(), this.loadPositions()])
    },

    reset() {
      this.accounts = []
      this.loaded = false
      this.summary = null
      this.positions = []
      this.currentId = ''
      localStorage.removeItem(ACCT_KEY)
    },
  },
})
