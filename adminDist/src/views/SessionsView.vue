<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Layers, Pencil, Plus, RefreshCw, Sparkles, Trash2 } from 'lucide-vue-next'
import PanelCard from '../components/PanelCard.vue'
import SessionFormModal from '../components/session/SessionFormModal.vue'
import { sessionApi } from '../api'
import { useSimStore } from '../stores/sim'
import { askConfirm, dangerConfirm } from '../composables/useDanger'
import { TRADE_MODE, TSESSION_STATE, TSESSION_STATE_COLOR, VARIETY } from '../utils/enums'
import { datetime, duration, money, mwh, price } from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)

const loading = ref(false)
const rows = ref([])
const acting = ref('')
const formOpen = ref(false)
const editing = ref(null)
const batchOpen = ref(false)
const batchReplace = ref(false)
const batchLoading = ref(false)
const nowTs = ref(Date.now())
let tick = null

const filters = reactive({ variety: null, tradeMode: null, state: null, keyword: '' })

const CENTRALIZED = ['CALL_AUCTION', 'SPOT_CLEARING']
const isCentralized = (m) => CENTRALIZED.includes(m)

/** 按后端 TradingSessionService 的状态守卫算可用性，避免点了才报错 */
function can(action, r) {
  const s = r.state
  const c = isCentralized(r.tradeMode)
  switch (action) {
    case 'preOpen':
      return c && s === 'PENDING'
    case 'open':
      return !c && (s === 'PENDING' || s === 'SUSPENDED')
    case 'suspend':
      return s === 'OPEN'
    case 'clear':
      return c && ['PRE_OPEN', 'OPEN', 'SUSPENDED'].includes(s)
    case 'close':
      return !['CLOSED', 'SETTLED'].includes(s)
    case 'edit':
      return !['SETTLED'].includes(s)
    case 'delete':
      return ['PENDING', 'CLOSED', 'SETTLED'].includes(s)
    default:
      return false
  }
}

async function load() {
  if (!simId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const page = await sessionApi.list(simId.value, 1, 500)
    rows.value = page?.list || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

const filtered = computed(() =>
  rows.value.filter((r) => {
    if (filters.variety && r.variety !== filters.variety) return false
    if (filters.tradeMode && r.tradeMode !== filters.tradeMode) return false
    if (filters.state && r.state !== filters.state) return false
    if (filters.keyword) {
      const k = filters.keyword.toLowerCase()
      if (!`${r.name} ${r.id} ${r.instrumentKey}`.toLowerCase().includes(k)) return false
    }
    return true
  }),
)

const stat = computed(() => {
  const all = rows.value
  return {
    total: all.length,
    open: all.filter((x) => x.state === 'OPEN').length,
    preOpen: all.filter((x) => x.state === 'PRE_OPEN').length,
    closed: all.filter((x) => ['CLOSED', 'SETTLED'].includes(x.state)).length,
    volume: all.reduce((a, b) => a + (b.totalVolume || 0), 0),
    amount: all.reduce((a, b) => a + (b.totalAmount || 0), 0),
  }
})

function resetFilters() {
  filters.variety = null
  filters.tradeMode = null
  filters.state = null
  filters.keyword = ''
}

// ══════════════ 状态机操作 ══════════════

async function act(action, r) {
  acting.value = r.id + action
  try {
    await sessionApi[action](r.id)
    await load()
  } finally {
    acting.value = ''
  }
}

function doPreOpen(r) {
  act('preOpen', r).then(() => message.success(`${r.name} 已进入集中申报`))
}

function doOpen(r) {
  act('open', r).then(() => message.success(`${r.name} 已开市`))
}

function doSuspend(r) {
  askConfirm({
    title: `临时停牌「${r.name}」？`,
    content: '停牌期间拒绝新申报，已有委托保留在订单簿中，可重新开市恢复交易。',
    okText: '停牌',
    success: '场次已停牌',
    onOk: () => act('suspend', r),
  })
}

function doClear(r) {
  dangerConfirm({
    title: `触发「${r.name}」集中出清？`,
    width: 500,
    okText: '执行出清',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '出清是一次性动作，执行后场次立即闭市，不可撤销。'),
      h(
        'div',
        { style: 'color:#9aa4b0;margin-top:6px' },
        r.tradeMode === 'CALL_AUCTION'
          ? '集中竞价：买单价降序、卖单价升序累计求最大成交量对应价格，全部成交以统一出清价结算，边际单按比例分配。'
          : '日前现货：24 个小时段分别求供需曲线交点，得到各小时出清价与出清电量。',
      ),
    ]),
    success: '出清完成，场次已闭市',
    onOk: () => act('clear', r),
  })
}

function doClose(r) {
  dangerConfirm({
    title: `闭市「${r.name}」？`,
    content: '闭市将撤销订单簿内全部挂单并释放冻结资金，最新价写入前收盘。该操作不可撤销。',
    okText: '确认闭市',
    success: '场次已闭市',
    onOk: () => act('close', r),
  })
}

function doDelete(r) {
  dangerConfirm({
    title: `删除场次「${r.name}」？`,
    content: '删除后该场次及其排期将被移除，历史成交统计不再可见。',
    okText: '删除',
    success: '场次已删除',
    onOk: async () => {
      await sessionApi.remove(r.id)
      await load()
    },
  })
}

function openCreate() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  editing.value = null
  formOpen.value = true
}

function openEdit(r) {
  editing.value = r
  formOpen.value = true
}

async function runBatch() {
  batchLoading.value = true
  try {
    const created = await sessionApi.batchGenerate(simId.value, 'STANDARD', batchReplace.value)
    message.success(`标准场次集合已生成，共 ${created?.length || 0} 个场次`)
    batchOpen.value = false
    await load()
  } finally {
    batchLoading.value = false
  }
}

// ══════════════ 表格 ══════════════

const columns = [
  { title: '场次', dataIndex: 'name', width: 210, fixed: 'left' },
  { title: '品种/时段', dataIndex: 'variety', width: 116 },
  { title: '交易方式', dataIndex: 'tradeMode', width: 106 },
  { title: '状态', dataIndex: 'state', width: 128 },
  { title: '交割区间', dataIndex: 'delivery', width: 176 },
  { title: '开市 / 闭市', dataIndex: 'timeline', width: 176 },
  { title: '申报价区间', dataIndex: 'priceBand', width: 132, align: 'right' },
  { title: '最新价', dataIndex: 'lastPrice', width: 96, align: 'right' },
  { title: '成交量', dataIndex: 'totalVolume', width: 108, align: 'right' },
  { title: '成交额', dataIndex: 'totalAmount', width: 110, align: 'right' },
  { title: '操作', dataIndex: 'ops', width: 268, fixed: 'right' },
]

onMounted(() => {
  simStore.load().then(load)
  tick = setInterval(() => (nowTs.value = Date.now()), 1000)
})

onBeforeUnmount(() => tick && clearInterval(tick))

watch(simId, load)

/** SessVO.countdownMs 是取数时刻的快照，这里用 serverTime 差值做本地推进 */
function liveCountdown(r) {
  if (!r.countdownMs) return 0
  const drift = nowTs.value - (r.serverTime || nowTs.value)
  return Math.max(0, r.countdownMs - drift)
}
</script>

<template>
  <div class="page">
    <PanelCard title="交易场次排期">
      <template #extra>
        <a-button size="small" @click="load">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
        <a-button size="small" :disabled="!simId" @click="batchOpen = true">
          <Sparkles :size="13" style="vertical-align: -2px" /> 一键生成标准场次集合
        </a-button>
        <a-button type="primary" size="small" :disabled="!simId" @click="openCreate">
          <Plus :size="13" style="vertical-align: -2px" /> 新建场次
        </a-button>
      </template>

      <div class="toolbar">
        <div class="stat-strip">
          <span><i class="dot d-all"></i>共 <b class="num">{{ stat.total }}</b> 场</span>
          <span><i class="dot d-open"></i>交易中 <b class="num">{{ stat.open }}</b></span>
          <span><i class="dot d-pre"></i>集中申报 <b class="num">{{ stat.preOpen }}</b></span>
          <span><i class="dot d-closed"></i>已闭市 <b class="num">{{ stat.closed }}</b></span>
          <span class="text-sub">累计成交 <b class="num">{{ mwh(stat.volume) }}</b> / <b class="num">{{ money(stat.amount) }}</b></span>
        </div>

        <div class="filters">
          <a-select
            v-model:value="filters.variety"
            size="small"
            style="width: 128px"
            placeholder="全部品种"
            allow-clear
            :options="VARIETY"
          />
          <a-select
            v-model:value="filters.tradeMode"
            size="small"
            style="width: 148px"
            placeholder="全部交易方式"
            allow-clear
            :options="TRADE_MODE"
          />
          <a-select
            v-model:value="filters.state"
            size="small"
            style="width: 120px"
            placeholder="全部状态"
            allow-clear
            :options="TSESSION_STATE"
          />
          <a-input
            v-model:value="filters.keyword"
            size="small"
            style="width: 160px"
            placeholder="搜索场次名/编号"
            allow-clear
          />
          <a-button size="small" type="text" @click="resetFilters">重置</a-button>
        </div>
      </div>
    </PanelCard>

    <PanelCard no-pad>
      <a-table
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        :pagination="{ pageSize: 20, size: 'small', showSizeChanger: true, showTotal: (t) => `共 ${t} 个场次` }"
        size="small"
        row-key="id"
        :scroll="{ x: 1720 }"
      >
        <template #emptyText>
          <a-empty
            :description="
              !simId
                ? '请先在顶栏选择演练场'
                : rows.length
                  ? '当前筛选条件下没有场次'
                  : '该演练场还没有排期场次，可点「一键生成标准场次集合」'
            "
          />
        </template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'name'">
            <div class="c-name">{{ record.name }}</div>
            <div class="c-sub num">{{ record.id }} · {{ record.instrumentKey }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'variety'">
            <a-tag>{{ record.varietyLabel }}</a-tag>
            <span class="text-sub" style="font-size: 11.5px">{{ record.periodLabel }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'tradeMode'">
            <a-tag :color="isCentralized(record.tradeMode) ? 'purple' : 'default'">
              {{ record.tradeModeLabel }}
            </a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'state'">
            <a-tag :color="TSESSION_STATE_COLOR[record.state]">{{ record.stateLabel }}</a-tag>
            <div v-if="liveCountdown(record) > 0" class="c-sub num">
              {{ record.state === 'PENDING' ? '距开市' : '距闭市' }} {{ duration(liveCountdown(record)) }}
            </div>
          </template>

          <template v-else-if="column.dataIndex === 'delivery'">
            <span class="num text-sub">{{ record.deliveryStart || '—' }} ~ {{ record.deliveryEnd || '—' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'timeline'">
            <div v-if="record.preOpenTime" class="c-sub num">申报 {{ datetime(record.preOpenTime).slice(5, 16) }}</div>
            <div class="num text-sub">{{ datetime(record.openTime).slice(5, 16) }}</div>
            <div class="c-sub num">至 {{ datetime(record.closeTime).slice(5, 16) }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'priceBand'">
            <span class="num text-sub">{{ price(record.priceFloor) }} ~ {{ price(record.priceCap) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'lastPrice'">
            <div
              class="num"
              :class="record.lastPrice > record.prevClose ? 'up' : record.lastPrice < record.prevClose && record.lastPrice > 0 ? 'down' : ''"
            >
              {{ price(record.lastPrice) }}
            </div>
            <div v-if="record.clearPrice > 0" class="c-sub num">出清 {{ price(record.clearPrice) }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'totalVolume'">
            <span class="num">{{ record.totalVolume ? mwh(record.totalVolume) : '—' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'totalAmount'">
            <span class="num">{{ record.totalAmount ? money(record.totalAmount) : '—' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'ops'">
            <a-space :size="1" wrap>
              <a-button
                v-if="can('preOpen', record)"
                type="link"
                size="small"
                :loading="acting === record.id + 'preOpen'"
                @click="doPreOpen(record)"
              >集中申报</a-button>
              <a-button
                v-if="can('open', record)"
                type="link"
                size="small"
                :loading="acting === record.id + 'open'"
                @click="doOpen(record)"
              >开市</a-button>
              <a-button
                v-if="can('suspend', record)"
                type="link"
                size="small"
                @click="doSuspend(record)"
              >停牌</a-button>
              <a-button
                v-if="can('clear', record)"
                type="link"
                size="small"
                style="color: #a371f7"
                :loading="acting === record.id + 'clear'"
                @click="doClear(record)"
              >触发出清</a-button>
              <a-button
                v-if="can('close', record)"
                type="link"
                size="small"
                danger
                @click="doClose(record)"
              >闭市</a-button>
              <a-divider type="vertical" style="margin: 0 1px" />
              <a-button type="link" size="small" :disabled="!can('edit', record)" @click="openEdit(record)">
                <Pencil :size="12" style="vertical-align: -1px" />
              </a-button>
              <a-button type="link" size="small" danger :disabled="!can('delete', record)" @click="doDelete(record)">
                <Trash2 :size="12" style="vertical-align: -1px" />
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </PanelCard>

    <SessionFormModal v-model:open="formOpen" :record="editing" :sim-id="simId" @saved="load" />

    <!-- 一键生成标准场次集合 -->
    <a-modal
      v-model:open="batchOpen"
      title="一键生成标准场次集合"
      :width="560"
      :confirm-loading="batchLoading"
      ok-text="生成"
      cancel-text="取消"
      centered
      @ok="runBatch"
    >
      <p class="batch-desc">
        预设方案 <b>STANDARD</b> 将一次铺开一套完整市场，共 <b>12 个场次</b>：
      </p>
      <ul class="batch-list">
        <li><Layers :size="12" /> 年度全时段 × 滚动撮合 / 挂牌摘牌 / 双边协商</li>
        <li><Layers :size="12" /> 月度全时段 × 滚动撮合 / 集中竞价 / 挂牌摘牌 / 双边协商</li>
        <li><Layers :size="12" /> 月度峰段、月度谷段 × 滚动撮合（演示峰谷价差）</li>
        <li><Layers :size="12" /> 月内多日全时段 × 滚动撮合 / 挂牌摘牌</li>
        <li><Layers :size="12" /> 日前现货全时段 × 现货集中出清（24 点）</li>
      </ul>

      <a-alert
        v-if="rows.length"
        type="warning"
        show-icon
        style="margin: 12px 0"
        :message="`该演练场已有 ${rows.length} 个场次，重复生成会造成堆积。`"
      />

      <a-checkbox v-model:checked="batchReplace">
        <span :class="batchReplace ? 'text-danger' : ''">先清空该盘已有场次再生成（replaceExisting）</span>
      </a-checkbox>
    </a-modal>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.stat-strip {
  display: flex;
  align-items: center;
  gap: 18px;
  font-size: 12px;
  color: var(--text-sub);
}

.stat-strip b {
  color: var(--text);
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
}
.d-all {
  background: #6e7681;
}
.d-open {
  background: #00b578;
}
.d-pre {
  background: #2f81f7;
}
.d-closed {
  background: #484f58;
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.c-name {
  font-weight: 600;
  font-size: 12.5px;
}

.c-sub {
  font-size: 11px;
  color: var(--text-muted);
}

.batch-desc {
  margin: 0 0 8px;
  color: var(--text-sub);
}

.batch-list {
  margin: 0;
  padding-left: 4px;
  list-style: none;
  font-size: 12.5px;
  color: var(--text-sub);
  line-height: 2;
}

.batch-list li {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
