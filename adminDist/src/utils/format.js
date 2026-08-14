const YI = 1e8
const WAN = 1e4

function pad(n) {
  return n < 10 ? '0' + n : String(n)
}

export function num(v, digits = 2) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** 金额紧凑显示：亿 / 万 / 元，用于 KPI 与表格 */
export function money(v, unit = true) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const abs = Math.abs(n)
  if (abs >= YI) return num(n / YI, 2) + (unit ? ' 亿元' : ' 亿')
  if (abs >= WAN) return num(n / WAN, 2) + (unit ? ' 万元' : ' 万')
  return num(n, 2) + (unit ? ' 元' : '')
}

/** 只要数值不要单位，图表 tooltip 用 */
export function moneyShort(v) {
  return money(v, false)
}

/**
 * 拆成 [数值, 单位] 供 KPI 卡片分别渲染。
 * 必须成对取用，否则会出现「0.00」配「万元」这种量纲错位。
 */
export function moneyParts(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return ['—', '']
  const abs = Math.abs(n)
  if (abs >= YI) return [num(n / YI, 2), '亿元']
  if (abs >= WAN) return [num(n / WAN, 2), '万元']
  return [num(n, 2), '元']
}

/** 电量同理 */
export function mwhParts(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return ['—', '']
  if (Math.abs(n) >= WAN) return [num(n / WAN, 2), '万MWh']
  return [num(n, n % 1 === 0 ? 0 : 2), 'MWh']
}

export function moneyFull(v) {
  return num(v, 2)
}

/** 电量：万 MWh / MWh */
export function mwh(v, unit = true) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  if (Math.abs(n) >= WAN) return num(n / WAN, 2) + (unit ? ' 万MWh' : ' 万')
  return num(n, n % 1 === 0 ? 0 : 2) + (unit ? ' MWh' : '')
}

/** 电价 元/MWh，价格保留两位 */
export function price(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return '—'
  return num(n, 2)
}

/** 比例 → 百分数，入参是 0.15 这种小数 */
export function pct(v, digits = 2) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n * 100).toFixed(digits) + '%'
}

export function pctSigned(v, digits = 2) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n >= 0 ? '+' : '') + (n * 100).toFixed(digits) + '%'
}

export function signed(v, digits = 2) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n > 0 ? '+' : '') + num(n, digits)
}

export function datetime(ts) {
  if (!ts) return '—'
  const d = new Date(Number(ts))
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`
}

export function dateOnly(ts) {
  if (!ts) return '—'
  const d = new Date(Number(ts))
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function clock(ts) {
  if (!ts) return '—'
  const d = new Date(Number(ts))
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 毫秒时长 → 1天2时3分 / 05:12 这类紧凑写法 */
export function duration(ms) {
  const t = Math.max(0, Math.floor(Number(ms) / 1000))
  if (!Number.isFinite(t)) return '—'
  const d = Math.floor(t / 86400)
  const h = Math.floor((t % 86400) / 3600)
  const m = Math.floor((t % 3600) / 60)
  const s = t % 60
  if (d > 0) return `${d}天${h}时${m}分`
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}`
  return `${pad(m)}:${pad(s)}`
}

/** 相对现在的口语化时间，事件流用 */
export function fromNow(ts) {
  if (!ts) return '—'
  const diff = Date.now() - Number(ts)
  if (diff < 0) return clock(ts)
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  return dateOnly(ts)
}

/** 盈亏配色：红涨绿跌 */
export function pnlClass(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return 'pnl-zero'
  return n > 0 ? 'pnl-pos' : 'pnl-neg'
}

export function pnlColor(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return '#9aa4b0'
  return n > 0 ? '#f5222d' : '#00b578'
}

/** InputNumber 的千分位 formatter / parser，入参可能是 undefined */
export function thousands(v) {
  return String(v ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function unThousands(v) {
  return String(v ?? '').replace(/,/g, '')
}

/** yyyy-MM-dd，表单默认值用 */
export function todayStr(offsetDays = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** yyyy-MM，结算周期用 */
export function monthStr(offsetMonths = 0) {
  const d = new Date()
  d.setMonth(d.getMonth() + offsetMonths)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

/** 'YYYY-MM-DD HH:mm:ss' → epochMillis，DatePicker 回传的是本地时间串 */
export function strToEpoch(s) {
  if (!s) return null
  const t = new Date(String(s).replace(/-/g, '/')).getTime()
  return Number.isFinite(t) ? t : null
}

/** epochMillis → 'YYYY-MM-DD HH:mm:ss' */
export function epochToStr(ts) {
  if (!ts) return null
  return datetime(ts)
}
