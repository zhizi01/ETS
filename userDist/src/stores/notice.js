import { defineStore } from 'pinia'
import { pubApi } from '@/api/pub'
import { noticeApi } from '@/api/user'
import { MARKET_EVENT_TYPE } from '@/utils/enums'

const MAX_FEED = 200
let seq = 0

/**
 * 公告与市场事件。
 * 首屏走 REST 历史，盘中靠 WebSocket 主题 sim.{simId} 增量推送。
 */
export const useNoticeStore = defineStore('notice', {
  state: () => ({
    feed: [],
    unread: 0,
    fundamental: null,
    loadedSimId: '',
  }),

  getters: {
    notices: (s) => s.feed.filter((x) => x.kind === 'notice'),
    events: (s) => s.feed.filter((x) => x.kind === 'event'),
    urgentCount: (s) => s.feed.filter((x) => x.level === 'URGENT' && !x.read).length,
    latest: (s) => s.feed.slice(0, 8),
  },

  actions: {
    /** 收到 sim.{simId} 主题的任意一帧，type 由后端覆写，未知 type 一律归入系统消息 */
    ingest(data, type) {
      const item = normalize(data, type)
      if (!item) return
      if (item.kind === 'fundamental') {
        this.fundamental = item.raw
        return
      }
      if (this.feed.some((x) => x.id === item.id)) return
      this.feed.unshift(item)
      if (this.feed.length > MAX_FEED) this.feed.length = MAX_FEED
      this.unread += 1
    },

    async loadHistory(simId) {
      if (!simId) return
      this.loadedSimId = simId
      let notices = []
      let events = []
      try {
        const feed = await noticeApi.feed(simId, 50)
        notices = feed?.notices || []
        events = feed?.events || []
      } catch {
        const [n, e] = await Promise.allSettled([pubApi.notices(simId, 50), pubApi.events(simId, 50)])
        notices = n.status === 'fulfilled' ? n.value || [] : []
        events = e.status === 'fulfilled' ? e.value || [] : []
      }
      const hist = []
      for (const n of notices) hist.push(normalize(n, 'notice'))
      for (const ev of events) hist.push(normalize(ev, 'event'))
      const live = this.feed.filter((x) => x.kind === 'state' || x.kind === 'system')
      const merged = [...live, ...hist.filter(Boolean)]
      const seen = new Set()
      this.feed = merged.filter((x) => {
        if (!x || seen.has(x.id)) return false
        seen.add(x.id)
        return true
      }).sort((a, b) => (b.ts || 0) - (a.ts || 0)).slice(0, MAX_FEED)
    },

    markAllRead() {
      this.unread = 0
      this.feed.forEach((x) => (x.read = true))
    },

    clear() {
      this.feed = []
      this.unread = 0
      this.loadedSimId = ''
    },
  },
})

function normalize(data, type) {
  const ts = Date.now()
  const id = data?.id || `f${++seq}`

  if (typeof data === 'string') {
    return { id, kind: 'system', level: 'INFO', title: '系统消息', content: data, ts, read: false, raw: data }
  }
  if (!data || typeof data !== 'object') return null

  switch (type) {
    case 'notice':
      return {
        id,
        kind: 'notice',
        level: data.level || 'INFO',
        title: data.title || '市场公告',
        content: data.content || '',
        ts: data.createdAt || ts,
        read: false,
        raw: data,
      }

    case 'event':
      return {
        id,
        kind: 'event',
        level: 'WARN',
        title: data.title || MARKET_EVENT_TYPE[data.type] || '市场事件',
        content: data.description || '',
        eventType: data.type,
        magnitude: data.magnitude,
        ts: data.effectiveAt || ts,
        read: false,
        raw: data,
      }

    case 'eventRemoved':
      return {
        id,
        kind: 'event',
        level: 'INFO',
        title: `事件已撤销：${data.title || MARKET_EVENT_TYPE[data.type] || ''}`,
        content: '该市场事件的影响已提前解除',
        eventType: data.type,
        ts,
        read: false,
        raw: data,
      }

    case 'fundamental':
      return { id, kind: 'fundamental', ts, raw: data }

    default:
      break
  }

  // 无 type 覆写的状态广播：{event:"sim.started"|"session.opened", state, stateLabel, message}
  if (data.event) {
    const isSession = String(data.event).startsWith('session.')
    const who = isSession ? data.sessionName || '交易场次' : data.simName || '模拟盘'
    return {
      id,
      kind: 'state',
      level: 'INFO',
      title: `${who} · ${data.stateLabel || data.state || '状态变更'}`,
      content: data.message || (isSession ? '交易场次状态已变更' : '模拟盘状态已变更'),
      ts: data.ts || ts,
      read: false,
      raw: data,
    }
  }

  return {
    id,
    kind: 'system',
    level: 'INFO',
    title: '系统消息',
    content: JSON.stringify(data).slice(0, 200),
    ts,
    read: false,
    raw: data,
  }
}
