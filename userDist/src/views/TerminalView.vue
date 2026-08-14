<script setup>
import { computed, ref, watch } from 'vue'
import { Clock, RefreshCw } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import NumFlash from '@/components/common/NumFlash.vue'
import StateTag from '@/components/common/StateTag.vue'
import QuoteList from '@/components/market/QuoteList.vue'
import KlineChart from '@/components/market/KlineChart.vue'
import DepthPanel from '@/components/market/DepthPanel.vue'
import TradeTape from '@/components/market/TradeTape.vue'
import OrderTicket from '@/components/trade/OrderTicket.vue'
import MyOrdersPanel from '@/components/trade/MyOrdersPanel.vue'
import { pubApi } from '@/api/pub'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { useMarketSocket } from '@/composables/useMarketSocket'
import { usePolling } from '@/composables/usePolling'
import { instrumentLabel } from '@/utils/enums'
import { countdown, qty } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

const INTERVALS = [
  { key: '1m', label: '1分' },
  { key: '5m', label: '5分' },
  { key: '15m', label: '15分' },
  { key: '1h', label: '1时' },
  { key: '1d', label: '日线' },
]

const quotes = ref([])
const quotesLoading = ref(false)
const instrument = ref('')
const sessionId = ref('')
const interval = ref('1m')
const bars = ref([])
const klineLoading = ref(false)
const depth = ref(null)
const tape = ref([])
const ordersRef = ref(null)
const ticketRef = ref(null)
const now = ref(Date.now())

const simId = computed(() => simStore.currentSimId)
const quote = computed(() => quotes.value.find((q) => q.instrumentKey === instrument.value) || null)

const sessionOptions = computed(() => {
  const list = simStore.sessionsByInstrument[instrument.value] || []
  return list.map((s) => ({
    value: s.id,
    label: `${s.name}（${s.tradeModeLabel || s.tradeMode} · ${s.stateLabel || s.state}）`,
  }))
})
const session = computed(() => simStore.sessionOf(sessionId.value))

/** 优先落在正在接单的场次上，其次任意一个 */
function pickSession() {
  const list = simStore.sessionsByInstrument[instrument.value] || []
  if (sessionId.value && list.some((s) => s.id === sessionId.value)) return
  const live = list.find((s) => ['OPEN', 'PRE_OPEN'].includes(s.state))
  sessionId.value = (live || list[0])?.id || ''
}

async function loadQuotes() {
  if (!simId.value) return
  quotesLoading.value = true
  try {
    quotes.value = (await pubApi.quotes(simId.value)) || []
    if (!instrument.value || !quotes.value.some((q) => q.instrumentKey === instrument.value)) {
      instrument.value = quotes.value[0]?.instrumentKey || ''
    }
  } finally {
    quotesLoading.value = false
  }
}

async function loadKline() {
  if (!simId.value || !instrument.value) {
    bars.value = []
    return
  }
  klineLoading.value = true
  try {
    bars.value = (await pubApi.kline(simId.value, instrument.value, interval.value, 300)) || []
  } finally {
    klineLoading.value = false
  }
}

async function loadBook() {
  if (!sessionId.value) {
    depth.value = null
    tape.value = []
    return
  }
  const [d, t] = await Promise.allSettled([
    pubApi.depth(sessionId.value, 5),
    pubApi.marketTrades(sessionId.value, 60),
  ])
  depth.value = d.status === 'fulfilled' ? d.value : null
  tape.value = t.status === 'fulfilled' ? t.value || [] : []
}

watch(simId, loadQuotes, { immediate: true })
watch(instrument, () => {
  pickSession()
  loadKline()
})
watch(() => simStore.sessions.length, pickSession)
watch(sessionId, loadBook)
watch(interval, loadKline)

// 行情与盘口都有 WS 推送，轮询只作为断线兜底
usePolling(() => {
  now.value = Date.now()
  return Promise.resolve()
}, 1000, { immediate: true })
usePolling(loadQuotes, 15_000, { immediate: false })
usePolling(loadBook, 12_000, { immediate: false })

const topics = computed(() => {
  const list = []
  if (!simId.value) return list
  for (const q of quotes.value) list.push(`quote.${simId.value}.${q.instrumentKey}`)
  if (instrument.value) list.push(`kline.${simId.value}.${instrument.value}.${interval.value}`)
  if (sessionId.value) list.push(`depth.${sessionId.value}`, `trade.${sessionId.value}`)
  return list
})

useMarketSocket({
  topics: () => topics.value,

  quote: (data) => {
    if (!data?.instrumentKey) return
    const i = quotes.value.findIndex((q) => q.instrumentKey === data.instrumentKey)
    // 推送的是 Quote 实体（无 name/varietyLabel），保留列表里已算好的展示字段
    if (i >= 0) quotes.value[i] = { ...quotes.value[i], ...data }
    else quotes.value.push(data)
  },

  depth: (data) => {
    if (data?.sessionId === sessionId.value || !data?.sessionId) depth.value = data
  },

  trade: (data, topic) => {
    if (!data || topic !== `trade.${sessionId.value}`) return
    tape.value = [data, ...tape.value].slice(0, 80)
  },

  kline: (bar, topic) => {
    if (!bar || topic !== `kline.${simId.value}.${instrument.value}.${interval.value}`) return
    const last = bars.value[bars.value.length - 1]
    if (last && last.ts === bar.ts) bars.value.splice(bars.value.length - 1, 1, bar)
    else bars.value = [...bars.value, bar].slice(-500)
  },

  // 自己的委托状态变更后刷新下方列表与资金
  order: () => {
    ordersRef.value?.reload()
    acctStore.refreshCurrent()
  },
})

function onPick(payload) {
  ticketRef.value?.applyQuote(payload)
}

function refreshAll() {
  loadQuotes()
  loadKline()
  loadBook()
  simStore.loadSessions(simId.value)
  ordersRef.value?.reload()
}

const countdownText = computed(() => {
  const s = session.value
  if (!s || !s.countdownMs) return ''
  // countdownMs 是取数那一刻的快照，用本地时钟做线性外推
  const elapsed = now.value - (s.serverTime || now.value)
  return countdown(s.countdownMs - elapsed, '')
})
</script>

<template>
  <div class="terminal">
    <div class="top-row">
      <!-- 左：行情列表 -->
      <PanelBox class="col-quotes" title="标的行情" :sub="`${quotes.length} 个`">
        <template #extra>
          <a-button type="text" size="small" :loading="quotesLoading" @click="loadQuotes">
            <RefreshCw :size="12" />
          </a-button>
        </template>
        <QuoteList v-model="instrument" :quotes="quotes" :loading="quotesLoading" />
      </PanelBox>

      <!-- 中：K 线 + 盘口 -->
      <div class="col-center">
        <PanelBox class="chart-panel">
          <template #title />
          <div class="inst-bar">
            <div class="inst-main">
              <span class="inst-name">{{ quote?.name || instrumentLabel(instrument) || '未选择标的' }}</span>
              <NumFlash :value="quote?.last" :color-by="quote?.change" strong class="big" />
              <NumFlash :value="quote?.change" type="signed" :color-by="quote?.change" />
              <NumFlash :value="quote?.changePct" type="pct" :color-by="quote?.changePct" />
            </div>
            <div class="inst-stats">
              <span>开 <NumFlash :value="quote?.open" :color-by="null" /></span>
              <span>高 <NumFlash :value="quote?.high" class="up" :color-by="null" /></span>
              <span>低 <NumFlash :value="quote?.low" class="down" :color-by="null" /></span>
              <span>均 <NumFlash :value="quote?.avg" :color-by="null" /></span>
              <span>量 <b class="mono">{{ qty(quote?.volume) }}</b> MWh</span>
              <span>{{ quote?.tradeCount || 0 }} 笔</span>
            </div>
          </div>

          <div class="sess-bar">
            <span class="ets-label">交易场次</span>
            <a-select
              v-model:value="sessionId"
              size="small"
              style="min-width: 260px"
              :options="sessionOptions"
              placeholder="该标的暂无场次"
              :disabled="!sessionOptions.length"
            />
            <StateTag v-if="session" kind="session" :value="session.state" :label="session.stateLabel" />
            <StateTag v-if="session" kind="mode" :value="session.tradeMode" :label="session.tradeModeLabel" />
            <span v-if="session?.deliveryStart" class="dl">
              交割 <b class="mono">{{ session.deliveryStart }} ~ {{ session.deliveryEnd }}</b>
            </span>
            <span v-if="countdownText" class="cd">
              <Clock :size="12" />
              <b class="mono">{{ countdownText }}</b>
              <i>{{ session.state === 'PENDING' ? '后开市' : '后闭市' }}</i>
            </span>

            <div class="iv">
              <button
                v-for="i in INTERVALS"
                :key="i.key"
                :class="{ on: interval === i.key }"
                @click="interval = i.key"
              >
                {{ i.label }}
              </button>
              <a-button type="text" size="small" @click="refreshAll"><RefreshCw :size="12" /></a-button>
            </div>
          </div>

          <div class="chart-body">
            <KlineChart :bars="bars" :interval="interval" :loading="klineLoading" height="100%" />
          </div>
        </PanelBox>

        <div class="book-row">
          <PanelBox class="p-depth" title="五档盘口" :sub="depth?.hidden ? '保密' : ''">
            <DepthPanel
              :depth="depth"
              :last-price="session?.lastPrice || quote?.last || 0"
              @pick="onPick"
            />
          </PanelBox>
          <PanelBox class="p-tape" title="逐笔成交" :sub="`${tape.length} 笔`">
            <TradeTape :trades="tape" />
          </PanelBox>
        </div>
      </div>

      <!-- 右：下单面板 -->
      <PanelBox class="col-ticket" :title="`申报下单${acctStore.current ? ' · ' + acctStore.current.code : ''}`">
        <template #extra>
          <span class="ets-label">{{ acctStore.current?.memberTypeLabel || '未选账户' }}</span>
        </template>
        <OrderTicket
          ref="ticketRef"
          :session="session"
          :depth="depth"
          :last-price="quote?.last || 0"
          @placed="ordersRef?.reload()"
        />
      </PanelBox>
    </div>

    <!-- 底部：我的委托 / 成交 / 持仓 -->
    <PanelBox class="bottom-row" :flex="false">
      <MyOrdersPanel ref="ordersRef" :session-id="sessionId" />
    </PanelBox>
  </div>
</template>

<style scoped>
.terminal {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  overflow: hidden;
}

.top-row {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 246px minmax(0, 1fr) 288px;
  gap: 8px;
}

.col-quotes,
.col-ticket {
  min-height: 0;
}

.col-center {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  min-height: 0;
}
.chart-panel {
  flex: 1.35;
  min-height: 0;
}
.chart-panel :deep(.ets-panel-title) {
  display: none;
}
.chart-body {
  flex: 1;
  min-height: 0;
  padding: 2px 0 4px;
}

.inst-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 8px 12px 6px;
  flex: none;
}
.inst-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.inst-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.big {
  font-size: 21px !important;
  font-weight: 700;
}
.inst-stats {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--ets-text-faint);
  flex-wrap: wrap;
}
.inst-stats b {
  color: var(--ets-text-weak);
  font-weight: 500;
}

.sess-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 12px 7px;
  border-bottom: 1px solid var(--ets-line);
  flex: none;
}
.dl {
  font-size: 11px;
  color: var(--ets-text-faint);
}
.dl b {
  color: var(--ets-text-weak);
}
.cd {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: 12px;
  background: var(--ets-raise);
  color: var(--ets-warn);
  font-size: 11px;
}
.cd i {
  font-style: normal;
  color: var(--ets-text-faint);
}
.iv {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}
.iv button {
  height: 22px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--ets-text-weak);
  font-size: 11px;
  cursor: pointer;
}
.iv button:hover {
  background: var(--ets-hover);
}
.iv button.on {
  background: var(--ets-accent-soft);
  border-color: rgba(47, 127, 255, 0.4);
  color: #7fb0ff;
  font-weight: 600;
}

.book-row {
  flex: 1;
  min-height: 190px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.p-depth,
.p-tape {
  min-height: 0;
}

.bottom-row {
  height: 268px;
  flex: none;
}

@media (max-width: 1500px) {
  .top-row {
    grid-template-columns: 210px minmax(0, 1fr) 262px;
  }
}
@media (max-width: 1240px) {
  .terminal {
    overflow: auto;
  }
  .top-row {
    grid-template-columns: minmax(0, 1fr);
    grid-auto-rows: minmax(240px, auto);
  }
  .col-quotes {
    height: 240px;
  }
  .chart-panel {
    height: 380px;
  }
  .book-row {
    height: 240px;
  }
  .bottom-row {
    height: 320px;
  }
}
</style>
