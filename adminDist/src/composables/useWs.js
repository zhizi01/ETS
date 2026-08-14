import { onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import { TOKEN_KEY } from '../api/request'

const PING_INTERVAL = 20_000
const BACKOFF_BASE = 1000
const BACKOFF_MAX = 30_000

/**
 * 管理端 WebSocket 单例。
 *
 * 上行 {"op":"sub|unsub","topics":[...]} 与 {"op":"ping"}；
 * 下行报文的 type 除契约里的 pong/subscribed/unsubscribed/data/error 外，
 * 后端还会用 dashboard / fundamental / notice / event / eventRemoved 等自定义 type，
 * 因此这里一律按 topic 路由、把 type 原样交给业务方判断，不认识的 type 不丢弃也不报错。
 */
class EtsSocket {
  constructor() {
    this.ws = null
    this.topics = new Map() // topic -> 引用计数
    this.listeners = new Map() // topic -> Set<fn>
    this.anyListeners = new Set()
    this.pingTimer = null
    this.retryTimer = null
    this.attempt = 0
    this.manualClosed = false
    this.state = reactive({ status: 'closed', lastMessageAt: 0, retries: 0 })
  }

  url() {
    const token = localStorage.getItem(TOKEN_KEY) || ''
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${proto}://${window.location.host}/ws?token=${encodeURIComponent(token)}`
  }

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    this.manualClosed = false
    this.state.status = 'connecting'
    let sock
    try {
      sock = new WebSocket(this.url())
    } catch {
      this.scheduleRetry()
      return
    }
    this.ws = sock

    sock.onopen = () => {
      this.attempt = 0
      this.state.retries = 0
      this.state.status = 'open'
      this.resubscribeAll()
      this.startPing()
    }

    sock.onmessage = (ev) => {
      this.state.lastMessageAt = Date.now()
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return
      }
      this.dispatch(msg)
    }

    sock.onclose = () => {
      this.stopPing()
      if (this.manualClosed) {
        this.state.status = 'closed'
        return
      }
      this.state.status = 'closed'
      this.scheduleRetry()
    }

    sock.onerror = () => {
      // onclose 紧随其后，重连逻辑收敛在那里，这里只标状态
      this.state.status = 'error'
    }
  }

  dispatch(msg) {
    const type = msg?.type
    // pong 只用于刷新活跃时间，不打扰业务
    if (type === 'pong') return

    if (type === 'error') {
      // 订阅被拒（比如非管理员订 admin.*）只在控制台留痕，不弹窗打断操作
      console.warn('[ws] 服务端错误:', msg.msg)
      this.anyListeners.forEach((fn) => safeCall(fn, msg))
      return
    }

    if (type === 'subscribed' || type === 'unsubscribed') {
      this.anyListeners.forEach((fn) => safeCall(fn, msg))
      return
    }

    // data / dashboard / fundamental / notice / event / eventRemoved / 任何未知 type
    const topic = msg?.topic
    if (topic && this.listeners.has(topic)) {
      this.listeners.get(topic).forEach((fn) => safeCall(fn, msg))
    }
    this.anyListeners.forEach((fn) => safeCall(fn, msg))
  }

  send(payload) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify(payload))
        return true
      } catch {
        return false
      }
    }
    return false
  }

  startPing() {
    this.stopPing()
    this.pingTimer = setInterval(() => this.send({ op: 'ping' }), PING_INTERVAL)
  }

  stopPing() {
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  scheduleRetry() {
    if (this.retryTimer || this.manualClosed) return
    this.attempt += 1
    this.state.retries = this.attempt
    const delay = Math.min(BACKOFF_BASE * 2 ** (this.attempt - 1), BACKOFF_MAX)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, delay)
  }

  resubscribeAll() {
    const all = [...this.topics.keys()]
    if (all.length) this.send({ op: 'sub', topics: all })
  }

  subscribe(topics) {
    const fresh = []
    topics.forEach((t) => {
      const n = this.topics.get(t) || 0
      this.topics.set(t, n + 1)
      if (n === 0) fresh.push(t)
    })
    if (fresh.length) this.send({ op: 'sub', topics: fresh })
  }

  unsubscribe(topics) {
    const gone = []
    topics.forEach((t) => {
      const n = this.topics.get(t) || 0
      if (n <= 1) {
        this.topics.delete(t)
        gone.push(t)
      } else {
        this.topics.set(t, n - 1)
      }
    })
    if (gone.length) this.send({ op: 'unsub', topics: gone })
  }

  on(topic, fn) {
    if (!this.listeners.has(topic)) this.listeners.set(topic, new Set())
    this.listeners.get(topic).add(fn)
  }

  off(topic, fn) {
    const set = this.listeners.get(topic)
    if (!set) return
    set.delete(fn)
    if (!set.size) this.listeners.delete(topic)
  }

  onAny(fn) {
    this.anyListeners.add(fn)
  }

  offAny(fn) {
    this.anyListeners.delete(fn)
  }

  /** 登录/登出后令牌变了，必须重连才能带上新 token */
  reconnect() {
    this.close()
    this.connect()
  }

  close() {
    this.manualClosed = true
    this.stopPing()
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    if (this.ws) {
      try {
        this.ws.close()
      } catch {
        /* 已经断了 */
      }
      this.ws = null
    }
    this.state.status = 'closed'
  }
}

function safeCall(fn, msg) {
  try {
    fn(msg.data, msg)
  } catch (e) {
    console.warn('[ws] 消息处理异常', e)
  }
}

export const socket = new EtsSocket()

/**
 * 在组件里订阅一组主题，主题随响应式来源（通常是 simId）变化时自动退订并重订。
 *
 * @param {() => string[]} topicsFn 返回本次要订阅的主题；返回空数组表示暂不订阅
 * @param {(data: any, msg: any) => void} handler 收到该组主题的任意报文都会调用，
 *        业务方自行按 msg.type 分派（后端的 type 不止契约里那五种）
 */
export function useWs(topicsFn, handler) {
  let bound = []

  const bind = () => {
    const list = (topicsFn() || []).filter(Boolean)
    if (!list.length) return
    socket.connect()
    socket.subscribe(list)
    list.forEach((t) => socket.on(t, handler))
    bound = list
  }

  const unbind = () => {
    if (!bound.length) return
    bound.forEach((t) => socket.off(t, handler))
    socket.unsubscribe(bound)
    bound = []
  }

  const rebind = () => {
    unbind()
    bind()
  }

  onMounted(bind)
  onBeforeUnmount(unbind)
  // 主题串变了就重订，避免切换演练场后还在收上一场的推送
  watch(
    () => (topicsFn() || []).join('|'),
    () => rebind(),
  )

  return { socket, state: socket.state, rebind }
}
