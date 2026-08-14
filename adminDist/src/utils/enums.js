/**
 * 枚举字典与配色。值全部对齐 docs/DESIGN.md 第 4 节与后端 domain/enums，
 * 运行时会被 /api/pub/meta/enums 的返回覆盖标签，这里的副本用于首屏与离线兜底。
 */

export const MEMBER_TYPE = [
  { value: 'GENCO', label: '发电企业', color: '#f5222d' },
  { value: 'CONSUMER', label: '电力用户', color: '#2f81f7' },
  { value: 'RETAILER', label: '售电公司', color: '#e3b341' },
]

export const GEN_TYPE = [
  { value: 'COAL', label: '燃煤' },
  { value: 'GAS', label: '燃气' },
  { value: 'HYDRO', label: '水电' },
  { value: 'WIND', label: '风电' },
  { value: 'SOLAR', label: '光伏' },
  { value: 'NUCLEAR', label: '核电' },
  { value: 'STORAGE', label: '储能' },
]

export const LOAD_PROFILE = [
  { value: 'CONTINUOUS', label: '连续生产' },
  { value: 'TWO_SHIFT', label: '两班制' },
  { value: 'THREE_SHIFT', label: '三班制' },
  { value: 'COMMERCIAL', label: '商业' },
  { value: 'RESIDENTIAL', label: '居民' },
]

export const VARIETY = [
  { value: 'ANNUAL', label: '年度' },
  { value: 'MONTHLY', label: '月度' },
  { value: 'MULTI_DAY', label: '月内多日' },
  { value: 'SPOT_DA', label: '日前现货' },
]

export const PERIOD_TYPE = [
  { value: 'ALL', label: '全时段' },
  { value: 'PEAK', label: '峰段' },
  { value: 'FLAT', label: '平段' },
  { value: 'VALLEY', label: '谷段' },
  { value: 'SHARP', label: '尖峰' },
]

export const TRADE_MODE = [
  { value: 'BILATERAL', label: '双边协商' },
  { value: 'CALL_AUCTION', label: '集中竞价' },
  { value: 'LISTING', label: '挂牌摘牌' },
  { value: 'ROLLING_MATCH', label: '滚动撮合' },
  { value: 'SPOT_CLEARING', label: '现货集中出清' },
]

export const ORDER_SOURCE = [
  { value: 'HUMAN', label: '真人' },
  { value: 'ROBOT', label: '机器人' },
]

export const SIM_STATE = [
  { value: 'SETUP', label: '筹备中' },
  { value: 'RUNNING', label: '交易中' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'ENDED', label: '已结束' },
]

export const TSESSION_STATE = [
  { value: 'PENDING', label: '未开始' },
  { value: 'PRE_OPEN', label: '集中申报' },
  { value: 'OPEN', label: '交易中' },
  { value: 'SUSPENDED', label: '临时停牌' },
  { value: 'CLEARING', label: '出清中' },
  { value: 'CLOSED', label: '已闭市' },
  { value: 'SETTLED', label: '已结算' },
]

export const ROBOT_STRATEGY = [
  { value: 'MARKET_MAKER', label: '做市商' },
  { value: 'TREND', label: '趋势跟随' },
  { value: 'COST_BASED', label: '成本定价' },
  { value: 'NOISE', label: '噪声交易' },
  { value: 'ARBITRAGE', label: '跨品种套利' },
  { value: 'DEMAND', label: '刚性需求' },
]

export const NOTICE_LEVEL = [
  { value: 'INFO', label: '普通' },
  { value: 'WARN', label: '重要' },
  { value: 'URGENT', label: '紧急' },
]

/** 九种市场事件，priceImpact 与后端 MarketEventType 一致，用于提交前预览冲击 */
export const MARKET_EVENT_TYPE = [
  { value: 'FUEL_PRICE_UP', label: '燃料涨价', impact: 0.18, icon: 'Flame', hint: '煤价气价上行，火电报价成本抬升' },
  { value: 'FUEL_PRICE_DOWN', label: '燃料降价', impact: -0.15, icon: 'TrendingDown', hint: '燃料成本回落，火电让价空间打开' },
  { value: 'LOAD_SURGE', label: '负荷激增', impact: 0.22, icon: 'Zap', hint: '用电负荷陡增，供需趋紧推高电价' },
  { value: 'LOAD_DROP', label: '负荷骤降', impact: -0.18, icon: 'CircleArrowDown', hint: '负荷回落，富余出力压低成交价' },
  { value: 'RENEWABLE_HIGH', label: '新能源大发', impact: -0.2, icon: 'Wind', hint: '风光出力饱满，挤占火电空间' },
  { value: 'RENEWABLE_LOW', label: '新能源小发', impact: 0.16, icon: 'CloudOff', hint: '风光出力不足，火电顶峰抬价' },
  { value: 'UNIT_OUTAGE', label: '机组非停', impact: 0.25, icon: 'OctagonAlert', hint: '主力机组非计划停运，供给缺口扩大' },
  { value: 'POLICY_PRICE_CAP', label: '限价政策', impact: -0.1, icon: 'Gavel', hint: '监管出台限价，成交重心下移' },
  { value: 'WEATHER_EXTREME', label: '极端天气', impact: 0.3, icon: 'CloudLightning', hint: '极端天气叠加负荷与停机，冲击最猛' },
]

function toMap(list) {
  const m = {}
  list.forEach((i) => (m[i.value] = i))
  return m
}

const MAPS = {
  MemberType: toMap(MEMBER_TYPE),
  GenType: toMap(GEN_TYPE),
  LoadProfile: toMap(LOAD_PROFILE),
  Variety: toMap(VARIETY),
  PeriodType: toMap(PERIOD_TYPE),
  TradeMode: toMap(TRADE_MODE),
  OrderSource: toMap(ORDER_SOURCE),
  SimState: toMap(SIM_STATE),
  TSessionState: toMap(TSESSION_STATE),
  RobotStrategy: toMap(ROBOT_STRATEGY),
  NoticeLevel: toMap(NOTICE_LEVEL),
  MarketEventType: toMap(MARKET_EVENT_TYPE),
}

export function labelOf(dict, value, fallback = '—') {
  if (value == null || value === '') return fallback
  const m = MAPS[dict]
  return (m && m[value] && m[value].label) || value
}

export function itemOf(dict, value) {
  const m = MAPS[dict]
  return (m && m[value]) || null
}

/** ant-design Tag 的语义色 */
export const SIM_STATE_COLOR = {
  SETUP: 'default',
  RUNNING: 'success',
  PAUSED: 'warning',
  ENDED: 'error',
}

export const TSESSION_STATE_COLOR = {
  PENDING: 'default',
  PRE_OPEN: 'processing',
  OPEN: 'success',
  SUSPENDED: 'warning',
  CLEARING: 'purple',
  CLOSED: 'default',
  SETTLED: 'cyan',
}

export const MEMBER_TYPE_COLOR = {
  GENCO: 'red',
  CONSUMER: 'blue',
  RETAILER: 'gold',
}

export const SOURCE_COLOR = {
  HUMAN: 'cyan',
  ROBOT: 'purple',
}

export const STRATEGY_COLOR = {
  MARKET_MAKER: 'blue',
  TREND: 'volcano',
  COST_BASED: 'green',
  NOISE: 'default',
  ARBITRAGE: 'purple',
  DEMAND: 'gold',
}

export const NOTICE_LEVEL_COLOR = {
  INFO: 'default',
  WARN: 'warning',
  URGENT: 'error',
}

/** 图表多线配色，深色底上区分度优先 */
export const SERIES_COLORS = [
  '#2f81f7',
  '#f5222d',
  '#00b578',
  '#e3b341',
  '#a371f7',
  '#3fb950',
  '#ff7b72',
  '#39c5cf',
  '#db6d28',
  '#8b949e',
]
