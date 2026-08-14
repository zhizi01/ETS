import { onScopeDispose, reactive, readonly, watch } from 'vue'
import { getToken } from '@/api/http'

/**
 * 全局唯一的行情 WebSocket 客户端。
 *
 * 与后端 ws/EtsWebSocketHandler 对齐：
 *   上行 {"op":"sub|unsub","topics":[...]} / {"op":"ping"}
 *   下行 {"type":"pong|subscribed|unsubscribed|error"} 与 {"type":"data","topic":..,"data":..}
 *
 * 后端在 data 帧上会覆写 type（notice / event / eventRemoved / fundamental / listing / taken /
 * dashboard ...），所以这里**按 topic 路由、把 type 仅作为附加信息透传**，
 * 遇到不认识的 type 不报错也不丢弃。
 */

const PING_INTERVAL_MS = 20_000
const BACKOFF_MS = [1000, 2000, 4000, 8000, 15_000, 30_000]

const state = reactive({
  /** idle | connecting | open | closed */
  status: 'idle',
  retries: 0,
  connectedAt: 0,
  lastMessageAt: 0,
  lastError: '',
  topicCount: 0,
})

/** topic -> 引用计数，多个组件订阅同一主题时只发一次 sub */
const desired = new Map()
/** 主题族(quote/depth/...) -> 回调集合 */
const listeners = new Map()

let socket = null
let pingTimer = null
let retryTimer = null
let closedByUs = false
let bootstrapped = false

function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = getToken()
  return `${proto}//${location.host}/ws${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

function familyOf(topic) {
  if (!topic) return ''
  const i = topic.indexOf('.')
  return i < 0 ? topic : topic.slice(0, i)
}

function send(payload) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload))
    return true
  }
  return false
}

function startPing() {
  stopPing()
  pingTimer = setInterval(() => send({ op: 'ping' }), PING_INTERVAL_MS)
}

function stopPing() {
  if (pingTimer) clearInterval(pingTimer)
  pingTimer = null
}

/** 重连成功后把断线前的订阅一次性补回去 */
function resubscribeAll() {
  const topics = [...desired.keys()]
  if (topics.length) send({ op: 'sub', topics })
}

function scheduleReconnect() {
  if (retryTimer || closedByUs) return
  const delay = BACKOFF_MS[Math.min(state.retries, BACKOFF_MS.length - 1)]
  state.retries += 1
  retryTimer = setTimeout(() => {
    retryTimer = null
    connect()
  }, delay + Math.floor(Math.random() * 400))
}

function handleFrame(raw) {
  let msg
  try {
    msg = JSON.parse(raw)
  } catch {
    return
  }
  if (!msg || typeof msg !== 'object') return

  state.lastMessageAt = Date.now()

  switch (msg.type) {
    case 'pong':
      return
    case 'subscribed':
    case 'unsubscribed':
      state.topicCount = desired.size
      return
    case 'error':
      // 订阅越权之类的软错误：记录即可，不打断其它主题
      state.lastError = msg.msg || '未知错误'
      return
    default:
      break
  }

  // data 或任何自定义 type：只要带 topic 就分发
  if (!msg.topic) return
  const family = familyOf(msg.topic)
  const bucket = listeners.get(family)
  if (!bucket || !bucket.size) return
  for (const fn of [...bucket]) {
    try {
      fn(msg.data, msg.topic, msg.type)
    } catch (e) {
      console.warn('[ws] 处理器异常', msg.topic, e)
    }
  }
}

export function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }
  closedByUs = false
  state.status = 'connecting'

  let next
  try {
    next = new WebSocket(wsUrl())
  } catch (e) {
    state.status = 'closed'
    state.lastError = String(e)
    scheduleReconnect()
    return
  }
  socket = next

  next.onopen = () => {
    if (socket !== next) return
    state.status = 'open'
    state.retries = 0
    state.connectedAt = Date.now()
    state.lastError = ''
    resubscribeAll()
    startPing()
  }
  next.onmessage = (ev) => {
    if (socket === next) handleFrame(ev.data)
  }
  next.onerror = () => {
    if (socket === next) state.lastError = '连接异常'
  }
  next.onclose = () => {
    if (socket !== next) return
    socket = null
    stopPing()
    state.status = 'closed'
    if (!closedByUs) scheduleReconnect()
  }
}

export function disconnect() {
  closedByUs = true
  stopPing()
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  if (socket) {
    try {
      socket.close()
    } catch {
      /* 已经断了就算了 */
    }
    socket = null
  }
  state.status = 'idle'
  state.retries = 0
}

/** 令牌变化（登录/登出）后必须换连接，否则私有主题一律鉴权失败 */
export function reconnect() {
  disconnect()
  desired.clear()
  state.topicCount = 0
  connect()
}

export function subscribe(topics) {
  const fresh = []
  for (const t of topics) {
    if (!t) continue
    const n = desired.get(t) || 0
    desired.set(t, n + 1)
    if (n === 0) fresh.push(t)
  }
  state.topicCount = desired.size
  if (fresh.length) send({ op: 'sub', topics: fresh })
}

export function unsubscribe(topics) {
  const gone = []
  for (const t of topics) {
    if (!t) continue
    const n = desired.get(t) || 0
    if (n <= 1) {
      desired.delete(t)
      gone.push(t)
    } else {
      desired.set(t, n - 1)
    }
  }
  state.topicCount = desired.size
  if (gone.length) send({ op: 'unsub', topics: gone })
}

/** 监听某一主题族的推送，返回取消函数 */
export function onTopic(family, handler) {
  let bucket = listeners.get(family)
  if (!bucket) {
    bucket = new Set()
    listeners.set(family, bucket)
  }
  bucket.add(handler)
  return () => bucket.delete(handler)
}

/**
 * 组件内使用：
 *   useMarketSocket({
 *     topics: () => [`quote.${simId}.${ik}`],
 *     quote: (data, topic) => {...},
 *     sim:   (data, topic, type) => {...},
 *   })
 * topics 变化时自动增量订阅/退订，作用域销毁时自动清理。
 */
export function useMarketSocket(options = {}) {
  const { topics, immediate = true, ...handlers } = options

  if (!bootstrapped) {
    bootstrapped = true
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        // 标签页切回来时后端可能已经把空闲连接扫掉了，立即补一次
        if (document.visibilityState === 'visible' && state.status === 'closed') {
          state.retries = 0
          if (retryTimer) {
            clearTimeout(retryTimer)
            retryTimer = null
          }
          connect()
        }
      })
    }
  }

  if (immediate) connect()

  const offs = []
  for (const [family, fn] of Object.entries(handlers)) {
    if (typeof fn === 'function') offs.push(onTopic(family, fn))
  }

  let current = []
  if (typeof topics === 'function') {
    const stop = watch(
      topics,
      (next) => {
        const list = (next || []).filter(Boolean)
        const added = list.filter((t) => !current.includes(t))
        const removed = current.filter((t) => !list.includes(t))
        if (added.length) subscribe(added)
        if (removed.length) unsubscribe(removed)
        current = list
      },
      { immediate: true, deep: true },
    )
    offs.push(stop)
  }

  onScopeDispose(() => {
    offs.forEach((off) => off())
    if (current.length) unsubscribe(current)
    current = []
  })

  return { state: readonly(state) }
}

export const socketState = readonly(state)
