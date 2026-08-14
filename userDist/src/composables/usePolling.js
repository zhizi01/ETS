import { onScopeDispose, ref } from 'vue'

/**
 * 轮询兜底。WebSocket 只推增量，资金/持仓这类没有专属推送主题的数据仍需定时拉取；
 * 页面不可见时自动暂停，切回来立刻补一次，避免后台标签页空转。
 */
export function usePolling(fn, intervalMs = 5000, options = {}) {
  const { immediate = true } = options
  const loading = ref(false)
  let timer = null
  let stopped = false
  let inflight = false

  async function tick() {
    if (inflight || stopped) return
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    inflight = true
    loading.value = true
    try {
      await fn()
    } catch {
      // 单次失败不打断轮询；错误提示已在 axios 拦截器里处理
    } finally {
      inflight = false
      loading.value = false
    }
  }

  function start() {
    stop()
    stopped = false
    if (immediate) tick()
    timer = setInterval(tick, intervalMs)
  }

  function stop() {
    if (timer) clearInterval(timer)
    timer = null
  }

  function onVisible() {
    if (document.visibilityState === 'visible') tick()
  }

  start()
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible)

  onScopeDispose(() => {
    stopped = true
    stop()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
  })

  return { loading, refresh: tick, start, stop }
}
