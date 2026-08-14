<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Activity,
  Bell,
  Bot,
  CalendarClock,
  CircleDollarSign,
  Coins,
  Radio,
  ShieldAlert,
  Users,
  Zap,
} from 'lucide-vue-next'
import EChart from '../components/EChart.vue'
import KpiCard from '../components/KpiCard.vue'
import PanelCard from '../components/PanelCard.vue'
import { eventApi, pubApi, simApi } from '../api'
import { useSimStore } from '../stores/sim'
import { useWs } from '../composables/useWs'
import {
  MEMBER_TYPE_COLOR,
  SERIES_COLORS,
  SOURCE_COLOR,
  NOTICE_LEVEL_COLOR,
  labelOf,
} from '../utils/enums'
import {
  clock,
  duration,
  fromNow,
  money,
  moneyParts,
  mwh,
  mwhParts,
  num,
  pct,
  pctSigned,
  pnlClass,
  price,
} from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)

const loading = ref(false)
const dash = ref(null)
const quotes = ref([])
const fundamental = ref(null)
/** WS 每 2s 推来的运行态，比 REST 快，KPI 优先用它 */
const live = reactive({ sim: null, online: 0, ts: 0 })
const feed = ref([])
const activeEvents = ref([])

/** instrumentKey -> [[ts, price], ...]，先用 K 线铺底，之后由 WS 增量追加 */
const trend = reactive({})
const trendKeys = ref([])
const MAX_POINTS = 240

let restTimer = null
let tickTimer = null
const nowTs = ref(Date.now())

// ══════════════ 取数 ══════════════

async function loadDashboard() {
  if (!simId.value) {
    dash.value = null
    return
  }
  try {
    dash.value = await simApi.dashboard(simId.value)
  } catch {
    dash.value = null
  }
}

async function loadQuotes() {
  if (!simId.value) return
  try {
    quotes.value = (await pubApi.quotes(simId.value)) || []
  } catch {
    quotes.value = []
  }
}

async function loadFundamental() {
  if (!simId.value) return
  try {
    fundamental.value = await eventApi.fundamental(simId.value)
  } catch {
    fundamental.value = null
  }
}

async function loadFeed() {
  if (!simId.value) return
  try {
    const [notices, events] = await Promise.all([
      eventApi.notices(simId.value, 20).catch(() => []),
      eventApi.active(simId.value).catch(() => []),
    ])
    activeEvents.value = events || []
    feed.value = (notices || []).map((n) => ({
      key: 'N' + n.id,
      kind: 'notice',
      level: n.level,
      title: n.title,
      content: n.content,
      ts: n.createdAt,
    }))
  } catch {
    feed.value = []
  }
}

/** 用 1m K 线给走势图铺一段历史，只对有成交的标的做，避免拉一堆空曲线 */
async function seedTrend() {
  Object.keys(trend).forEach((k) => delete trend[k])
  trendKeys.value = []
  if (!simId.value) return

  const active = (quotes.value || [])
    .filter((q) => q.volume > 0 || q.activeSessions > 0)
    .sort((a, b) => b.amount - a.amount)
    .slice(0, 6)

  trendKeys.value = active.map((q) => q.instrumentKey)
  await Promise.all(
    active.map(async (q) => {
      try {
        const bars = await pubApi.kline(simId.value, q.instrumentKey, '1m', 90)
        trend[q.instrumentKey] = (bars || []).map((b) => [b.ts, b.close])
      } catch {
        trend[q.instrumentKey] = []
      }
      if (!trend[q.instrumentKey].length && q.last > 0) {
        trend[q.instrumentKey] = [[q.updatedAt || Date.now(), q.last]]
      }
    }),
  )
}

function pushTrendPoint(key, ts, value) {
  if (!value || value <= 0) return
  const arr = trend[key]
  if (!arr) return
  const lastPoint = arr[arr.length - 1]
  // 同一秒内的重复推送直接覆盖，避免曲线上堆点
  if (lastPoint && ts - lastPoint[0] < 1500) {
    lastPoint[1] = value
    return
  }
  arr.push([ts, value])
  if (arr.length > MAX_POINTS) arr.splice(0, arr.length - MAX_POINTS)
}

async function reloadAll() {
  loading.value = true
  try {
    await Promise.all([loadDashboard(), loadQuotes(), loadFundamental(), loadFeed()])
    await seedTrend()
  } finally {
    loading.value = false
  }
}

// ══════════════ WebSocket ══════════════

/**
 * 大屏推送里的 quotes 是 Quote 实体，比 REST 的 QuoteVO 少了拼好的展示名与枚举原值，
 * 直接覆盖会让图例从「月度 全时段」退化成 MONTHLY_ALL，所以按 instrumentKey 合并回去。
 */
function mergeQuotes(incoming) {
  const prev = new Map(quotes.value.map((q) => [q.instrumentKey, q]))
  return incoming.map((q) => {
    const old = prev.get(q.instrumentKey)
    const name =
      old?.name ||
      [q.varietyLabel, q.periodLabel].filter(Boolean).join(' ') ||
      q.instrumentKey
    return { ...old, ...q, name, variety: old?.variety, periodType: old?.periodType }
  })
}

function onDashboard(data, msg) {
  if (msg?.type !== 'dashboard' || !data) return
  live.sim = data.sim || live.sim
  live.online = data.online ?? live.online
  live.ts = data.ts || Date.now()
  const list = data.quotes || []
  if (list.length) {
    quotes.value = mergeQuotes(list)
    const ts = data.ts || Date.now()
    list.forEach((q) => pushTrendPoint(q.instrumentKey, ts, q.last))
  }
}

function onSimEvent(data, msg) {
  const type = msg?.type
  if (type === 'fundamental') {
    fundamental.value = data
    return
  }
  if (type === 'notice' && data) {
    feed.value = [
      { key: 'N' + data.id, kind: 'notice', level: data.level, title: data.title, content: data.content, ts: data.createdAt },
      ...feed.value,
    ].slice(0, 40)
    return
  }
  if (type === 'event' && data) {
    activeEvents.value = [data, ...activeEvents.value.filter((e) => e.id !== data.id)]
    feed.value = [
      {
        key: 'E' + data.id,
        kind: 'event',
        level: 'URGENT',
        title: `市场事件 · ${labelOf('MarketEventType', data.type)}`,
        content: data.title,
        ts: data.createdAt || Date.now(),
      },
      ...feed.value,
    ].slice(0, 40)
    return
  }
  if (type === 'eventRemoved' && data) {
    activeEvents.value = activeEvents.value.filter((e) => e.id !== data.id)
  }
  // 其余未知 type（后端可能扩展）忽略即可，不影响页面
}

/** 两个主题共用一个入口，按 type 分派；未知 type 静默忽略 */
function onWsMessage(data, msg) {
  if (msg?.type === 'dashboard') onDashboard(data, msg)
  else onSimEvent(data, msg)
}

useWs(() => (simId.value ? [`admin.dashboard.${simId.value}`, `sim.${simId.value}`] : []), onWsMessage)

// ══════════════ 派生数据 ══════════════

const kpi = computed(() => {
  const d = dash.value
  const s = live.sim
  return {
    online: live.ts ? live.online : d?.wsOnlineCount ?? 0,
    accounts: s?.accountCount ?? d?.accountCount ?? 0,
    human: d?.humanAccountCount ?? 0,
    robotAcct: d?.robotAccountCount ?? 0,
    robots: s?.robotCount ?? d?.robotCount ?? 0,
    robotsRunning: s?.robotRunning ?? d?.runningRobotCount ?? 0,
    openSessions: s?.openSessionCount ?? d?.openSessionCount ?? 0,
    sessions: s?.sessionCount ?? d?.sessionCount ?? 0,
    volume: s?.totalVolume ?? d?.totalVolume ?? 0,
    amount: s?.totalAmount ?? d?.totalAmount ?? 0,
    trades: d?.tradeCount ?? 0,
    avgPrice: d?.avgPrice ?? 0,
    bookOrders: s?.bookOrders ?? 0,
    users: d?.userCount ?? 0,
  }
})

const benchmark = computed(() => fundamental.value?.benchmarkPrice || simStore.current?.cfg?.benchmarkPrice || 0)
const eventImpact = computed(() => fundamental.value?.eventImpact || 0)

const trendOption = computed(() => {
  const keys = trendKeys.value.filter((k) => (trend[k] || []).length)
  const nameOf = (k) => quotes.value.find((q) => q.instrumentKey === k)?.name || k
  return {
    grid: { left: 52, right: 16, top: 34, bottom: 26 },
    legend: { top: 0, right: 0, icon: 'roundRect', data: keys.map(nameOf) },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (v == null ? '—' : num(v, 2) + ' 元/MWh'),
    },
    xAxis: { type: 'time', axisLabel: { formatter: (v) => clock(v).slice(0, 5) } },
    yAxis: {
      type: 'value',
      scale: true,
      name: '元/MWh',
      nameTextStyle: { color: '#6e7681', fontSize: 10, padding: [0, 0, 0, -34] },
      axisLabel: { formatter: (v) => num(v, 0) },
    },
    series: keys.map((k, i) => ({
      name: nameOf(k),
      type: 'line',
      showSymbol: false,
      smooth: 0.25,
      sampling: 'lttb',
      lineStyle: { width: 1.6, color: SERIES_COLORS[i % SERIES_COLORS.length] },
      itemStyle: { color: SERIES_COLORS[i % SERIES_COLORS.length] },
      data: trend[k] || [],
    })),
  }
})

const volumeOption = computed(() => {
  const list = (quotes.value || []).filter((q) => q.volume > 0).sort((a, b) => b.volume - a.volume)
  return {
    grid: { left: 58, right: 18, top: 22, bottom: 60 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps) => {
        const p = ps[0]
        const q = list[p.dataIndex]
        return `${p.name}<br/>成交量 ${mwh(q.volume)}<br/>成交额 ${money(q.amount)}<br/>成交笔数 ${q.tradeCount}`
      },
    },
    xAxis: {
      type: 'category',
      data: list.map((q) => q.name || q.instrumentKey),
      axisLabel: { interval: 0, rotate: list.length > 4 ? 22 : 0, fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      name: '万MWh',
      nameTextStyle: { color: '#6e7681', fontSize: 10, padding: [0, 0, 0, -38] },
      axisLabel: { formatter: (v) => num(v / 1e4, 1) },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 34,
        data: list.map((q, i) => ({
          value: q.volume,
          itemStyle: { color: SERIES_COLORS[i % SERIES_COLORS.length], borderRadius: [3, 3, 0, 0] },
        })),
      },
    ],
  }
})

const memberPieOption = computed(() => {
  const list = dash.value?.memberTypeDist || []
  const colors = { GENCO: '#f5222d', CONSUMER: '#2f81f7', RETAILER: '#e3b341' }
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}<br/>账户数 ${p.value} 个（${p.percent}%）<br/>资金合计 ${money(p.data.amount)}`,
    },
    legend: { bottom: 0, icon: 'circle' },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#161b22', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{c} 户', color: '#8b949e', fontSize: 11 },
        labelLine: { length: 8, length2: 8 },
        data: list.map((b) => ({
          name: b.label || b.key,
          value: b.count,
          amount: b.amount,
          itemStyle: { color: colors[b.key] || '#8b949e' },
        })),
      },
    ],
  }
})

const sessionStateOption = computed(() => {
  const list = dash.value?.sessionStateDist || []
  const colors = {
    PENDING: '#6e7681',
    PRE_OPEN: '#2f81f7',
    OPEN: '#00b578',
    SUSPENDED: '#e3b341',
    CLEARING: '#a371f7',
    CLOSED: '#484f58',
    SETTLED: '#39c5cf',
  }
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 场（{d}%）' },
    legend: { bottom: 0, icon: 'circle' },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '44%'],
        itemStyle: { borderColor: '#161b22', borderWidth: 2 },
        label: { show: false },
        data: list.map((b) => ({
          name: b.label || b.key,
          value: b.count,
          itemStyle: { color: colors[b.key] || '#8b949e' },
        })),
      },
    ],
  }
})

const topColumns = [
  { title: '名次', dataIndex: 'rank', width: 56 },
  { title: '账户', dataIndex: 'code', width: 78 },
  { title: '主体名称', dataIndex: 'name', ellipsis: true },
  { title: '类型', dataIndex: 'memberType', width: 88 },
  { title: '来源', dataIndex: 'source', width: 72 },
  { title: '总盈亏(元)', dataIndex: 'totalPnl', width: 118, align: 'right' },
  { title: '收益率', dataIndex: 'returnPct', width: 84, align: 'right' },
  { title: '成交', dataIndex: 'tradeCount', width: 64, align: 'right' },
]

const alerts = computed(() => dash.value?.alerts || [])

const eventCountdown = computed(() =>
  (activeEvents.value || []).map((e) => ({
    ...e,
    remainMs: Math.max(0, (e.expireAt || 0) - nowTs.value),
  })),
)

// ══════════════ 生命周期 ══════════════

onMounted(async () => {
  await simStore.load()
  await reloadAll()
  // WS 负责秒级刷新；REST 只慢速补齐 WS 载荷里没有的排行榜、主体分布与告警
  restTimer = setInterval(() => {
    loadDashboard()
    loadFeed()
  }, 15000)
  tickTimer = setInterval(() => (nowTs.value = Date.now()), 1000)
})

onBeforeUnmount(() => {
  if (restTimer) clearInterval(restTimer)
  if (tickTimer) clearInterval(tickTimer)
})

// 主题的重订由 useWs 内部的 watch 负责，这里只管重新拉 REST 数据
watch(simId, async () => {
  live.sim = null
  live.ts = 0
  await reloadAll()
})
</script>

<template>
  <div v-if="!simId" class="empty-wrap">
    <a-empty description="还没有可监控的演练场，请先到「模拟盘管理」新建一个" />
  </div>

  <div v-else class="dash">
    <!-- 告警条 -->
    <a-alert
      v-if="alerts.length"
      type="error"
      show-icon
      banner
      class="alert-bar"
      :message="`风控告警 ${alerts.length} 条`"
      :description="alerts.join('；')"
    />

    <!-- KPI -->
    <div class="kpi-grid">
      <KpiCard
        label="在线连接数"
        :value="kpi.online"
        unit="个"
        :icon="Radio"
        tone="brand"
        :hint="`WebSocket 实时会话 · ${live.ts ? clock(live.ts) : '等待推送'}`"
      />
      <KpiCard
        label="交易账户数"
        :value="kpi.accounts"
        unit="户"
        :icon="Users"
        :hint="`真人 ${kpi.human} · 机器人 ${kpi.robotAcct} · 用户 ${kpi.users} 名`"
      />
      <KpiCard
        label="策略机器人"
        :value="`${kpi.robotsRunning}/${kpi.robots}`"
        :icon="Bot"
        :tone="kpi.robots && kpi.robotsRunning === 0 ? 'warn' : 'neutral'"
        :hint="`运行中 / 总数 · 订单簿挂单 ${num(kpi.bookOrders, 0)} 笔`"
      />
      <KpiCard
        label="活跃场次"
        :value="`${kpi.openSessions}/${kpi.sessions}`"
        :icon="CalendarClock"
        :hint="'开市中 / 已排期场次'"
      />
      <KpiCard
        label="累计成交量"
        :value="mwhParts(kpi.volume)[0]"
        :unit="mwhParts(kpi.volume)[1]"
        :icon="Zap"
        :hint="`成交 ${num(kpi.trades, 0)} 笔 · 均价 ${price(kpi.avgPrice)} 元/MWh`"
      />
      <KpiCard
        label="累计成交额"
        :value="moneyParts(kpi.amount)[0]"
        :unit="moneyParts(kpi.amount)[1]"
        :icon="Coins"
        :hint="'全品种全场次累计交易额'"
      />
      <KpiCard
        label="当前基准电价"
        :value="price(benchmark)"
        unit="元/MWh"
        :icon="CircleDollarSign"
        :tone="eventImpact > 0 ? 'up' : eventImpact < 0 ? 'down' : 'neutral'"
        :hint="`事件冲击 ${pctSigned(eventImpact)} · 生效事件 ${activeEvents.length} 项`"
      />
      <KpiCard
        label="演练时长"
        :value="dash?.startedAt ? Math.max(0, Math.floor((nowTs - dash.startedAt) / 60000)) : 0"
        unit="分钟"
        :icon="Activity"
        :hint="dash?.startedAt ? `开盘于 ${clock(dash.startedAt)}` : '尚未开盘'"
      />
    </div>

    <!-- 走势 + 分布 -->
    <div class="row-2">
      <PanelCard title="各标的实时价格走势" :body-style="{ padding: '8px 10px 4px' }">
        <template #extra>
          <span class="text-muted" style="font-size: 11px">1 分钟 K 线铺底，WebSocket 秒级追加</span>
        </template>
        <EChart v-if="trendKeys.length" :option="trendOption" :height="286" replace-merge="series" />
        <a-empty v-else description="暂无成交，价格曲线待生成" :style="{ padding: '76px 0' }" />
      </PanelCard>

      <PanelCard title="主体类型分布">
        <EChart v-if="dash?.memberTypeDist?.length" :option="memberPieOption" :height="286" />
        <a-empty v-else description="暂无交易账户" :style="{ padding: '76px 0' }" />
      </PanelCard>
    </div>

    <div class="row-3">
      <PanelCard title="各标的成交量" :body-style="{ padding: '8px 10px 4px' }">
        <EChart
          v-if="quotes.some((q) => q.volume > 0)"
          :option="volumeOption"
          :height="252"
          replace-merge="series"
        />
        <a-empty v-else description="暂无成交" :style="{ padding: '62px 0' }" />
      </PanelCard>

      <PanelCard title="场次状态分布">
        <EChart v-if="dash?.sessionStateDist?.length" :option="sessionStateOption" :height="252" />
        <a-empty v-else description="暂无排期场次" :style="{ padding: '62px 0' }" />
      </PanelCard>

      <PanelCard title="生效中的市场事件">
        <template #extra>
          <a-tag v-if="eventCountdown.length" color="error">{{ eventCountdown.length }} 项生效</a-tag>
        </template>
        <div v-if="eventCountdown.length" class="event-list">
          <div v-for="e in eventCountdown" :key="e.id" class="event-item">
            <div class="row-gap" style="justify-content: space-between">
              <span class="event-type">{{ labelOf('MarketEventType', e.type) }}</span>
              <span class="num" :class="e.magnitude >= 0 ? 'up' : 'down'">
                强度 {{ num(e.magnitude, 2) }}
              </span>
            </div>
            <div class="event-title">{{ e.title }}</div>
            <div class="event-foot">
              <span class="num text-warn">剩余 {{ duration(e.remainMs) }}</span>
              <a-progress
                :percent="Math.min(100, (e.remainMs / Math.max(1, e.expireAt - e.effectiveAt)) * 100)"
                :show-info="false"
                size="small"
                status="active"
                stroke-color="#e3b341"
              />
            </div>
          </div>
        </div>
        <a-empty v-else description="当前无生效事件" :style="{ padding: '62px 0' }" />
      </PanelCard>
    </div>

    <!-- 排行 + 事件流 -->
    <div class="row-bottom">
      <PanelCard title="Top10 盈亏排行" no-pad>
        <template #extra>
          <span class="text-muted" style="font-size: 11px">按总盈亏排序 · 每 15 秒刷新</span>
        </template>
        <a-table
          :columns="topColumns"
          :data-source="dash?.top10 || []"
          :pagination="false"
          size="small"
          row-key="accountId"
          :scroll="{ y: 300 }"
        >
          <template #emptyText>
            <a-empty description="暂无排行数据，等待首笔成交" />
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'rank'">
              <span class="rank-badge" :class="`rank-${record.rank <= 3 ? record.rank : 'n'}`">
                {{ record.rank }}
              </span>
            </template>
            <template v-else-if="column.dataIndex === 'code'">
              <span class="num">{{ record.code }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'memberType'">
              <a-tag :color="MEMBER_TYPE_COLOR[record.memberType]">{{ record.memberTypeLabel }}</a-tag>
            </template>
            <template v-else-if="column.dataIndex === 'source'">
              <a-tag :color="SOURCE_COLOR[record.source]">{{ record.sourceLabel }}</a-tag>
            </template>
            <template v-else-if="column.dataIndex === 'totalPnl'">
              <span class="num" :class="pnlClass(record.totalPnl)">{{ num(record.totalPnl, 0) }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'returnPct'">
              <span class="num" :class="pnlClass(record.returnPct)">{{ pct(record.returnPct) }}</span>
            </template>
          </template>
        </a-table>
      </PanelCard>

      <PanelCard title="实时公告与事件流" :body-style="{ padding: '4px 0 0' }">
        <template #extra>
          <span class="live-dot"></span>
          <span class="text-muted" style="font-size: 11px">实时推送</span>
        </template>
        <div class="feed">
          <div v-if="!feed.length" class="feed-empty">
            <a-empty description="暂无公告与市场事件" />
          </div>
          <div v-for="f in feed" :key="f.key" class="feed-item">
            <div class="feed-head">
              <component :is="f.kind === 'event' ? ShieldAlert : Bell" :size="12" class="feed-icon" />
              <a-tag :color="NOTICE_LEVEL_COLOR[f.level] || 'default'" style="margin: 0">
                {{ labelOf('NoticeLevel', f.level) }}
              </a-tag>
              <span class="feed-title">{{ f.title }}</span>
              <span class="grow"></span>
              <span class="feed-time num">{{ fromNow(f.ts) }}</span>
            </div>
            <div v-if="f.content" class="feed-body">{{ f.content }}</div>
          </div>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<style scoped>
.empty-wrap {
  display: grid;
  place-items: center;
  height: 60vh;
}

.dash {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.alert-bar {
  border-radius: 5px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

@media (min-width: 1500px) {
  .kpi-grid {
    grid-template-columns: repeat(8, minmax(0, 1fr));
  }
}

.row-2 {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(0, 1fr);
  gap: 12px;
}

.row-3 {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr) minmax(0, 1.1fr);
  gap: 12px;
}

.row-bottom {
  display: grid;
  grid-template-columns: minmax(0, 1.75fr) minmax(0, 1fr);
  gap: 12px;
}

@media (max-width: 1280px) {
  .row-2,
  .row-3,
  .row-bottom {
    grid-template-columns: minmax(0, 1fr);
  }
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 生效事件 */
.event-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 252px;
  overflow-y: auto;
}

.event-item {
  padding: 8px 10px;
  border: 1px solid rgba(245, 34, 45, 0.28);
  border-radius: 5px;
  background: rgba(245, 34, 45, 0.06);
}

.event-type {
  font-size: 12px;
  font-weight: 600;
  color: #ff7b72;
}

.event-title {
  margin: 3px 0 6px;
  font-size: 12px;
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.event-foot :deep(.ant-progress) {
  flex: 1;
  margin: 0;
}

/* 排行榜名次 */
.rank-badge {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 700;
  background: #21262d;
  color: var(--text-sub);
}
.rank-1 {
  background: rgba(245, 34, 45, 0.2);
  color: #ff7b72;
}
.rank-2 {
  background: rgba(227, 179, 65, 0.2);
  color: #e3b341;
}
.rank-3 {
  background: rgba(47, 129, 247, 0.2);
  color: #79b8ff;
}

/* 事件流 */
.feed {
  max-height: 336px;
  overflow-y: auto;
}

.feed-empty {
  padding: 80px 0;
}

.feed-item {
  padding: 8px 14px;
  border-bottom: 1px solid var(--border);
}

.feed-item:hover {
  background: #1b222c;
}

.feed-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.feed-icon {
  color: var(--text-muted);
  flex: 0 0 auto;
}

.feed-title {
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.feed-time {
  font-size: 11px;
  color: var(--text-muted);
  flex: 0 0 auto;
}

.feed-body {
  margin-top: 3px;
  padding-left: 18px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #00b578;
  box-shadow: 0 0 6px #00b578;
  animation: blink 1.6s infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
</style>
