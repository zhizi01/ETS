import axios from 'axios'
import { message } from 'ant-design-vue'

const TOKEN_KEY = 'ets.user.token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/** 401 时由 router 注入跳转动作，避免 api 层反向依赖路由 */
let onUnauthorized = null
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

/** 同一秒内的重复错误只弹一次，撮合类接口批量失败时不至于刷屏 */
const recentErrors = new Map()
function toast(text) {
  const now = Date.now()
  if (recentErrors.get(text) > now - 1200) return
  recentErrors.set(text, now)
  if (recentErrors.size > 40) recentErrors.clear()
  message.error(text)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers['X-Token'] = token
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // 非统一响应体（例如代理未生效时返回的 HTML）直接放行给调用方判断
    if (!body || typeof body !== 'object' || !('code' in body)) return body

    if (body.code === 0) return body.data

    if (body.code === 401) {
      clearToken()
      if (!resp.config?.silent) toast(body.msg || '登录已失效，请重新登录')
      onUnauthorized?.()
      return Promise.reject(Object.assign(new Error(body.msg || 'unauthorized'), { code: 401, body }))
    }

    if (!resp.config?.silent) toast(body.msg || `请求失败（${body.code}）`)
    return Promise.reject(Object.assign(new Error(body.msg || 'biz error'), { code: body.code, body }))
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const silent = error.config?.silent

    if (status === 401 || body?.code === 401) {
      clearToken()
      if (!silent) toast(body?.msg || '登录已失效，请重新登录')
      onUnauthorized?.()
      return Promise.reject(Object.assign(error, { code: 401 }))
    }

    let text
    if (body && typeof body === 'object' && body.msg) text = body.msg
    else if (error.code === 'ECONNABORTED') text = '请求超时，后端可能正在忙'
    else if (!error.response) text = '无法连接后端服务（localhost:8080）'
    else text = `请求失败（HTTP ${status}）`

    if (!silent) toast(text)
    return Promise.reject(Object.assign(error, { code: status || -1, msg: text }))
  },
)

/** 静默请求：失败不弹 toast，由调用方自行决定展示方式 */
export function quiet(config) {
  return { ...config, silent: true }
}

export default http
