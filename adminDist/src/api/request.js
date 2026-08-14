import axios from 'axios'
import { message } from 'ant-design-vue'

export const TOKEN_KEY = 'ets_admin_token'

/** 401 之后只跳一次登录页，避免并发请求刷出一屏报错 */
let redirecting = false

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers['X-Token'] = token
  }
  return config
})

function toLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem('ets_admin_profile')
  const back = window.location.hash || ''
  if (!back.startsWith('#/login')) {
    window.location.hash = '#/login'
  }
  setTimeout(() => (redirecting = false), 800)
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    // 非统一响应体（理论上不会出现）直接透传
    if (!body || typeof body.code === 'undefined') return body

    if (body.code === 0) return body.data

    if (body.code === 401) {
      message.error(body.msg || '登录已失效，请重新登录')
      toLogin()
      return Promise.reject(new Error(body.msg || '未登录'))
    }
    if (body.code === 403) {
      message.error(body.msg || '无权执行该操作')
      return Promise.reject(Object.assign(new Error(body.msg || '无权访问'), { code: 403 }))
    }

    message.error(body.msg || `请求失败（${body.code}）`)
    return Promise.reject(Object.assign(new Error(body.msg || '请求失败'), { code: body.code }))
  },
  (err) => {
    const status = err?.response?.status
    const body = err?.response?.data
    if (status === 401 || body?.code === 401) {
      message.error(body?.msg || '登录已失效，请重新登录')
      toLogin()
    } else if (err.code === 'ECONNABORTED') {
      message.error('请求超时，后端可能正忙')
    } else if (!err.response) {
      message.error('无法连接后端服务（localhost:8080）')
    } else {
      message.error(body?.msg || `网络异常（HTTP ${status}）`)
    }
    return Promise.reject(err)
  },
)

export default http
