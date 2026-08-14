import { defineStore } from 'pinia'
import { simApi } from '../api'

const CURRENT_KEY = 'ets_admin_sim'

/**
 * 全局「当前模拟盘」。管理端几乎每个页面都以 simId 为查询前提，
 * 所以选择结果持久化到 localStorage，刷新后仍停在同一场演练。
 */
export const useSimStore = defineStore('sim', {
  state: () => ({
    sims: [],
    currentId: localStorage.getItem(CURRENT_KEY) || '',
    loading: false,
    loaded: false,
  }),

  getters: {
    current: (s) => s.sims.find((x) => x.id === s.currentId) || null,
    options: (s) => s.sims.map((x) => ({ value: x.id, label: `${x.name}（${x.id}）`, state: x.state })),
    isRunning() {
      return this.current?.state === 'RUNNING'
    },
    isSetup() {
      return this.current?.state === 'SETUP'
    },
  },

  actions: {
    async load(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      try {
        const page = await simApi.list(1, 200)
        this.sims = page?.list || []
        this.loaded = true
        if (!this.currentId || !this.sims.some((x) => x.id === this.currentId)) {
          // 优先落在正在跑的盘上，没有就取第一条
          const running = this.sims.find((x) => x.state === 'RUNNING')
          this.setCurrent(running?.id || this.sims[0]?.id || '')
        }
      } finally {
        this.loading = false
      }
    },

    setCurrent(id) {
      this.currentId = id || ''
      if (id) localStorage.setItem(CURRENT_KEY, id)
      else localStorage.removeItem(CURRENT_KEY)
    },

    /** 生命周期操作后局部刷新，避免整表重拉 */
    patch(sim) {
      if (!sim?.id) return
      const i = this.sims.findIndex((x) => x.id === sim.id)
      if (i >= 0) this.sims[i] = { ...this.sims[i], ...sim }
      else this.sims.unshift(sim)
    },

    remove(id) {
      this.sims = this.sims.filter((x) => x.id !== id)
      if (this.currentId === id) this.setCurrent(this.sims[0]?.id || '')
    },
  },
})
