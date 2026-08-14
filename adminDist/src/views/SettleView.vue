<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Calculator, RefreshCw, Trophy } from 'lucide-vue-next'
import EChart from '../components/EChart.vue'
import PanelCard from '../components/PanelCard.vue'
import KpiCard from '../components/KpiCard.vue'
import { settleApi } from '../api'
import { useSimStore } from '../stores/sim'
import { dangerConfirm } from '../composables/useDanger'
import { MEMBER_TYPE_COLOR, SERIES_COLORS, SOURCE_COLOR } from '../utils/enums'
import { money, moneyParts, monthStr, mwh, mwhParts, num, pct, pnlClass } from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)

const PERIOD_FINAL = 'FINAL'

const state = reactive({
  loading: false,
  running: false,
  period: PERIOD_FINAL,
  page: 1,
  size: 20,
  total: 0,
  list: [],
})

const rank = reactive({ loading: false, list: [] })

/**
 * 结算周期候选：终盘 + 最近 6 个自然月 + 以演练场 baseDate 为起点向后 12 个月。
 * 交割区间常常落在 baseDate 之后（甚至跨年），只按当前月份列会够不着，
 * 因此控件用 AutoComplete，候选只是提示，管理员随时可手输任意 yyyy-MM。
 */
function monthsFrom(startYm, count) {
  const out = []
  const [y, m] = String(startYm).split('-').map(Number)
  if (!y || !m) return out
  for (let i = 0; i < count; i += 1) {
    const d = new Date(y, m - 1 + i, 1)
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  return out
}

const periodOptions = computed(() => {
  const seen = new Set()
  const out = [{ value: PERIOD_FINAL, label: '终盘结算（FINAL）' }]
  const base = simStore.current?.baseDate?.slice(0, 7)
  const candidates = [
    ...(base ? monthsFrom(base, 12) : []),
    ...Array.from({ length: 6 }, (_, i) => monthStr(-i)),
  ]
  candidates.forEach((m) => {
    if (seen.has(m)) return
    seen.add(m)
    out.push({ value: m, label: `${m} 月度结算${m === base ? '（基准月）' : ''}` })
  })
  return out
})

async function loadBills() {
  if (!simId.value) {
    state.list = []
    state.total = 0
    return
  }
  state.loading = true
  try {
    const p = await settleApi.bills(simId.value, state.period, state.page, state.size)
    state.list = p?.list || []
    state.total = p?.total || 0
  } catch {
    state.list = []
    state.total = 0
  } finally {
    state.loading = false
  }
}

async function loadRank() {
  if (!simId.value) {
    rank.list = []
    return
  }
  rank.loading = true
  try {
    rank.list = (await settleApi.rank(simId.value, 50)) || []
  } catch {
    rank.list = []
  } finally {
    rank.loading = false
  }
}

function runSettle() {
  const label = periodOptions.value.find((p) => p.value === state.period)?.label || state.period
  dangerConfirm({
    title: `执行结算：${label}？`,
    width: 520,
    okText: '执行结算',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '结算会覆盖该周期已有的结算单，并重排最终名次。'),
      h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '系统将逐账户核算：'),
      h('ul', { style: 'margin:4px 0 0;padding-left:18px;color:#9aa4b0' }, [
        h('li', '中长期合同电量与电费'),
        h('li', '实际交割电量与偏差电量，按容差外部分计考核费用'),
        h('li', '现货电量与电费'),
        h('li', '手续费、收入、成本、利润与排名'),
      ]),
    ]),
    onOk: async () => {
      state.running = true
      try {
        const bills = await settleApi.run(simId.value, state.period)
        message.success(`结算完成，生成 ${bills?.length || 0} 张结算单`)
        state.page = 1
        await Promise.all([loadBills(), loadRank()])
      } finally {
        state.running = false
      }
    },
  })
}

// ══════════════ 汇总 ══════════════

const summary = computed(() => {
  const l = state.list
  return {
    count: state.total,
    contractQty: l.reduce((a, b) => a + (b.contractQty || 0), 0),
    contractAmount: l.reduce((a, b) => a + (b.contractAmount || 0), 0),
    deviationQty: l.reduce((a, b) => a + Math.abs(b.deviationQty || 0), 0),
    deviationPenalty: l.reduce((a, b) => a + (b.deviationPenalty || 0), 0),
    spotAmount: l.reduce((a, b) => a + (b.spotAmount || 0), 0),
    feeTotal: l.reduce((a, b) => a + (b.feeTotal || 0), 0),
    profit: l.reduce((a, b) => a + (b.profit || 0), 0),
  }
})

const rankChartOption = computed(() => {
  const top = rank.list.slice(0, 15).reverse()
  return {
    grid: { left: 96, right: 40, top: 12, bottom: 24 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps) => {
        const p = ps[0]
        const r = top[p.dataIndex]
        return `${r.code} ${r.name}<br/>总盈亏 ${money(r.totalPnl)}<br/>收益率 ${pct(r.returnPct)}<br/>成交 ${r.tradeCount} 笔`
      },
    },
    xAxis: { type: 'value', axisLabel: { formatter: (v) => num(v / 1e4, 0) + '万' } },
    yAxis: {
      type: 'category',
      data: top.map((r) => `${r.code} ${r.name}`),
      axisLabel: { fontSize: 10, width: 88, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 14,
        data: top.map((r) => ({
          value: r.totalPnl,
          itemStyle: { color: r.totalPnl >= 0 ? '#f5222d' : '#00b578', borderRadius: [0, 3, 3, 0] },
        })),
        label: {
          show: true,
          position: 'right',
          fontSize: 10,
          color: '#8b949e',
          formatter: (p) => num(p.value / 1e4, 1) + '万',
        },
      },
    ],
  }
})

const structureOption = computed(() => {
  const s = summary.value
  const items = [
    { name: '合同电费', value: Math.abs(s.contractAmount) },
    { name: '现货电费', value: Math.abs(s.spotAmount) },
    { name: '偏差考核', value: Math.abs(s.deviationPenalty) },
    { name: '手续费', value: Math.abs(s.feeTotal) },
  ].filter((x) => x.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: (p) => `${p.name}<br/>${money(p.value)}（${p.percent}%）` },
    legend: { bottom: 0, icon: 'circle' },
    series: [
      {
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '43%'],
        itemStyle: { borderColor: '#161b22', borderWidth: 2 },
        label: { show: false },
        data: items.map((x, i) => ({ ...x, itemStyle: { color: SERIES_COLORS[i % SERIES_COLORS.length] } })),
      },
    ],
  }
})

// ══════════════ 表格 ══════════════

const billColumns = [
  { title: '名次', dataIndex: 'rank', width: 60, fixed: 'left' },
  { title: '账户', dataIndex: 'accountCode', width: 92 },
  { title: '主体名称', dataIndex: 'accountName', width: 160 },
  { title: '合同电量', dataIndex: 'contractQty', width: 116, align: 'right' },
  { title: '合同电费', dataIndex: 'contractAmount', width: 122, align: 'right' },
  { title: '实际交割', dataIndex: 'actualQty', width: 116, align: 'right' },
  { title: '偏差电量', dataIndex: 'deviationQty', width: 116, align: 'right' },
  { title: '偏差考核', dataIndex: 'deviationPenalty', width: 118, align: 'right' },
  { title: '现货电量', dataIndex: 'spotQty', width: 112, align: 'right' },
  { title: '现货电费', dataIndex: 'spotAmount', width: 118, align: 'right' },
  { title: '手续费', dataIndex: 'feeTotal', width: 108, align: 'right' },
  { title: '收入', dataIndex: 'revenue', width: 120, align: 'right' },
  { title: '成本', dataIndex: 'cost', width: 120, align: 'right' },
  { title: '利润', dataIndex: 'profit', width: 126, align: 'right', fixed: 'right' },
]

const rankColumns = [
  { title: '名次', dataIndex: 'rank', width: 58 },
  { title: '账户', dataIndex: 'code', width: 84 },
  { title: '主体名称', dataIndex: 'name', ellipsis: true },
  { title: '类型', dataIndex: 'memberType', width: 92 },
  { title: '来源', dataIndex: 'source', width: 76 },
  { title: '已实现', dataIndex: 'realizedPnl', width: 112, align: 'right' },
  { title: '浮动', dataIndex: 'unrealizedPnl', width: 104, align: 'right' },
  { title: '总盈亏', dataIndex: 'totalPnl', width: 118, align: 'right' },
  { title: '收益率', dataIndex: 'returnPct', width: 86, align: 'right' },
  { title: '成交', dataIndex: 'tradeCount', width: 64, align: 'right' },
]

onMounted(async () => {
  await simStore.load()
  await Promise.all([loadBills(), loadRank()])
})

watch(simId, async () => {
  state.page = 1
  await Promise.all([loadBills(), loadRank()])
})
</script>

<template>
  <div class="page">
    <PanelCard title="执行结算">
      <template #extra>
        <a-button size="small" @click="loadBills(); loadRank()">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
      </template>

      <div class="run-bar">
        <div class="rb-field">
          <span class="rb-label">演练场</span>
          <a-select
            :value="simId"
            size="small"
            style="width: 240px"
            :options="simStore.sims.map((s) => ({ value: s.id, label: s.name }))"
            @change="(v) => simStore.setCurrent(v)"
          />
        </div>
        <div class="rb-field">
          <span class="rb-label">结算周期</span>
          <a-auto-complete
            v-model:value="state.period"
            size="small"
            style="width: 216px"
            :options="periodOptions"
            placeholder="FINAL 或 yyyy-MM"
            :filter-option="(input, opt) => String(opt.value).toUpperCase().includes(String(input).toUpperCase())"
            @select="
              () => {
                state.page = 1
                loadBills()
              }
            "
          />
          <a-button size="small" type="text" @click="state.page = 1; loadBills()">查询</a-button>
        </div>
        <a-button type="primary" danger size="small" :loading="state.running" :disabled="!simId" @click="runSettle">
          <Calculator :size="13" style="vertical-align: -2px" /> 执行结算
        </a-button>
        <span class="rb-tip">
          结算按合同电量与实际交割量核算偏差，超出容差
          <b class="num">{{ pct(simStore.current?.cfg?.deviationTolerancePct ?? 0.03) }}</b>
          的部分计入偏差考核费用。
        </span>
      </div>
    </PanelCard>

    <div class="kpi-grid">
      <KpiCard label="结算单数" :value="summary.count" unit="张" :hint="`周期 ${state.period}`" />
      <KpiCard
        label="合同电量"
        :value="mwhParts(summary.contractQty)[0]"
        :unit="mwhParts(summary.contractQty)[1]"
        hint="中长期签约电量合计"
      />
      <KpiCard
        label="合同电费"
        :value="moneyParts(summary.contractAmount)[0]"
        :unit="moneyParts(summary.contractAmount)[1]"
        hint="中长期合同结算金额"
      />
      <KpiCard
        label="偏差电量"
        :value="mwhParts(summary.deviationQty)[0]"
        :unit="mwhParts(summary.deviationQty)[1]"
        tone="warn"
        hint="本页各账户偏差绝对值合计"
      />
      <KpiCard
        label="偏差考核"
        :value="moneyParts(summary.deviationPenalty)[0]"
        :unit="moneyParts(summary.deviationPenalty)[1]"
        tone="up"
        hint="超容差部分的考核费用"
      />
      <KpiCard
        label="手续费"
        :value="moneyParts(summary.feeTotal)[0]"
        :unit="moneyParts(summary.feeTotal)[1]"
        hint="买卖双边手续费合计"
      />
      <KpiCard
        label="现货电费"
        :value="moneyParts(summary.spotAmount)[0]"
        :unit="moneyParts(summary.spotAmount)[1]"
        hint="日前现货成交金额"
      />
      <KpiCard
        label="利润合计"
        :value="moneyParts(summary.profit)[0]"
        :unit="moneyParts(summary.profit)[1]"
        :tone="summary.profit >= 0 ? 'up' : 'down'"
        hint="本页结算单利润汇总"
      />
    </div>

    <PanelCard :title="`结算单 · ${state.period}`" no-pad>
      <a-table
        :columns="billColumns"
        :data-source="state.list"
        :loading="state.loading"
        size="small"
        row-key="id"
        :scroll="{ x: 1620 }"
        :pagination="{
          current: state.page,
          pageSize: state.size,
          total: state.total,
          size: 'small',
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 张结算单`,
        }"
        @change="
          (p) => {
            state.page = p.current
            state.size = p.pageSize
            loadBills()
          }
        "
      >
        <template #emptyText>
          <a-empty
            :description="
              !simId ? '请先选择演练场' : `周期 ${state.period} 还没有结算单，点上方「执行结算」生成`
            "
          />
        </template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'rank'">
            <span class="num">{{ record.rank || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'accountCode'">
            <span class="num">{{ record.accountCode }}</span>
          </template>
          <template v-else-if="['contractQty', 'actualQty', 'spotQty'].includes(column.dataIndex)">
            <span class="num">{{ record[column.dataIndex] ? mwh(record[column.dataIndex]) : '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'deviationQty'">
            <span class="num" :class="Math.abs(record.deviationQty) > 0 ? 'text-warn' : ''">
              {{ record.deviationQty ? mwh(record.deviationQty) : '—' }}
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'deviationPenalty'">
            <span class="num" :class="record.deviationPenalty > 0 ? 'up' : ''">
              {{ record.deviationPenalty ? money(record.deviationPenalty) : '—' }}
            </span>
          </template>
          <template
            v-else-if="['contractAmount', 'spotAmount', 'feeTotal', 'revenue', 'cost'].includes(column.dataIndex)"
          >
            <span class="num">{{ record[column.dataIndex] ? money(record[column.dataIndex]) : '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'profit'">
            <span class="num" :class="pnlClass(record.profit)">{{ num(record.profit, 0) }}</span>
          </template>
        </template>
      </a-table>
    </PanelCard>

    <div class="row-2">
      <PanelCard no-pad>
        <template #title>
          <Trophy :size="13" style="vertical-align: -2px; margin-right: 4px" />排行榜
        </template>
        <template #extra>
          <span class="text-muted" style="font-size: 11px">按 totalPnl 排序 · 取自 ets:sim:{id}:rank</span>
        </template>
        <a-table
          :columns="rankColumns"
          :data-source="rank.list"
          :loading="rank.loading"
          size="small"
          row-key="accountId"
          :scroll="{ y: 380 }"
          :pagination="false"
        >
          <template #emptyText>
            <a-empty description="暂无排行数据，等待首笔成交" />
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'rank'">
              <span class="rank-badge" :class="`rank-${record.rank <= 3 ? record.rank : 'n'}`">{{ record.rank }}</span>
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
            <template v-else-if="['realizedPnl', 'unrealizedPnl', 'totalPnl'].includes(column.dataIndex)">
              <span class="num" :class="pnlClass(record[column.dataIndex])">{{ num(record[column.dataIndex], 0) }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'returnPct'">
              <span class="num" :class="pnlClass(record.returnPct)">{{ pct(record.returnPct) }}</span>
            </template>
          </template>
        </a-table>
      </PanelCard>

      <div class="right-col">
        <PanelCard title="Top15 盈亏对比">
          <EChart v-if="rank.list.length" :option="rankChartOption" :height="330" replace-merge="series" />
          <a-empty v-else description="暂无排行数据" :style="{ padding: '96px 0' }" />
        </PanelCard>

        <PanelCard title="结算金额构成">
          <EChart
            v-if="summary.contractAmount || summary.spotAmount || summary.feeTotal || summary.deviationPenalty"
            :option="structureOption"
            :height="220"
          />
          <a-empty v-else description="尚无结算金额" :style="{ padding: '56px 0' }" />
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.run-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.rb-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rb-label {
  font-size: 12px;
  color: var(--text-muted);
}

.rb-tip {
  font-size: 11.5px;
  color: var(--text-muted);
}

.rb-tip b {
  color: var(--text-sub);
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
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 12px;
}

.right-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

@media (max-width: 1280px) {
  .row-2 {
    grid-template-columns: minmax(0, 1fr);
  }
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

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
</style>
