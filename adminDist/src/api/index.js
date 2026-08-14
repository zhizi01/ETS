import http from './request'

/** 统一响应已在拦截器里解包，这里的每个方法直接 resolve 出 data */

// ══════════════════ 公共 /api/pub ══════════════════
export const pubApi = {
  health: () => http.get('/pub/health'),
  enums: () => http.get('/pub/meta/enums'),
  adminLogin: (username, password) => http.post('/pub/auth/admin/login', { username, password }),
  adminMe: () => http.get('/admin/auth/me'),
  adminLogout: () => http.post('/admin/auth/logout'),
  sims: () => http.get('/pub/sims'),
  sim: (simId) => http.get(`/pub/sims/${simId}`),
  sessions: (simId) => http.get('/pub/sessions', { params: { simId } }),
  rank: (simId, limit = 50) => http.get('/pub/rank', { params: { simId, limit } }),
  quotes: (simId) => http.get('/pub/market/quotes', { params: { simId } }),
  quote: (simId, instrumentKey) => http.get('/pub/market/quote', { params: { simId, instrumentKey } }),
  kline: (simId, instrumentKey, interval = '1m', limit = 120) =>
    http.get('/pub/market/kline', { params: { simId, instrumentKey, interval, limit } }),
  depth: (sessionId, level = 5) => http.get('/pub/market/depth', { params: { sessionId, level } }),
  trades: (sessionId, limit = 50) => http.get('/pub/market/trades', { params: { sessionId, limit } }),
}

// ══════════════════ 模拟盘 3.1 ══════════════════
export const simApi = {
  list: (page = 1, size = 50) => http.get('/admin/sims', { params: { page, size } }),
  create: (body) => http.post('/admin/sims', body),
  detail: (id) => http.get(`/admin/sims/${id}`),
  update: (id, body) => http.put(`/admin/sims/${id}`, body),
  remove: (id) => http.delete(`/admin/sims/${id}`),
  start: (id) => http.post(`/admin/sims/${id}/start`),
  pause: (id) => http.post(`/admin/sims/${id}/pause`),
  resume: (id) => http.post(`/admin/sims/${id}/resume`),
  end: (id) => http.post(`/admin/sims/${id}/end`),
  reset: (id) => http.post(`/admin/sims/${id}/reset`),
  dashboard: (id) => http.get(`/admin/sims/${id}/dashboard`),
}

// ══════════════════ 交易场次 3.2 ══════════════════
export const sessionApi = {
  list: (simId, page = 1, size = 200) => http.get('/admin/sessions', { params: { simId, page, size } }),
  detail: (id) => http.get(`/admin/sessions/${id}`),
  create: (body) => http.post('/admin/sessions', body),
  update: (id, body) => http.put(`/admin/sessions/${id}`, body),
  remove: (id) => http.delete(`/admin/sessions/${id}`),
  batchGenerate: (simId, preset = 'STANDARD', replaceExisting = false) =>
    http.post('/admin/sessions/batch-generate', { simId, preset, replaceExisting }),
  preOpen: (id) => http.post(`/admin/sessions/${id}/pre-open`),
  open: (id) => http.post(`/admin/sessions/${id}/open`),
  suspend: (id) => http.post(`/admin/sessions/${id}/suspend`),
  clear: (id) => http.post(`/admin/sessions/${id}/clear`),
  close: (id) => http.post(`/admin/sessions/${id}/close`),
}

// ══════════════════ 机器人 3.3 ══════════════════
export const robotApi = {
  list: (simId, page = 1, size = 200) => http.get('/admin/robots', { params: { simId, page, size } }),
  strategies: () => http.get('/admin/robots/strategies'),
  create: (body) => http.post('/admin/robots', body),
  batch: (body) => http.post('/admin/robots/batch', body),
  spawnDefault: (simId) => http.post('/admin/robots/spawn-default', null, { params: { simId } }),
  update: (id, body) => http.put(`/admin/robots/${id}`, body),
  remove: (id) => http.delete(`/admin/robots/${id}`),
  enable: (id) => http.post(`/admin/robots/${id}/enable`),
  disable: (id) => http.post(`/admin/robots/${id}/disable`),
  enableAll: (simId) => http.post('/admin/robots/enable-all', { simId }),
  disableAll: (simId) => http.post('/admin/robots/disable-all', { simId }),
}

// ══════════════════ 参与者 3.4 ══════════════════
export const memberApi = {
  users: (params) => http.get('/admin/users', { params }),
  freeze: (id) => http.post(`/admin/users/${id}/freeze`),
  unfreeze: (id) => http.post(`/admin/users/${id}/unfreeze`),
  resetPassword: (id, newPassword) =>
    http.post(`/admin/users/${id}/reset-password`, newPassword ? { newPassword } : {}),
  accounts: (params) => http.get('/admin/accounts', { params }),
  positions: (id) => http.get(`/admin/accounts/${id}/positions`),
  adjustFund: (id, amount, remark) => http.post(`/admin/accounts/${id}/adjust-fund`, { amount, remark }),
  forceCancel: (id) => http.post(`/admin/accounts/${id}/force-cancel`),
}

// ══════════════════ 市场干预 3.5 ══════════════════
export const eventApi = {
  list: (simId, page = 1, size = 20) => http.get('/admin/events', { params: { simId, page, size } }),
  active: (simId) => http.get('/admin/events/active', { params: { simId } }),
  create: (body) => http.post('/admin/events', body),
  remove: (id) => http.delete(`/admin/events/${id}`),
  fundamental: (simId) => http.get('/admin/fundamental', { params: { simId } }),
  updateFundamental: (simId, body) => http.put('/admin/fundamental', body, { params: { simId } }),
  notices: (simId, limit = 30) => http.get('/admin/notices', { params: { simId, limit } }),
  createNotice: (body) => http.post('/admin/notices', body),
}

// ══════════════════ 结算与系统 3.6 ══════════════════
export const settleApi = {
  run: (simId, period) => http.post('/admin/settle/run', { simId, period }),
  bills: (simId, period, page = 1, size = 20) =>
    http.get('/admin/settle/bills', { params: { simId, period, page, size } }),
  rank: (simId, limit = 100) => http.get('/admin/rank', { params: { simId, limit } }),
}

export const sysApi = {
  metrics: () => http.get('/admin/sys/metrics'),
  audit: (page = 1, size = 20) => http.get('/admin/sys/audit', { params: { page, size } }),
}
