import http from './http'

/**
 * 公共接口 /api/pub —— 免鉴权。
 * 路径逐条核对自 controller/pub/{PubAuthController,PubMetaController,PubSimController,PubMarketController}。
 */

export const pubApi = {
  health: () => http.get('/pub/health', { silent: true }),

  enums: () => http.get('/pub/meta/enums'),

  login: (data) => http.post('/pub/auth/login', data),
  register: (data) => http.post('/pub/auth/register', data),

  sims: () => http.get('/pub/sims'),
  sim: (simId) => http.get(`/pub/sims/${simId}`),

  /** 场次列表。后端只有公共版本，/api/user/sessions 并未实现 */
  sessions: (simId) => http.get('/pub/sessions', { params: { simId } }),

  rank: (simId, limit = 50) => http.get('/pub/rank', { params: { simId, limit } }),

  quotes: (simId) => http.get('/pub/market/quotes', { params: { simId } }),
  quote: (simId, instrumentKey) => http.get('/pub/market/quote', { params: { simId, instrumentKey } }),

  kline: (simId, instrumentKey, interval = '1m', limit = 200) =>
    http.get('/pub/market/kline', { params: { simId, instrumentKey, interval, limit } }),

  depth: (sessionId, level = 5) => http.get('/pub/market/depth', { params: { sessionId, level } }),

  marketTrades: (sessionId, limit = 50) =>
    http.get('/pub/market/trades', { params: { sessionId, limit } }),
}

export default pubApi
