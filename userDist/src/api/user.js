import http from './http'

/**
 * 用户端接口 /api/user —— 需要 X-Token 且角色为 USER。
 * 路径逐条核对自 controller/user/*.java。
 */

// ── 账号 ─────────────────────────────────────────
export const authApi = {
  me: () => http.get('/user/auth/me'),
  logout: () => http.post('/user/auth/logout'),
  changePassword: (data) => http.post('/user/auth/password', data),
}

// ── 交易账户 ─────────────────────────────────────
export const accountApi = {
  /** silent=true 时失败不弹 toast，大厅逐盘统计我的账户数时用得上 */
  list: (simId, silent = false) => http.get('/user/accounts', { params: { simId }, silent }),
  create: (data) => http.post('/user/accounts', data),
  detail: (id) => http.get(`/user/accounts/${id}`),
  update: (id, data) => http.put(`/user/accounts/${id}`, data),
  remove: (id) => http.delete(`/user/accounts/${id}`),
  positions: (id) => http.get(`/user/accounts/${id}/positions`),
  flows: (id, page = 1, size = 20) => http.get(`/user/accounts/${id}/flows`, { params: { page, size } }),
  summary: (id) => http.get(`/user/accounts/${id}/summary`),
}

// ── 申报单（滚动撮合 / 集中竞价） ────────────────
export const orderApi = {
  place: (data) => http.post('/user/orders', data),
  cancel: (id) => http.delete(`/user/orders/${id}`),
  cancelAll: (data) => http.post('/user/orders/cancel-all', data),
  list: (params) => http.get('/user/orders', { params }),
  trades: (params) => http.get('/user/trades', { params }),
}

// ── 挂牌摘牌 ─────────────────────────────────────
export const listingApi = {
  create: (data) => http.post('/user/listings', data),
  withdraw: (id) => http.delete(`/user/listings/${id}`),
  /** mine=true 时后端强制要求同时带 accountId */
  list: (params) => http.get('/user/listings', { params }),
  detail: (id) => http.get(`/user/listings/${id}`),
  take: (id, data) => http.post(`/user/listings/${id}/take`, data),
}

// ── 双边协商 ─────────────────────────────────────
export const negoApi = {
  counterparties: (simId, accountId) => http.get('/user/counterparties', { params: { simId, accountId } }),
  propose: (data) => http.post('/user/negotiations', data),
  counter: (id, data) => http.post(`/user/negotiations/${id}/counter`, data),
  accept: (id, data) => http.post(`/user/negotiations/${id}/accept`, data),
  reject: (id, data) => http.post(`/user/negotiations/${id}/reject`, data),
  withdraw: (id, data) => http.post(`/user/negotiations/${id}/withdraw`, data),
  list: (params) => http.get('/user/negotiations', { params }),
  detail: (id) => http.get(`/user/negotiations/${id}`),
}

// ── 日前现货 ─────────────────────────────────────
export const spotApi = {
  submit: (data) => http.post('/user/spot/bids', data),
  cancel: (id) => http.delete(`/user/spot/bids/${id}`),
  bids: (accountId, sessionId) => http.get('/user/spot/bids', { params: { accountId, sessionId } }),
  clearing: (sessionId) => http.get('/user/spot/clearing', { params: { sessionId } }),
  myResult: (accountId, sessionId) => http.get('/user/spot/my-result', { params: { accountId, sessionId } }),
}

// ── 场次 / 公告 ──────────────────────────────────
export const sessionApi = {
  list: (params) => http.get('/user/sessions', { params }),
  detail: (id) => http.get(`/user/sessions/${id}`),
}

export const noticeApi = {
  list: (simId, limit = 20) => http.get('/user/notices', { params: { simId, limit } }),
  feed: (simId, limit = 50) => http.get('/user/notices/feed', { params: { simId, limit } }),
}

// ── 合同 / 结算 / 绩效 ───────────────────────────
export const assetApi = {
  contracts: (params) => http.get('/user/contracts', { params }),
  bills: (params) => http.get('/user/bills', { params }),
  portfolio: (accountId) => http.get('/user/portfolio', { params: { accountId } }),
}
