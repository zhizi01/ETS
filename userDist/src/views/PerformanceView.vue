<script setup>
import { computed, ref, watch } from 'vue'
import { Crown, RefreshCw, Trophy } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import NumFlash from '@/components/common/NumFlash.vue'
import EChart from '@/components/EChart.vue'
import { CHART_COLORS, baseAxis, baseTooltip } from '@/utils/echarts'
import { pubApi } from '@/api/pub'
import { assetApi } from '@/api/user'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { usePolling } from '@/composables/usePolling'
import { MEMBER_TYPE } from '@/utils/enums'
import { amount, datetime, money, price, qty, signed } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

const rank = ref([])
const portfolio = ref(null)
const loading = ref(false)
const memberFilter = ref(undefined)
const hideRobots = ref(false)

const accountId = computed(() => acctStore.currentId)

async function load() {
  if (!simStore.currentSimId) return
  loading.value = true
  try {
    const [r, p] = await Promise.allSettled([
      pubApi.rank(simStore.currentSimId, 100),
      accountId.value ? assetApi.portfolio(accountId.value) : Promise.resolve(null),
    ])
    rank.value = r.status === 'fulfilled' ? r.value || [] : []
    portfolio.value = p.status === 'fulfilled' ? p.value : null
  } finally {
    loading.value = false
  }
}

watch([() => simStore.currentSimId, accountId], load, { immediate: true })
usePolling(load, 15_000, { immediate: false })

const rankRows = computed(() =>
  rank.value.filter(
    (r) => (!memberFilter.value || r.memberType === memberFilter.value) && (!hideRobots.value || r.source !== 'ROBOT'),
  ),
)

const myRank = computed(() => rank.value.find((r) => r.accountId === accountId.value) || null)
const top3 = computed(() => rank.value.slice(0, 3))

// ── 盈亏曲线 ───────────────────────────────────────
const pnlOption = computed(() => {
  const pts = portfolio.value?.pnlCurve || []
  return {
    animation: false,
    grid: { left: 58, right: 16, top: 18, bottom: 26 },
    tooltip: {
      ...baseTooltip,
      formatter: (ps) => {
        const i = ps[0]?.dataIndex ?? 0
        const p = pts[i]
        if (!p) return ''
        return `<div style="font-size:11px;line-height:1.75">
          <div style="color:#8b98a9">${datetime(p.ts)}</div>
          <div>成交价 <b>${price(p.price)}</b>　电量 <b>${qty(p.quantity)}</b></div>
          <div>本笔盈亏 <b style="color:${p.pnl >= 0 ? CHART_COLORS.up : CHART_COLORS.down}">${signed(p.pnl)}</b></div>
          <div>累计盈亏 <b style="color:${p.cumulativePnl >= 0 ? CHART_COLORS.up : CHART_COLORS.down}">${signed(p.cumulativePnl)}</b></div>
        </div>`
      },
    },
    xAxis: {
      ...baseAxis,
      type: 'category',
      data: pts.map((p) => datetime(p.ts).slice(5, 16)),
      splitLine: { show: false },
      axisLabel: { ...baseAxis.axisLabel, hideOverlap: true },
    },
    yAxis: {
      ...baseAxis,
      type: 'value',
      scale: true,
      axisLabel: { ...baseAxis.axisLabel, formatter: (v) => money(v) },
    },
    series: [
      {
        name: '累计已实现盈亏',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: pts.map((p) => p.cumulativePnl),
        lineStyle: { color: CHART_COLORS.gold, width: 2 },
        itemStyle: { color: CHART_COLORS.gold },
        areaStyle: { color: 'rgba(226,179,60,0.10)' },
        markLine: {
          silent: true,
          symbol: 'none',
          data: [{ yAxis: 0 }],
          lineStyle: { color: '#3d4859', type: 'dashed' },
          label: { show: false },
        },
      },
    ],
  }
})

// ── 买卖均价对比 ───────────────────────────────────
const priceOption = computed(() => {
  const list = portfolio.value?.priceCompare || []
  return {
    animation: false,
    grid: { left: 56, right: 16, top: 26, bottom: 30 },
    tooltip: { ...baseTooltip },
    legend: {
      data: ['购电均价', '售电均价', '最新价'],
      top: 0,
      right: 6,
      textStyle: { color: CHART_COLORS.text, fontSize: 10 },
      itemWidth: 12,
      itemHeight: 8,
    },
    xAxis: { ...baseAxis, type: 'category', data: list.map((x) => x.label || x.instrumentKey), splitLine: { show: false } },
    yAxis: { ...baseAxis, type: 'value', scale: true },
    series: [
      { name: '购电均价', type: 'bar', data: list.map((x) => x.avgBuyPrice), itemStyle: { color: 'rgba(245,34,45,0.7)' }, barMaxWidth: 22 },
      { name: '售电均价', type: 'bar', data: list.map((x) => x.avgSellPrice), itemStyle: { color: 'rgba(0,181,120,0.7)' }, barMaxWidth: 22 },
      {
        name: '最新价',
        type: 'line',
        data: list.map((x) => x.lastPrice),
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: CHART_COLORS.accent, width: 1.5, type: 'dashed' },
        itemStyle: { color: CHART_COLORS.accent },
      },
    ],
  }
})

// ── 品种分布 ───────────────────────────────────────
const PIE_COLORS = ['#2f7fff', '#e2b33c', '#00b578', '#9254de', '#13c2c2', '#f5222d', '#f5a524']

const distOption = computed(() => {
  const list = (portfolio.value?.varietyDist || []).filter((x) => Math.abs(x.amount) > 0)
  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(22,27,34,0.96)',
      borderColor: CHART_COLORS.line,
      textStyle: { color: CHART_COLORS.textStrong, fontSize: 11 },
      formatter: (p) => `${p.name}<br/>成交额 <b>${amount(p.value)}</b> 元（${p.percent}%）`,
    },
    legend: {
      orient: 'vertical',
      right: 6,
      top: 'center',
      textStyle: { color: CHART_COLORS.text, fontSize: 10 },
      itemWidth: 10,
      itemHeight: 8,
    },
    series: [
      {
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['36%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: CHART_COLORS.panel, borderWidth: 2 },
        label: { show: false },
        data: list.map((x, i) => ({
          name: x.label || x.instrumentKey,
          value: Math.abs(x.amount),
          itemStyle: { color: PIE_COLORS[i % PIE_COLORS.length] },
        })),
      },
    ],
  }
})

const rankColumns = [
  { title: '排名', key: 'rank', width: 62 },
  { title: '市场主体', key: 'name', width: 190 },
  { title: '类型', key: 'memberType', width: 96 },
  { title: '来源', key: 'source', width: 72 },
  { title: '累计盈亏', key: 'totalPnl', width: 140, align: 'right' },
  { title: '已实现', key: 'realizedPnl', width: 130, align: 'right' },
  { title: '浮动', key: 'unrealizedPnl', width: 130, align: 'right' },
  { title: '收益率', key: 'returnPct', width: 96, align: 'right' },
  { title: '成交笔数', key: 'tradeCount', width: 86, align: 'right' },
  { title: '购电量', key: 'buyMwh', width: 110, align: 'right' },
  { title: '售电量', key: 'sellMwh', width: 110, align: 'right' },
]
</script>

<template>
  <div class="perf-view ets-scroll">
    <!-- 我的绩效 -->
    <PanelBox class="mine" :flex="false" title="我的绩效" :sub="acctStore.current ? `${acctStore.current.code} · ${acctStore.current.name}` : '未选择账户'">
      <template #extra>
        <a-button size="small" :loading="loading" @click="load"><RefreshCw :size="12" /></a-button>
      </template>

      <div class="podium">
        <div class="my-rank">
          <Trophy :size="16" class="ti" />
          <div>
            <span class="k">我的排名</span>
            <b class="mono big gold">{{ myRank ? `第 ${myRank.rank} 名` : '未上榜' }}</b>
            <span class="k2">共 {{ rank.length }} 个市场主体参与排名</span>
          </div>
        </div>
        <div class="m"><span>累计盈亏</span><NumFlash :value="portfolio?.totalPnl" type="signedMoney" color-by="self" strong class="v" /></div>
        <div class="m"><span>已实现</span><NumFlash :value="portfolio?.realizedPnl" type="signedMoney" color-by="self" class="v" /></div>
        <div class="m"><span>浮动盈亏</span><NumFlash :value="portfolio?.unrealizedPnl" type="signedMoney" color-by="self" class="v" /></div>
        <div class="m"><span>累计购电</span><NumFlash :value="portfolio?.totalBuyMwh" type="qty" suffix=" MWh" class="v" /></div>
        <div class="m"><span>累计售电</span><NumFlash :value="portfolio?.totalSellMwh" type="qty" suffix=" MWh" class="v" /></div>
        <div class="m"><span>购电金额</span><NumFlash :value="portfolio?.totalBuyAmount" type="money" class="v" /></div>
        <div class="m"><span>售电金额</span><NumFlash :value="portfolio?.totalSellAmount" type="money" class="v" /></div>
      </div>
    </PanelBox>

    <div class="charts">
      <PanelBox class="c1" title="盈亏曲线" sub="按成交逐笔累计">
        <div class="cb">
          <EChart
            :option="pnlOption"
            height="100%"
            :empty="!(portfolio?.pnlCurve || []).length"
            empty-text="尚无成交，盈亏曲线将在首笔成交后生成"
          />
        </div>
      </PanelBox>

      <PanelBox class="c2" title="买卖均价对比" sub="按标的">
        <div class="cb">
          <EChart
            :option="priceOption"
            height="100%"
            :empty="!(portfolio?.priceCompare || []).length"
            empty-text="暂无成交数据"
          />
        </div>
      </PanelBox>

      <PanelBox class="c3" title="品种分布" sub="按成交额">
        <div class="cb">
          <EChart
            :option="distOption"
            height="100%"
            :empty="!(portfolio?.varietyDist || []).some((x) => Math.abs(x.amount) > 0)"
            empty-text="暂无成交数据"
          />
        </div>
      </PanelBox>
    </div>

    <!-- 排行榜 -->
    <PanelBox class="rank-panel" title="市场排行榜" :sub="`按累计盈亏排序 · ${rankRows.length} 条`">
      <template #extra>
        <a-checkbox v-model:checked="hideRobots" class="ck">隐藏机器人</a-checkbox>
        <a-select
          v-model:value="memberFilter"
          size="small"
          style="width: 118px"
          placeholder="全部主体"
          allow-clear
          :options="[
            { value: 'GENCO', label: '发电企业' },
            { value: 'CONSUMER', label: '电力用户' },
            { value: 'RETAILER', label: '售电公司' },
          ]"
        />
      </template>

      <div v-if="top3.length" class="top3">
        <div v-for="(t, i) in top3" :key="t.accountId" class="t3" :class="`p${i + 1}`">
          <Crown :size="14" />
          <span class="no">{{ i + 1 }}</span>
          <div class="ti2">
            <span class="tn">{{ t.name }}</span>
            <span class="mono tc">{{ t.code }} · {{ MEMBER_TYPE[t.memberType] }}</span>
          </div>
          <NumFlash :value="t.totalPnl" type="signedMoney" color-by="self" strong />
        </div>
      </div>

      <div class="tb ets-scroll">
        <a-table
          :columns="rankColumns"
          :data-source="rankRows"
          :pagination="false"
          :loading="loading"
          row-key="accountId"
          size="small"
          :scroll="{ x: 1240 }"
          :row-class-name="(r) => (r.accountId === accountId ? 'me-row' : '')"
        >
          <template #emptyText>
            <EmptyHint text="暂无排行数据" hint="开盘并产生成交后，排行榜按累计盈亏实时更新" />
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'rank'">
              <span class="mono rk" :class="{ gold: record.rank <= 3 }">{{ record.rank }}</span>
            </template>
            <template v-else-if="column.key === 'name'">
              <div class="two">
                <span>{{ record.name }}<a-tag v-if="record.accountId === accountId" color="blue" :bordered="false">我</a-tag></span>
                <span class="mono sub">{{ record.code }}</span>
              </div>
            </template>
            <template v-else-if="column.key === 'memberType'">
              {{ record.memberTypeLabel || MEMBER_TYPE[record.memberType] }}
            </template>
            <template v-else-if="column.key === 'source'">
              <a-tag :bordered="false" :color="record.source === 'ROBOT' ? 'default' : 'blue'">
                {{ record.source === 'ROBOT' ? '机器人' : '真人' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'totalPnl'">
              <span class="mono" :class="record.totalPnl > 0 ? 'up' : record.totalPnl < 0 ? 'down' : ''">
                {{ signed(record.totalPnl) }}
              </span>
            </template>
            <template v-else-if="column.key === 'realizedPnl'">
              <span class="mono" :class="record.realizedPnl > 0 ? 'up' : record.realizedPnl < 0 ? 'down' : ''">
                {{ signed(record.realizedPnl) }}
              </span>
            </template>
            <template v-else-if="column.key === 'unrealizedPnl'">
              <span class="mono" :class="record.unrealizedPnl > 0 ? 'up' : record.unrealizedPnl < 0 ? 'down' : ''">
                {{ signed(record.unrealizedPnl) }}
              </span>
            </template>
            <template v-else-if="column.key === 'returnPct'">
              <span class="mono" :class="record.returnPct > 0 ? 'up' : record.returnPct < 0 ? 'down' : ''">
                {{ ((record.returnPct || 0) * 100).toFixed(2) }}%
              </span>
            </template>
            <template v-else-if="column.key === 'tradeCount'"><span class="mono">{{ record.tradeCount }}</span></template>
            <template v-else-if="column.key === 'buyMwh'"><span class="mono up">{{ qty(record.buyMwh) }}</span></template>
            <template v-else-if="column.key === 'sellMwh'"><span class="mono down">{{ qty(record.sellMwh) }}</span></template>
          </template>
        </a-table>
      </div>
    </PanelBox>
  </div>
</template>

<style scoped>
.perf-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}
.mine {
  flex: none;
}
.podium {
  display: grid;
  grid-template-columns: 1.5fr repeat(7, 1fr);
  gap: 1px;
  background: var(--ets-line);
}
.my-rank {
  display: flex;
  gap: 10px;
  padding: 11px 13px;
  background: linear-gradient(135deg, rgba(226, 179, 60, 0.1), transparent 70%);
}
.ti {
  color: var(--ets-gold);
  margin-top: 2px;
}
.my-rank .k {
  display: block;
  font-size: 11px;
  color: var(--ets-text-faint);
}
.my-rank .k2 {
  display: block;
  font-size: 10px;
  color: #4f5a6b;
}
.big {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.3;
}
.gold {
  color: var(--ets-gold) !important;
}
.m {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 11px 12px;
  background: var(--ets-panel);
}
.m > span {
  font-size: 10.5px;
  color: var(--ets-text-faint);
}
.v {
  font-size: 15px !important;
}

.charts {
  display: grid;
  grid-template-columns: 1.35fr 1fr 0.9fr;
  gap: 8px;
  height: 268px;
  flex: none;
}
.cb {
  flex: 1;
  min-height: 0;
  padding: 4px;
}

.rank-panel {
  flex: 1;
  min-height: 300px;
}
.ck {
  font-size: 11px !important;
  color: var(--ets-text-weak);
}
.top3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 10px;
  border-bottom: 1px solid var(--ets-line);
  flex: none;
}
.t3 {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 11px;
  border-radius: 6px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}
.t3.p1 {
  border-color: rgba(226, 179, 60, 0.45);
  background: linear-gradient(100deg, rgba(226, 179, 60, 0.12), var(--ets-raise) 60%);
  color: var(--ets-gold);
}
.t3.p2 {
  border-color: rgba(180, 190, 205, 0.3);
  color: #b4becd;
}
.t3.p3 {
  border-color: rgba(190, 130, 80, 0.3);
  color: #be8250;
}
.no {
  font-family: var(--ets-mono);
  font-size: 17px;
  font-weight: 800;
}
.ti2 {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.tn {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ets-text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tc {
  font-size: 10px;
  color: var(--ets-text-faint);
}

.tb {
  flex: 1;
  min-height: 0;
}
.tb :deep(.me-row) > td {
  background: rgba(47, 127, 255, 0.09) !important;
}
.rk {
  font-weight: 600;
}
.two {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.two .sub {
  font-size: 10px;
  color: var(--ets-text-faint);
}

@media (max-width: 1400px) {
  .podium {
    grid-template-columns: repeat(4, 1fr);
  }
  .charts {
    grid-template-columns: 1fr 1fr;
    height: auto;
    grid-auto-rows: 250px;
  }
}
</style>
