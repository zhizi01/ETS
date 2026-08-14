<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { Gauge, RefreshCw, Send, Wand2 } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import StateTag from '@/components/common/StateTag.vue'
import EChart from '@/components/EChart.vue'
import { CHART_COLORS, baseAxis, baseTooltip } from '@/utils/echarts'
import { spotApi } from '@/api/user'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { usePolling } from '@/composables/usePolling'
import { amount, price as fp, qty as fq, today } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

/** 国内典型峰平谷划分，与 PeriodType 的价格系数配套 */
const HOUR_PERIOD = [
  'VALLEY', 'VALLEY', 'VALLEY', 'VALLEY', 'VALLEY', 'VALLEY', 'VALLEY', 'VALLEY',
  'FLAT', 'FLAT', 'PEAK', 'PEAK', 'FLAT', 'FLAT', 'FLAT', 'FLAT',
  'FLAT', 'PEAK', 'PEAK', 'SHARP', 'SHARP', 'PEAK', 'FLAT', 'FLAT',
]
const PERIOD_FACTOR = { VALLEY: 0.62, FLAT: 1.0, PEAK: 1.35, SHARP: 1.65 }
const PERIOD_LABEL = { VALLEY: '谷', FLAT: '平', PEAK: '峰', SHARP: '尖' }
const PERIOD_COLOR = { VALLEY: '#00b578', FLAT: '#8b98a9', PEAK: '#f5a524', SHARP: '#f5222d' }

/** 典型日负荷曲线（相对峰值的比例），用于一键填量 */
const LOAD_SHAPE = {
  CONTINUOUS: Array(24).fill(0.95),
  TWO_SHIFT: [0.4, 0.38, 0.36, 0.36, 0.4, 0.5, 0.7, 0.9, 1, 1, 0.98, 0.9, 0.75, 0.9, 1, 1, 0.98, 0.9, 0.8, 0.7, 0.6, 0.5, 0.45, 0.42],
  THREE_SHIFT: [0.8, 0.78, 0.76, 0.76, 0.8, 0.85, 0.92, 0.98, 1, 1, 0.98, 0.95, 0.9, 0.95, 1, 1, 0.98, 0.95, 0.9, 0.88, 0.85, 0.84, 0.82, 0.8],
  COMMERCIAL: [0.3, 0.28, 0.26, 0.26, 0.28, 0.35, 0.5, 0.7, 0.9, 1, 1, 0.98, 0.9, 0.95, 1, 1, 0.98, 0.9, 0.85, 0.8, 0.7, 0.55, 0.42, 0.34],
  RESIDENTIAL: [0.45, 0.4, 0.36, 0.34, 0.34, 0.38, 0.5, 0.6, 0.62, 0.6, 0.58, 0.6, 0.65, 0.6, 0.58, 0.6, 0.7, 0.85, 1, 1, 0.95, 0.85, 0.7, 0.55],
}

const sessionId = ref('')
const side = ref('BUY')
const tradeDate = ref(today())
const segments = ref(Array.from({ length: 24 }, (_, h) => ({ hour: h, price: null, quantity: null })))
const myBid = ref(null)
const clearing = ref([])
const myResult = ref(null)
const loading = ref(false)
const submitting = ref(false)
const fillOpen = ref(false)

const fillCfg = reactive({ basePrice: 380, totalMwh: 24000, shape: 'CONTINUOUS' })

const accountId = computed(() => acctStore.currentId)
const spotSessions = computed(() => simStore.sessionsOfMode('SPOT_CLEARING'))
const session = computed(() => simStore.sessionOf(sessionId.value))
const editable = computed(() => simStore.tradable && ['PENDING', 'PRE_OPEN', 'OPEN'].includes(session.value?.state))
const cleared = computed(() => clearing.value.some((c) => Number(c.price) > 0 || Number(c.volume) > 0))

const totalQty = computed(() => segments.value.reduce((s, x) => s + (Number(x.quantity) || 0), 0))
const avgPrice = computed(() => {
  let amt = 0
  let q = 0
  for (const s of segments.value) {
    const p = Number(s.price) || 0
    const v = Number(s.quantity) || 0
    amt += p * v
    q += v
  }
  return q > 0 ? amt / q : 0
})
const totalAmount = computed(() => avgPrice.value * totalQty.value)

function pickSession() {
  if (sessionId.value && spotSessions.value.some((s) => s.id === sessionId.value)) return
  const live = spotSessions.value.find((s) => ['PRE_OPEN', 'OPEN'].includes(s.state))
  sessionId.value = (live || spotSessions.value[0])?.id || ''
}

async function load() {
  if (!sessionId.value) {
    myBid.value = null
    clearing.value = []
    myResult.value = null
    return
  }
  loading.value = true
  try {
    const tasks = [spotApi.clearing(sessionId.value)]
    tasks.push(accountId.value ? spotApi.myResult(accountId.value, sessionId.value) : Promise.resolve(null))
    const [c, r] = await Promise.allSettled(tasks)
    clearing.value = c.status === 'fulfilled' ? c.value || [] : []
    myResult.value = r.status === 'fulfilled' ? r.value : null

    if (myResult.value?.bidId) {
      myBid.value = myResult.value
      side.value = myResult.value.side || side.value
      tradeDate.value = myResult.value.tradeDate || tradeDate.value
      // 已申报过就把曲线回填到编辑器，方便微调后覆盖提交
      const map = new Map((myResult.value.segments || []).map((s) => [s.hour, s]))
      segments.value = Array.from({ length: 24 }, (_, h) => ({
        hour: h,
        price: map.get(h)?.price ?? null,
        quantity: map.get(h)?.quantity ?? null,
      }))
    } else {
      myBid.value = null
    }
  } finally {
    loading.value = false
  }
}

watch(() => spotSessions.value.length, pickSession, { immediate: true })
watch([sessionId, accountId], load, { immediate: true })
usePolling(load, 15_000, { immediate: false })

// ── 批量填充 ───────────────────────────────────────
function openFill() {
  fillCfg.basePrice = session.value?.lastPrice || session.value?.prevClose || simStore.cfg.benchmarkPrice || 380
  const p = acctStore.current?.params
  if (acctStore.current?.memberType === 'GENCO') fillCfg.totalMwh = Math.round((p?.annualEnergyMwh || 0) / 365) || 24000
  else if (acctStore.current?.memberType === 'CONSUMER') fillCfg.totalMwh = Math.round((p?.annualDemandMwh || 0) / 365) || 24000
  else fillCfg.totalMwh = Math.round((p?.agentDemandMwh || 0) / 365) || 24000
  fillCfg.shape = p?.loadProfile || 'CONTINUOUS'
  fillOpen.value = true
}

function applyPriceTemplate() {
  const base = Number(fillCfg.basePrice) || 380
  segments.value = segments.value.map((s) => ({
    ...s,
    price: round2(base * PERIOD_FACTOR[HOUR_PERIOD[s.hour]]),
  }))
  message.success('已按峰平谷模板填入 24 点申报价')
}

function applyQtyTemplate() {
  const shape = LOAD_SHAPE[fillCfg.shape] || LOAD_SHAPE.CONTINUOUS
  const sum = shape.reduce((a, b) => a + b, 0)
  const total = Number(fillCfg.totalMwh) || 0
  segments.value = segments.value.map((s) => ({
    ...s,
    quantity: round3((total * shape[s.hour]) / sum),
  }))
  message.success('已按负荷曲线分配 24 点申报电量')
}

function applyBoth() {
  applyPriceTemplate()
  applyQtyTemplate()
  fillOpen.value = false
}

function clearAll() {
  Modal.confirm({
    title: '清空曲线',
    content: '将清空 24 点全部价格与电量，确认？',
    okText: '清空',
    cancelText: '取消',
    onOk: () => {
      segments.value = segments.value.map((s) => ({ ...s, price: null, quantity: null }))
    },
  })
}

function round2(v) {
  return Math.round(v * 100) / 100
}
function round3(v) {
  return Math.round(v * 1000) / 1000
}

// ── 提交 ───────────────────────────────────────────
const invalidHours = computed(() =>
  segments.value.filter((s) => s.price === null || s.price === undefined || s.price === '').map((s) => s.hour),
)

async function submit() {
  if (!accountId.value) {
    message.warning('请先选择交易账户')
    return
  }
  if (!sessionId.value) {
    message.warning('请选择日前现货场次')
    return
  }
  if (invalidHours.value.length) {
    message.warning(`第 ${invalidHours.value.join('、')} 时段缺少申报价，24 段必须全部填写`)
    return
  }
  submitting.value = true
  try {
    await spotApi.submit({
      accountId: accountId.value,
      sessionId: sessionId.value,
      side: side.value,
      tradeDate: tradeDate.value,
      segments: segments.value.map((s) => ({
        hour: s.hour,
        price: Number(s.price) || 0,
        quantity: Number(s.quantity) || 0,
      })),
    })
    message.success('24 点报价曲线已提交，同场次重复提交将覆盖原申报')
    load()
    acctStore.refreshCurrent()
  } catch {
    // 限价越界等原因由后端返回
  } finally {
    submitting.value = false
  }
}

async function cancelBid() {
  if (!myBid.value?.bidId) return
  await spotApi.cancel(myBid.value.bidId)
  message.success('已撤销本场次申报')
  load()
}

// ── 图表 ───────────────────────────────────────────
const hours = Array.from({ length: 24 }, (_, h) => `${String(h).padStart(2, '0')}:00`)

const editorOption = computed(() => ({
  animation: false,
  grid: { left: 46, right: 46, top: 24, bottom: 24 },
  tooltip: { ...baseTooltip },
  legend: {
    data: ['申报价', '申报电量'],
    right: 8,
    top: 0,
    textStyle: { color: CHART_COLORS.text, fontSize: 10 },
    itemWidth: 12,
    itemHeight: 8,
  },
  xAxis: { ...baseAxis, type: 'category', data: hours, splitLine: { show: false } },
  yAxis: [
    { ...baseAxis, type: 'value', name: '元/MWh', nameTextStyle: { color: CHART_COLORS.text, fontSize: 10 }, scale: true },
    {
      ...baseAxis,
      type: 'value',
      name: 'MWh',
      nameTextStyle: { color: CHART_COLORS.text, fontSize: 10 },
      splitLine: { show: false },
    },
  ],
  series: [
    {
      name: '申报电量',
      type: 'bar',
      yAxisIndex: 1,
      data: segments.value.map((s) => Number(s.quantity) || 0),
      itemStyle: { color: 'rgba(47,127,255,0.35)' },
      barMaxWidth: 18,
    },
    {
      name: '申报价',
      type: 'line',
      smooth: false,
      symbol: 'circle',
      symbolSize: 5,
      data: segments.value.map((s) => (s.price === null || s.price === '' ? null : Number(s.price))),
      lineStyle: { color: CHART_COLORS.gold, width: 2 },
      itemStyle: { color: CHART_COLORS.gold },
      connectNulls: false,
    },
  ],
}))

const resultOption = computed(() => {
  const clearPrices = Array.from({ length: 24 }, (_, h) => {
    const row = clearing.value.find((c) => Number(c.hour) === h)
    return row ? Number(row.price) || 0 : 0
  })
  const clearVolumes = Array.from({ length: 24 }, (_, h) => {
    const row = clearing.value.find((c) => Number(c.hour) === h)
    return row ? Number(row.volume) || 0 : 0
  })
  const mine = Array.from({ length: 24 }, (_, h) => {
    const s = (myResult.value?.segments || []).find((x) => x.hour === h)
    return s ? Number(s.clearedQty) || 0 : 0
  })

  return {
    animation: false,
    grid: { left: 46, right: 46, top: 24, bottom: 24 },
    tooltip: { ...baseTooltip },
    legend: {
      data: ['出清价', '市场出清量', '我的中标电量'],
      right: 8,
      top: 0,
      textStyle: { color: CHART_COLORS.text, fontSize: 10 },
      itemWidth: 12,
      itemHeight: 8,
    },
    xAxis: { ...baseAxis, type: 'category', data: hours, splitLine: { show: false } },
    yAxis: [
      { ...baseAxis, type: 'value', name: '元/MWh', nameTextStyle: { color: CHART_COLORS.text, fontSize: 10 }, scale: true },
      { ...baseAxis, type: 'value', name: 'MWh', nameTextStyle: { color: CHART_COLORS.text, fontSize: 10 }, splitLine: { show: false } },
    ],
    series: [
      {
        name: '市场出清量',
        type: 'bar',
        yAxisIndex: 1,
        data: clearVolumes,
        itemStyle: { color: 'rgba(139,152,169,0.22)' },
        barMaxWidth: 20,
      },
      {
        name: '我的中标电量',
        type: 'bar',
        yAxisIndex: 1,
        data: mine,
        itemStyle: { color: 'rgba(47,127,255,0.7)' },
        barMaxWidth: 12,
      },
      {
        name: '出清价',
        type: 'line',
        symbol: 'circle',
        symbolSize: 5,
        data: clearPrices,
        lineStyle: { color: CHART_COLORS.up, width: 2 },
        itemStyle: { color: CHART_COLORS.up },
      },
    ],
  }
})

const columns = [
  { title: '时段', key: 'hour', width: 78 },
  { title: '峰谷', key: 'period', width: 54, align: 'center' },
  { title: '申报价 (元/MWh)', key: 'price', width: 130 },
  { title: '申报电量 (MWh)', key: 'quantity', width: 130 },
  { title: '出清价', key: 'clearPrice', width: 88, align: 'right' },
  { title: '中标电量', key: 'clearedQty', width: 96, align: 'right' },
]

function clearingOf(h) {
  return clearing.value.find((c) => Number(c.hour) === h)
}
function myClearedOf(h) {
  return (myResult.value?.segments || []).find((x) => x.hour === h)
}
</script>

<template>
  <div class="spot-view ets-scroll">
    <PanelBox class="head-panel" :flex="false" title="日前现货申报" :sub="session ? session.name : ''">
      <template #extra>
        <a-button size="small" :loading="loading" @click="load"><RefreshCw :size="12" /></a-button>
      </template>

      <div class="ctrl">
        <div class="c-item">
          <span class="ets-label">交易场次</span>
          <a-select
            v-model:value="sessionId"
            size="small"
            style="width: 250px"
            :options="spotSessions.map((s) => ({ value: s.id, label: `${s.name}（${s.stateLabel}）` }))"
            placeholder="暂无日前现货场次"
            :disabled="!spotSessions.length"
          />
        </div>
        <StateTag v-if="session" kind="session" :value="session.state" :label="session.stateLabel" />
        <div class="c-item">
          <span class="ets-label">申报方向</span>
          <a-radio-group v-model:value="side" size="small" button-style="solid" :disabled="!editable">
            <a-radio-button value="BUY">购电（需求侧）</a-radio-button>
            <a-radio-button value="SELL">售电（供给侧）</a-radio-button>
          </a-radio-group>
        </div>
        <div class="c-item">
          <span class="ets-label">交易日</span>
          <a-input v-model:value="tradeDate" size="small" style="width: 118px" placeholder="yyyy-MM-dd" :disabled="!editable" />
        </div>

        <div class="c-ops">
          <a-button size="small" :disabled="!editable" @click="openFill">
            <template #icon><Wand2 :size="12" /></template>批量填充
          </a-button>
          <a-button size="small" :disabled="!editable" @click="clearAll">清空</a-button>
          <a-button v-if="myBid?.bidId" size="small" danger :disabled="!editable" @click="cancelBid">撤销申报</a-button>
          <a-button type="primary" size="small" :loading="submitting" :disabled="!editable || !accountId" @click="submit">
            <template #icon><Send :size="12" /></template>提交曲线
          </a-button>
        </div>
      </div>

      <div class="summary">
        <div class="sm"><span>申报总电量</span><b class="mono">{{ fq(totalQty) }}</b><i>MWh</i></div>
        <div class="sm"><span>加权均价</span><b class="mono">{{ fp(avgPrice) }}</b><i>元/MWh</i></div>
        <div class="sm"><span>申报总金额</span><b class="mono">{{ amount(totalAmount) }}</b><i>元</i></div>
        <div class="sm">
          <span>申报状态</span>
          <b>
            <StateTag v-if="myBid?.status" kind="spot" :value="myBid.status" :label="myBid.statusLabel" />
            <span v-else class="dim">未申报</span>
          </b>
        </div>
        <div class="sm"><span>已中标电量</span><b class="mono up">{{ fq(myResult?.clearedQty || 0) }}</b><i>MWh</i></div>
        <div class="sm"><span>中标均价</span><b class="mono">{{ fp(myResult?.avgClearedPrice || 0) }}</b><i>元/MWh</i></div>
      </div>

      <div v-if="session && !editable" class="warn-bar">
        {{ !simStore.tradable
          ? `模拟盘当前为「${simStore.sim?.stateLabel || simStore.simState}」，暂不接受现货申报`
          : `场次当前为「${session.stateLabel}」，已停止接受申报，以下为只读视图。` }}
      </div>
    </PanelBox>

    <div v-if="!spotSessions.length" class="ets-panel empty-panel">
      <EmptyHint
        text="当前模拟盘没有日前现货场次"
        hint="日前现货按 24 个小时段独立出清，需要管理端先创建 SPOT_CLEARING 类型的交易场次"
      >
        <template #icon><Gauge :size="26" :stroke-width="1.4" /></template>
      </EmptyHint>
    </div>

    <template v-else>
      <div class="mid-row">
        <PanelBox class="p-table" title="24 点报价曲线" sub="可逐点微调">
          <div class="tb ets-scroll">
            <a-table
              :columns="columns"
              :data-source="segments"
              :pagination="false"
              row-key="hour"
              size="small"
              :scroll="{ y: 340 }"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'hour'">
                  <span class="mono">{{ String(record.hour).padStart(2, '0') }}:00</span>
                </template>
                <template v-else-if="column.key === 'period'">
                  <span class="pd" :style="{ color: PERIOD_COLOR[HOUR_PERIOD[record.hour]] }">
                    {{ PERIOD_LABEL[HOUR_PERIOD[record.hour]] }}
                  </span>
                </template>
                <template v-else-if="column.key === 'price'">
                  <a-input-number
                    v-model:value="record.price"
                    size="small"
                    :min="0"
                    :precision="2"
                    :disabled="!editable"
                    :status="record.price === null ? 'warning' : ''"
                    style="width: 100%"
                  />
                </template>
                <template v-else-if="column.key === 'quantity'">
                  <a-input-number
                    v-model:value="record.quantity"
                    size="small"
                    :min="0"
                    :precision="3"
                    :disabled="!editable"
                    style="width: 100%"
                  />
                </template>
                <template v-else-if="column.key === 'clearPrice'">
                  <span class="mono" :class="clearingOf(record.hour)?.price ? 'up' : 'dim'">
                    {{ clearingOf(record.hour)?.price ? fp(clearingOf(record.hour).price) : '—' }}
                  </span>
                </template>
                <template v-else-if="column.key === 'clearedQty'">
                  <span class="mono" :class="myClearedOf(record.hour)?.clearedQty ? '' : 'dim'">
                    {{ myClearedOf(record.hour)?.clearedQty ? fq(myClearedOf(record.hour).clearedQty) : '—' }}
                  </span>
                </template>
              </template>
            </a-table>
          </div>
        </PanelBox>

        <PanelBox class="p-chart" title="申报曲线" sub="价格与电量联动">
          <div class="ch">
            <EChart :option="editorOption" height="100%" />
          </div>
        </PanelBox>
      </div>

      <PanelBox class="p-result" :flex="false" title="出清结果" :sub="cleared ? '已出清' : '待出清'">
        <div class="ch res">
          <EChart
            :option="resultOption"
            height="100%"
            :empty="!cleared"
            empty-text="本场次尚未出清。集中出清后将展示 24 点统一出清价与我的中标电量对比"
          />
        </div>
        <div v-if="cleared" class="res-foot">
          <span>中标电量 <b class="mono up">{{ fq(myResult?.clearedQty || 0) }}</b> MWh</span>
          <span>结算金额 <b class="mono">{{ amount(myResult?.clearedAmount || 0) }}</b> 元</span>
          <span>中标均价 <b class="mono">{{ fp(myResult?.avgClearedPrice || 0) }}</b> 元/MWh</span>
          <span class="dim">出清规则：供给曲线升序、需求曲线降序，逐小时求交点，同一小时全部成交按统一出清价结算</span>
        </div>
      </PanelBox>
    </template>

    <!-- 批量填充 -->
    <a-modal v-model:open="fillOpen" title="批量填充 24 点曲线" :width="480" ok-text="价量一起填" cancel-text="关闭" @ok="applyBoth">
      <a-form layout="vertical">
        <a-divider orientation="left" orientation-margin="0"><span class="dv">按峰平谷模板填价</span></a-divider>
        <a-form-item label="基准电价 (元/MWh)">
          <a-input-number v-model:value="fillCfg.basePrice" :min="0" :precision="2" />
          <div class="hint">
            谷段 ×0.62、平段 ×1.00、峰段 ×1.35、尖峰 ×1.65（尖峰 19-20 时，峰段 10-11/17-18/21 时，谷段 0-7 时）
          </div>
        </a-form-item>
        <a-button size="small" block @click="applyPriceTemplate">仅填价格</a-button>

        <a-divider orientation="left" orientation-margin="0" style="margin-top: 18px">
          <span class="dv">按负荷曲线填量</span>
        </a-divider>
        <div class="grid2">
          <a-form-item label="日总电量 (MWh)">
            <a-input-number v-model:value="fillCfg.totalMwh" :min="0" :precision="3" />
          </a-form-item>
          <a-form-item label="负荷特性">
            <a-select
              v-model:value="fillCfg.shape"
              :options="[
                { value: 'CONTINUOUS', label: '连续生产' },
                { value: 'TWO_SHIFT', label: '两班制' },
                { value: 'THREE_SHIFT', label: '三班制' },
                { value: 'COMMERCIAL', label: '商业' },
                { value: 'RESIDENTIAL', label: '居民' },
              ]"
            />
          </a-form-item>
        </div>
        <a-button size="small" block @click="applyQtyTemplate">仅填电量</a-button>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.spot-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}
.head-panel {
  flex: none;
}
.ctrl {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 9px 11px;
}
.c-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.c-ops {
  display: flex;
  gap: 6px;
  margin-left: auto;
}

.summary {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  padding: 9px 11px;
  border-top: 1px solid var(--ets-line);
  background: var(--ets-raise);
}
.sm {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.sm > span {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.sm b {
  font-size: 15px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.sm i {
  font-style: normal;
  font-size: 10px;
  color: var(--ets-text-faint);
}

.warn-bar {
  padding: 6px 11px;
  border-top: 1px solid var(--ets-line);
  background: rgba(245, 165, 36, 0.07);
  color: var(--ets-warn);
  font-size: 11.5px;
}

.empty-panel {
  padding: 26px;
}

.mid-row {
  display: grid;
  grid-template-columns: 470px minmax(0, 1fr);
  gap: 8px;
  min-height: 400px;
}
.p-table,
.p-chart {
  min-height: 0;
}
.tb {
  flex: 1;
  min-height: 0;
}
.ch {
  flex: 1;
  min-height: 260px;
  padding: 6px;
}
.ch.res {
  min-height: 250px;
}

.p-result {
  min-height: 300px;
}
.res-foot {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  padding: 8px 11px;
  border-top: 1px solid var(--ets-line);
  font-size: 11.5px;
  color: var(--ets-text-weak);
}
.res-foot b {
  color: var(--ets-text-strong);
}

.pd {
  font-weight: 700;
  font-size: 12px;
}
.dim {
  color: var(--ets-text-faint);
}
.hint {
  font-size: 11px;
  color: var(--ets-text-faint);
  line-height: 1.65;
  margin-top: 3px;
}
.dv {
  font-size: 12px;
  color: var(--ets-text-weak);
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

@media (max-width: 1280px) {
  .mid-row {
    grid-template-columns: minmax(0, 1fr);
  }
  .summary {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
