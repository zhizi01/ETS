import { defineStore } from 'pinia'
import { pubApi } from '../api'

/** /api/pub/meta/enums 的字典缓存，形如 { MemberType: [{value,label,...}] } */
export const useMetaStore = defineStore('meta', {
  state: () => ({
    dict: {},
    loaded: false,
  }),

  actions: {
    async load() {
      if (this.loaded) return
      try {
        this.dict = (await pubApi.enums()) || {}
        this.loaded = true
      } catch {
        // 枚举拿不到不阻塞页面，utils/enums.js 里有静态副本兜底
        this.loaded = false
      }
    },

    options(name) {
      return this.dict[name] || []
    },

    label(name, value) {
      const hit = (this.dict[name] || []).find((x) => x.value === value)
      return hit?.label || value || '—'
    },
  },
})
