/**
 * 枚举中文标签的本地兜底表。
 * 后端 /api/pub/meta/enums 是权威来源（simStore 启动时会拉一次），
 * 这里保证接口未就绪时界面依然是中文而不是裸枚举名。
 */

export const MEMBER_TYPE = {
  GENCO: '发电企业',
  CONSUMER: '电力用户',
  RETAILER: '售电公司',
}

export const GEN_TYPE = {
  COAL: '燃煤',
  GAS: '燃气',
  HYDRO: '水电',
  WIND: '风电',
  SOLAR: '光伏',
  NUCLEAR: '核电',
  STORAGE: '储能',
}

/** 机组典型变动成本 元/MWh，创建账户时给个填写参考 */
export const GEN_TYPE_COST = {
  COAL: 320,
  GAS: 430,
  HYDRO: 120,
  WIND: 60,
  SOLAR: 50,
  NUCLEAR: 180,
  STORAGE: 260,
}

export const LOAD_PROFILE = {
  CONTINUOUS: '连续生产',
  TWO_SHIFT: '两班制',
  THREE_SHIFT: '三班制',
  COMMERCIAL: '商业',
  RESIDENTIAL: '居民',
}

export const VARIETY = {
  ANNUAL: '年度',
  MONTHLY: '月度',
  MULTI_DAY: '月内多日',
  SPOT_DA: '日前现货',
}

export const PERIOD_TYPE = {
  ALL: '全时段',
  PEAK: '峰段',
  FLAT: '平段',
  VALLEY: '谷段',
  SHARP: '尖峰',
}

export const TRADE_MODE = {
  BILATERAL: '双边协商',
  CALL_AUCTION: '集中竞价',
  LISTING: '挂牌摘牌',
  ROLLING_MATCH: '滚动撮合',
  SPOT_CLEARING: '现货集中出清',
}

export const SIDE = { BUY: '买入', SELL: '卖出' }

/** 站在电力市场语境下的更贴切说法 */
export const SIDE_TRADE = { BUY: '购电', SELL: '售电' }

export const ORDER_SOURCE = { HUMAN: '真人', ROBOT: '机器人' }

export const TIME_IN_FORCE = {
  GFD: '当日有效',
  GTC: '撤销前有效',
  IOC: '立即成交剩余撤销',
  FOK: '全部成交否则撤销',
}

export const ORDER_STATUS = {
  ACTIVE: '挂单中',
  PARTIAL: '部分成交',
  FILLED: '全部成交',
  CANCELLED: '已撤单',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
}

export const LISTING_STATUS = {
  LISTED: '挂牌中',
  PARTIAL_TAKEN: '部分摘牌',
  FULLY_TAKEN: '全部摘牌',
  WITHDRAWN: '已撤牌',
  EXPIRED: '已过期',
}

export const NEGO_STATUS = {
  PROPOSED: '已发起',
  COUNTERED: '已还价',
  ACCEPTED: '已达成',
  REJECTED: '已拒绝',
  WITHDRAWN: '已撤回',
  EXPIRED: '已过期',
}

export const NEGO_ACTION = {
  PROPOSE: '发起邀约',
  COUNTER: '还价',
  ACCEPT: '接受',
  REJECT: '拒绝',
  WITHDRAW: '撤回',
}

export const CONTRACT_STATUS = {
  EFFECTIVE: '已生效',
  DELIVERING: '交割中',
  DELIVERED: '已交割',
  TERMINATED: '已终止',
}

export const SIM_STATE = {
  SETUP: '筹备中',
  RUNNING: '交易中',
  PAUSED: '已暂停',
  ENDED: '已结束',
}

export const TSESSION_STATE = {
  PENDING: '未开始',
  PRE_OPEN: '集中申报',
  OPEN: '交易中',
  SUSPENDED: '临时停牌',
  CLEARING: '出清中',
  CLOSED: '已闭市',
  SETTLED: '已结算',
}

export const SPOT_BID_STATUS = {
  ACTIVE: '已申报',
  CLEARED: '已出清',
  CANCELLED: '已撤销',
}

export const FLOW_TYPE = {
  DEPOSIT: '初始注资',
  FREEZE: '冻结',
  UNFREEZE: '解冻',
  TRADE_PAY: '购电付款',
  TRADE_RECV: '售电收款',
  FEE: '手续费',
  SETTLE: '结算',
  DEVIATION: '偏差考核',
  ADJUST: '管理员调整',
}

export const NOTICE_LEVEL = { INFO: '普通', WARN: '重要', URGENT: '紧急' }

export const MARKET_EVENT_TYPE = {
  FUEL_PRICE_UP: '燃料涨价',
  FUEL_PRICE_DOWN: '燃料降价',
  LOAD_SURGE: '负荷激增',
  LOAD_DROP: '负荷骤降',
  RENEWABLE_HIGH: '新能源大发',
  RENEWABLE_LOW: '新能源小发',
  UNIT_OUTAGE: '机组非停',
  POLICY_PRICE_CAP: '限价政策',
  WEATHER_EXTREME: '极端天气',
}

/** AntDV Tag 配色 */
export const SIM_STATE_COLOR = {
  SETUP: 'blue',
  RUNNING: 'green',
  PAUSED: 'orange',
  ENDED: 'default',
}

export const TSESSION_STATE_COLOR = {
  PENDING: 'default',
  PRE_OPEN: 'purple',
  OPEN: 'green',
  SUSPENDED: 'orange',
  CLEARING: 'gold',
  CLOSED: 'default',
  SETTLED: 'cyan',
}

export const ORDER_STATUS_COLOR = {
  ACTIVE: 'blue',
  PARTIAL: 'cyan',
  FILLED: 'green',
  CANCELLED: 'default',
  REJECTED: 'red',
  EXPIRED: 'default',
}

export const LISTING_STATUS_COLOR = {
  LISTED: 'blue',
  PARTIAL_TAKEN: 'cyan',
  FULLY_TAKEN: 'green',
  WITHDRAWN: 'default',
  EXPIRED: 'default',
}

export const NEGO_STATUS_COLOR = {
  PROPOSED: 'blue',
  COUNTERED: 'gold',
  ACCEPTED: 'green',
  REJECTED: 'red',
  WITHDRAWN: 'default',
  EXPIRED: 'default',
}

export const CONTRACT_STATUS_COLOR = {
  EFFECTIVE: 'blue',
  DELIVERING: 'gold',
  DELIVERED: 'green',
  TERMINATED: 'default',
}

export const NOTICE_LEVEL_COLOR = { INFO: 'default', WARN: 'orange', URGENT: 'red' }

export const MEMBER_TYPE_COLOR = { GENCO: 'red', CONSUMER: 'blue', RETAILER: 'purple' }

/** 未收录时原样回显，避免出现 undefined */
export function labelOf(dict, value, dash = '—') {
  if (value === null || value === undefined || value === '') return dash
  return dict[value] || value
}

/** MONTHLY_ALL → 月度全时段 */
export function instrumentLabel(instrumentKey) {
  if (!instrumentKey) return '—'
  const cut = instrumentKey.lastIndexOf('_')
  if (cut <= 0) return instrumentKey
  const v = VARIETY[instrumentKey.slice(0, cut)]
  const p = PERIOD_TYPE[instrumentKey.slice(cut + 1)]
  return v && p ? v + p : instrumentKey
}

export function toOptions(dict) {
  return Object.entries(dict).map(([value, label]) => ({ value, label }))
}
