import { defineStore } from 'pinia'
import { pubApi } from '@/api/pub'
import { sessionApi } from '@/api/user'

const SIM_KEY = 'ets.user.simId'

/** 与 SimConfig 的字段默认值保持一致（DESIGN.md 5.2） */
const DEFAULT_CFG = {
  maxAccountsPerUser: 3,
  initialCapitalMin: 1_000_000,
  initialCapitalMax: 500_000_000,
  feeRateBuy: 0.0005,
  feeRateSell: 0.0005,
  priceLimitPct: 0.15,
  marginRatio: 0.2,
  allowNakedShort: false,
  deviationTolerancePct: 0.03,
  benchmarkPrice: 380,
}

export const useSimStore = defineStore('sim', {
  state: () => ({
    sims: [],
    simsLoading: false,
    sim: null,
    simLoading: false,
    sessions: [],
    sessionsLoading: false,
    /** /api/pub/meta/enums 的原始字典 */
    enums: {},
    currentSimId: localStorage.getItem(SIM_KEY) || '',
  }),

  getters: {
    cfg: (s) => ({ ...DEFAULT_CFG, ...(s.sim?.cfg || {}) }),
    simName: (s) => s.sim?.name || '未选择模拟盘',
    simState: (s) => s.sim?.state || '',
    isSetup: (s) => s.sim?.state === 'SETUP',
    isRunning: (s) => s.sim?.state === 'RUNNING',
    /** 允许下单：只有 RUNNING 阶段接受新申报 */
    tradable: (s) => s.sim?.state === 'RUNNING',

    /** 正在接单的场次（含集中申报），下单面板优先落在这些场次上 */
    liveSessions: (s) => s.sessions.filter((x) => ['OPEN', 'PRE_OPEN'].includes(x.state)),

    sessionsByInstrument: (s) => {
      const map = {}
      for (const sess of s.sessions) {
        const key = sess.instrumentKey
        if (!key) continue
        ;(map[key] ||= []).push(sess)
      }
      return map
    },

    sessionOf: (s) => (id) => s.sessions.find((x) => x.id === id) || null,

    /** 按交易方式过滤，挂牌/协商/现货页各取所需 */
    sessionsOfMode: (s) => (mode) => s.sessions.filter((x) => x.tradeMode === mode),
  },

  actions: {
    async loadSims() {
      this.simsLoading = true
      try {
        this.sims = (await pubApi.sims()) || []
      } finally {
        this.simsLoading = false
      }
      return this.sims
    },

    async loadSim(simId) {
      if (!simId) return null
      this.simLoading = true
      try {
        this.sim = await pubApi.sim(simId)
        this.currentSimId = simId
        localStorage.setItem(SIM_KEY, simId)
        return this.sim
      } finally {
        this.simLoading = false
      }
    },

    async loadSessions(simId = this.currentSimId) {
      if (!simId) return []
      this.sessionsLoading = true
      try {
        try {
          this.sessions = (await sessionApi.list({ simId })) || []
        } catch {
          this.sessions = (await pubApi.sessions(simId)) || []
        }
      } finally {
        this.sessionsLoading = false
      }
      return this.sessions
    },

    async loadEnums() {
      if (Object.keys(this.enums).length) return this.enums
      try {
        this.enums = (await pubApi.enums()) || {}
      } catch {
        // 字典拉不到时各页面用 utils/enums.js 的本地兜底表
        this.enums = {}
      }
      return this.enums
    },

    /** 后端字典优先，取不到就回退本地表 */
    options(name, fallbackDict) {
      const list = this.enums[name]
      if (Array.isArray(list) && list.length) return list
      return Object.entries(fallbackDict || {}).map(([value, label]) => ({ value, label }))
    },

    setCurrentSim(simId) {
      this.currentSimId = simId
      if (simId) localStorage.setItem(SIM_KEY, simId)
      else localStorage.removeItem(SIM_KEY)
    },

    /** WebSocket sim.{simId} 推来的状态变更，直接就地更新避免整页重拉 */
    applySimEvent(payload) {
      if (!payload || typeof payload !== 'object') return
      if (payload.simId && this.sim && payload.simId === this.sim.id && payload.state) {
        this.sim = { ...this.sim, state: payload.state, stateLabel: payload.stateLabel }
      }
      if (payload.sessionId && payload.state) {
        const i = this.sessions.findIndex((x) => x.id === payload.sessionId)
        if (i >= 0) {
          this.sessions[i] = {
            ...this.sessions[i],
            state: payload.state,
            stateLabel: payload.stateLabel,
          }
        }
      }
    },

    reset() {
      this.sim = null
      this.sessions = []
      this.currentSimId = ''
      localStorage.removeItem(SIM_KEY)
    },
  },
})
