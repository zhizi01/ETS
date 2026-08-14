const NBSP = '—'

function isNum(v) {
  return typeof v === 'number' && Number.isFinite(v)
}

function group(str) {
  const [i, d] = str.split('.')
  return i.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (d ? '.' + d : '')
}

/** 价格 元/MWh，2 位 */
export function price(v, dash = NBSP) {
  return isNum(v) ? group(v.toFixed(2)) : dash
}

/** 电量 MWh，3 位但去掉无意义的尾零 */
export function qty(v, dash = NBSP) {
  if (!isNum(v)) return dash
  const s = v.toFixed(3).replace(/\.?0+$/, '')
  return group(s === '' || s === '-' ? '0' : s)
}

/** 金额 元，2 位 */
export function amount(v, dash = NBSP) {
  return isNum(v) ? group(v.toFixed(2)) : dash
}

/** 大额金额自动换算 万/亿，用于概览卡片 */
export function money(v, dash = NBSP) {
  if (!isNum(v)) return dash
  const abs = Math.abs(v)
  if (abs >= 1e8) return group((v / 1e8).toFixed(2)) + ' 亿'
  if (abs >= 1e4) return group((v / 1e4).toFixed(2)) + ' 万'
  return group(v.toFixed(2))
}

/** 带正负号，用于盈亏 */
export function signed(v, fn = amount, dash = NBSP) {
  if (!isNum(v)) return dash
  return (v > 0 ? '+' : '') + fn(v)
}

export function pct(v, digits = 2, dash = NBSP) {
  if (!isNum(v)) return dash
  return (v > 0 ? '+' : '') + (v * 100).toFixed(digits) + '%'
}

export function ratio(v, digits = 1, dash = NBSP) {
  if (!isNum(v)) return dash
  return (v * 100).toFixed(digits) + '%'
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

export function time(ts, dash = NBSP) {
  if (!ts) return dash
  const d = new Date(ts)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function datetime(ts, dash = NBSP) {
  if (!ts) return dash
  const d = new Date(ts)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`
}

export function date(ts, dash = NBSP) {
  if (!ts) return dash
  const d = new Date(ts)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function today() {
  return date(Date.now())
}

/** 倒计时 12:34:56 */
export function countdown(ms, dash = NBSP) {
  if (!isNum(ms) || ms <= 0) return dash
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/** 涨跌方向：1 红 / -1 绿 / 0 灰 */
export function dirOf(v) {
  if (!isNum(v) || v === 0) return 0
  return v > 0 ? 1 : -1
}

export function dirClass(v) {
  const d = dirOf(v)
  return d === 1 ? 'up' : d === -1 ? 'down' : 'flat'
}

export const DASH = NBSP
