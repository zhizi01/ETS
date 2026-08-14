<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Cpu, Database, HardDrive, Network, RefreshCw, ScrollText } from 'lucide-vue-next'
import EChart from '../components/EChart.vue'
import KpiCard from '../components/KpiCard.vue'
import PanelCard from '../components/PanelCard.vue'
import { sysApi } from '../api'
import { SIM_STATE_COLOR } from '../utils/enums'
import { datetime, duration, money, mwh, num } from '../utils/format'

const metrics = ref(null)
const loading = ref(false)
const autoRefresh = ref(true)
let timer = null

/** 堆内存与 QPS 的滑动窗口，给运维一个趋势而不是孤立数字 */
const heapSeries = ref([])
const qpsSeries = ref([])
const MAX_POINTS = 60

const audit = reactive({ loading: false, list: [], total: 0, page: 1, size: 20 })

async function loadMetrics() {
  loading.value = true
  try {
    const m = await sysApi.metrics()
    metrics.value = m
    const ts = m?.ts || Date.now()
    heapSeries.value.push([ts, m?.jvm?.heapUsedMb ?? 0])
    qpsSeries.value.push([ts, m?.redis?.opsPerSec ?? 0])
    if (heapSeries.value.length > MAX_POINTS) heapSeries.value.shift()
    if (qpsSeries.value.length > MAX_POINTS) qpsSeries.value.shift()
  } catch {
    metrics.value = null
  } finally {
    loading.value = false
  }
}

async function loadAudit() {
  audit.loading = true
  try {
    const p = await sysApi.audit(audit.page, audit.size)
    audit.list = p?.list || []
    audit.total = p?.total || 0
  } catch {
    audit.list = []
    audit.total = 0
  } finally {
    audit.loading = false
  }
}

function setAuto(v) {
  autoRefresh.value = v
  if (timer) clearInterval(timer)
  timer = null
  if (v) timer = setInterval(loadMetrics, 5000)
}

const jvm = computed(() => metrics.value?.jvm || {})
const redis = computed(() => metrics.value?.redis || {})
const ws = computed(() => metrics.value?.ws || {})
const sims = computed(() => metrics.value?.sims || [])

const totals = computed(() => {
  const s = sims.value
  return {
    robots: s.reduce((a, b) => a + (b.robotCount || 0), 0),
    running: s.reduce((a, b) => a + (b.robotRunning || 0), 0),
    book: s.reduce((a, b) => a + (b.bookOrders || 0), 0),
    accounts: s.reduce((a, b) => a + (b.accountCount || 0), 0),
  }
})

function lineOption(data, name, color, unit) {
  return {
    grid: { left: 46, right: 12, top: 16, bottom: 22 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${num(v, 0)} ${unit}` },
    xAxis: { type: 'time', axisLabel: { formatter: (v) => datetime(v).slice(11, 16) } },
    yAxis: { type: 'value', scale: true, axisLabel: { formatter: (v) => num(v, 0) } },
    series: [
      {
        name,
        type: 'line',
        showSymbol: false,
        smooth: 0.3,
        areaStyle: { opacity: 0.12, color },
        lineStyle: { width: 1.6, color },
        itemStyle: { color },
        data,
      },
    ],
  }
}

const heapOption = computed(() => lineOption(heapSeries.value, '堆内存', '#2f81f7', 'MB'))
const qpsOption = computed(() => lineOption(qpsSeries.value, 'Redis QPS', '#00b578', 'ops/s'))

const simColumns = [
  { title: '演练场', dataIndex: 'simName', width: 220 },
  { title: '状态', dataIndex: 'state', width: 92 },
  { title: '交易账户', dataIndex: 'accountCount', width: 92, align: 'right' },
  { title: '机器人', dataIndex: 'robots', width: 108, align: 'right' },
  { title: '场次', dataIndex: 'sessions', width: 96, align: 'right' },
  { title: '订单簿挂单', dataIndex: 'bookOrders', width: 168, align: 'right' },
  { title: '累计成交量', dataIndex: 'totalVolume', width: 122, align: 'right' },
  { title: '累计成交额', dataIndex: 'totalAmount', width: 122, align: 'right' },
]

const auditColumns = [
  { title: '时间', dataIndex: 'createdAt', width: 152 },
  { title: '操作者', dataIndex: 'actor', width: 158 },
  { title: '动作', dataIndex: 'action', width: 150 },
  { title: '演练场', dataIndex: 'simId', width: 96 },
  { title: '对象', dataIndex: 'target', width: 108 },
  { title: '明细', dataIndex: 'detail', ellipsis: true },
  { title: '来源 IP', dataIndex: 'ip', width: 150 },
]

const ACTOR_COLOR = { ADMIN: 'blue', USER: 'cyan', SYSTEM: 'default' }

onMounted(() => {
  loadMetrics()
  loadAudit()
  setAuto(true)
})

onBeforeUnmount(() => timer && clearInterval(timer))
</script>

<template>
  <div class="page">
    <PanelCard title="运行指标">
      <template #extra>
        <span class="text-muted" style="font-size: 11px">
          采样于 {{ metrics?.ts ? datetime(metrics.ts) : '—' }}
        </span>
        <a-switch :checked="autoRefresh" size="small" @change="setAuto" />
        <span class="text-muted" style="font-size: 11px">5 秒自动刷新</span>
        <a-button size="small" :loading="loading" @click="loadMetrics">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
      </template>

      <div class="kpi-grid">
        <KpiCard
          label="JVM 堆内存"
          :value="`${num(jvm.heapUsedMb, 0)}/${num(jvm.heapMaxMb, 0)}`"
          unit="MB"
          :icon="Cpu"
          :tone="jvm.heapUsedPct > 80 ? 'up' : 'neutral'"
          :hint="`占用 ${num(jvm.heapUsedPct, 2)}% · 非堆 ${num(jvm.nonHeapUsedMb, 0)} MB`"
        />
        <KpiCard
          label="JVM 线程"
          :value="jvm.threadCount ?? '—'"
          unit="个"
          :icon="Cpu"
          :hint="`峰值 ${jvm.peakThreadCount ?? '—'} · 守护 ${jvm.daemonThreadCount ?? '—'} · CPU ${jvm.availableProcessors ?? '—'} 核`"
        />
        <KpiCard
          label="服务运行时长"
          :value="duration(jvm.uptimeMs)"
          :icon="HardDrive"
          :hint="`Java ${jvm.javaVersion || '—'} · 负载 ${jvm.systemLoadAverage > 0 ? num(jvm.systemLoadAverage, 2) : '不可用'}`"
        />
        <KpiCard
          label="Redis"
          :value="redis.connected ? redis.version || '已连接' : '未连接'"
          :icon="Database"
          :tone="redis.connected ? 'neutral' : 'up'"
          :hint="`${redis.mode || '—'} · 内存 ${redis.usedMemoryHuman || '—'} · 客户端 ${redis.connectedClients ?? '—'}`"
        />
        <KpiCard
          label="Redis QPS"
          :value="num(redis.opsPerSec, 0)"
          unit="ops/s"
          :icon="Database"
          :hint="`累计命令 ${num(redis.totalCommandsProcessed, 0)} 次`"
        />
        <KpiCard
          label="db3 键数量"
          :value="num(redis.dbSize, 0)"
          unit="key"
          :icon="Database"
          :hint="`Redis 已运行 ${duration(redis.uptimeSeconds * 1000)}`"
        />
        <KpiCard
          label="WebSocket 在线"
          :value="ws.onlineCount ?? 0"
          unit="连接"
          :icon="Network"
          tone="brand"
          :hint="`主题 ${ws.topicCount ?? 0} 个 · 订阅 ${ws.subscriptionCount ?? 0} 条 · 累计接入 ${ws.totalConnected ?? 0}`"
        />
        <KpiCard
          label="撮合引擎负载"
          :value="num(totals.book, 0)"
          unit="笔挂单"
          :icon="Cpu"
          :hint="`机器人 ${totals.running}/${totals.robots} 运行 · 账户 ${totals.accounts} 户`"
        />
      </div>

      <a-alert
        v-if="redis.error"
        type="error"
        show-icon
        style="margin-top: 12px"
        :message="`Redis 采集异常：${redis.error}`"
      />
    </PanelCard>

    <div class="row-2">
      <PanelCard title="JVM 堆内存趋势（MB）">
        <EChart v-if="heapSeries.length > 1" :option="heapOption" :height="200" />
        <div v-else class="wait">正在采样，稍候将绘出趋势…</div>
      </PanelCard>
      <PanelCard title="Redis QPS 趋势（ops/s）">
        <EChart v-if="qpsSeries.length > 1" :option="qpsOption" :height="200" />
        <div v-else class="wait">正在采样，稍候将绘出趋势…</div>
      </PanelCard>
    </div>

    <PanelCard title="各演练场运行态" no-pad>
      <a-table
        :columns="simColumns"
        :data-source="sims"
        :loading="loading"
        :pagination="false"
        size="small"
        row-key="simId"
        :scroll="{ x: 1040 }"
      >
        <template #emptyText><a-empty description="暂无演练场" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'simName'">
            <div class="c-name">{{ record.simName }}</div>
            <div class="c-sub num">{{ record.simId }}</div>
          </template>
          <template v-else-if="column.dataIndex === 'state'">
            <a-tag :color="SIM_STATE_COLOR[record.state]">{{ record.state }}</a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'accountCount'">
            <span class="num">{{ record.accountCount }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'robots'">
            <span class="num">{{ record.robotRunning }}/{{ record.robotCount }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'sessions'">
            <span class="num">{{ record.openSessionCount }}/{{ record.sessionCount }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'bookOrders'">
            <span class="num">{{ num(record.bookOrders, 0) }}</span>
            <span class="text-muted" style="font-size: 11px">
              （买 {{ num(record.bookBids, 0) }} / 卖 {{ num(record.bookAsks, 0) }}）
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'totalVolume'">
            <span class="num">{{ mwh(record.totalVolume) }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'totalAmount'">
            <span class="num">{{ money(record.totalAmount) }}</span>
          </template>
        </template>
      </a-table>
    </PanelCard>

    <PanelCard no-pad>
      <template #title>
        <ScrollText :size="13" style="vertical-align: -2px; margin-right: 4px" />审计日志
      </template>
      <template #extra>
        <a-button size="small" :loading="audit.loading" @click="loadAudit">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
      </template>
      <a-table
        :columns="auditColumns"
        :data-source="audit.list"
        :loading="audit.loading"
        size="small"
        row-key="id"
        :scroll="{ x: 1080 }"
        :pagination="{
          current: audit.page,
          pageSize: audit.size,
          total: audit.total,
          size: 'small',
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条审计记录`,
        }"
        @change="
          (p) => {
            audit.page = p.current
            audit.size = p.pageSize
            loadAudit()
          }
        "
      >
        <template #emptyText><a-empty description="暂无审计记录" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'createdAt'">
            <span class="num text-sub">{{ datetime(record.createdAt) }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'actor'">
            <a-tag :color="ACTOR_COLOR[record.actorType] || 'default'">{{ record.actorType }}</a-tag>
            <span>{{ record.actorName || record.actorId || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <span style="font-weight: 600">{{ record.action }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'simId'">
            <span class="num text-sub">{{ record.simId || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'target'">
            <span class="num text-sub">{{ record.target || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'detail'">
            <span class="text-sub">{{ record.detail || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'ip'">
            <span class="num text-muted">{{ record.ip || '—' }}</span>
          </template>
        </template>
      </a-table>
    </PanelCard>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
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

@media (max-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 1100px) {
  .row-2 {
    grid-template-columns: 1fr;
  }
}

.wait {
  display: grid;
  place-items: center;
  height: 200px;
  font-size: 12px;
  color: var(--text-muted);
}

.c-name {
  font-weight: 600;
  font-size: 12.5px;
}

.c-sub {
  font-size: 11px;
  color: var(--text-muted);
}
</style>
