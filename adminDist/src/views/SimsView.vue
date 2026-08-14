<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  CircleStop,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Rocket,
  Trash2,
} from 'lucide-vue-next'
import PanelCard from '../components/PanelCard.vue'
import SimFormModal from '../components/sim/SimFormModal.vue'
import { simApi } from '../api'
import { useSimStore } from '../stores/sim'
import { askConfirm, dangerConfirm } from '../composables/useDanger'
import { SIM_STATE_COLOR } from '../utils/enums'
import { datetime, money, num, pct, price } from '../utils/format'

const simStore = useSimStore()

const loading = ref(false)
const acting = ref('')
const formOpen = ref(false)
const editing = ref(null)

const current = computed(() => simStore.current)

/** SETUP → RUNNING ⇄ PAUSED → ENDED，reset 可从任意态回到 SETUP */
const STEPS = [
  { key: 'SETUP', title: '筹备中', desc: '建账户、排场次、投机器人' },
  { key: 'RUNNING', title: '交易中', desc: '账户参数已锁定，撮合运行' },
  { key: 'PAUSED', title: '已暂停', desc: '拒绝新申报，机器人停摆' },
  { key: 'ENDED', title: '已结束', desc: '终盘结算完成' },
]

const stepIndex = computed(() => {
  const s = current.value?.state
  if (s === 'SETUP') return 0
  if (s === 'RUNNING') return 1
  if (s === 'PAUSED') return 2
  if (s === 'ENDED') return 3
  return 0
})

const stepStatus = computed(() => (current.value?.state === 'PAUSED' ? 'error' : 'process'))

function can(action, state) {
  switch (action) {
    case 'start':
      return state === 'SETUP'
    case 'pause':
      return state === 'RUNNING'
    case 'resume':
      return state === 'PAUSED'
    case 'end':
      return state === 'RUNNING' || state === 'PAUSED'
    case 'reset':
      return state !== 'SETUP'
    case 'edit':
      return true
    case 'delete':
      return state === 'SETUP' || state === 'ENDED'
    default:
      return false
  }
}

async function load(force = true) {
  loading.value = true
  try {
    await simStore.load(force)
  } finally {
    loading.value = false
  }
}

async function run(action, sim) {
  acting.value = sim.id + action
  try {
    const updated = await simApi[action](sim.id)
    simStore.patch(updated)
    return updated
  } finally {
    acting.value = ''
  }
}

// ══════════════ 生命周期动作 ══════════════

function doStart(sim) {
  dangerConfirm({
    title: `确认为「${sim.name}」开盘？`,
    width: 520,
    okText: '确认开盘',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '开盘后所有用户的交易账户参数将被永久锁定，此后不可增删改。'),
      h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '开盘将同时执行：'),
      h('ul', { style: 'margin:4px 0 0;padding-left:18px;color:#9aa4b0' }, [
        h('li', '按各账户 initialCapital 注资'),
        h('li', '置 paramsLocked = true，冻结全部账户参数'),
        h('li', '以当前时刻为原点重排场次时间轴'),
        h('li', '唤起全部已启用的策略机器人'),
      ]),
    ]),
    success: '演练场已开盘，撮合引擎开始运行',
    onOk: () => run('start', sim),
  })
}

function doPause(sim) {
  askConfirm({
    title: `暂停「${sim.name}」？`,
    content: '暂停后拒绝新申报、机器人停摆，已挂委托保留在订单簿中。可随时恢复。',
    okText: '暂停演练',
    success: '演练已暂停',
    onOk: () => run('pause', sim),
  })
}

function doResume(sim) {
  run('resume', sim).then(() => message.success('演练已恢复，机器人重新开始 tick'))
}

function doEnd(sim) {
  dangerConfirm({
    title: `确认结束「${sim.name}」？`,
    width: 500,
    okText: '结束并终盘结算',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '结束是不可逆操作，演练无法再回到交易中。'),
      h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '系统将闭市全部场次、停止机器人，并触发终盘结算（period = FINAL）生成结算单与最终排名。'),
    ]),
    success: '演练已结束，终盘结算已触发',
    onOk: () => run('end', sim),
  })
}

function doReset(sim) {
  dangerConfirm({
    title: `确认重置「${sim.name}」？`,
    width: 520,
    okText: '我确认清空全部交易数据',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '该操作会清空本演练场的全部交易数据且不可恢复！'),
      h('ul', { style: 'margin:6px 0 0;padding-left:18px;color:#9aa4b0' }, [
        h('li', '交易账户、资金流水、持仓全部删除'),
        h('li', '委托、成交、挂牌、协商、现货报价全部清空'),
        h('li', '合同、结算单、排行榜清零'),
        h('li', '机器人与行情重置，演练场回到「筹备中」'),
      ]),
    ]),
    success: '演练场已重置，可重新组织参与者',
    onOk: () => run('reset', sim),
  })
}

function doDelete(sim) {
  dangerConfirm({
    title: `删除演练场「${sim.name}」？`,
    content: '删除后该演练场及其全部关联数据将从系统中移除，无法恢复。',
    okText: '删除',
    success: '演练场已删除',
    onOk: async () => {
      await simApi.remove(sim.id)
      simStore.remove(sim.id)
    },
  })
}

function openCreate() {
  editing.value = null
  formOpen.value = true
}

function openEdit(sim) {
  editing.value = sim
  formOpen.value = true
}

function onSaved(sim) {
  simStore.patch(sim)
  if (sim?.id && !simStore.currentId) simStore.setCurrent(sim.id)
  load(true)
}

// ══════════════ 表格 ══════════════

const columns = [
  { title: '演练场', dataIndex: 'name', width: 260 },
  { title: '状态', dataIndex: 'state', width: 92 },
  { title: '基准日', dataIndex: 'baseDate', width: 104 },
  { title: '交易账户', dataIndex: 'accountCount', width: 148 },
  { title: '场次', dataIndex: 'sessionCount', width: 96, align: 'right' },
  { title: '基准电价', dataIndex: 'benchmarkPrice', width: 104, align: 'right' },
  { title: '创建时间', dataIndex: 'createdAt', width: 150 },
  { title: '生命周期操作', dataIndex: 'lifecycle', width: 300, fixed: 'right' },
]
onMounted(() => load(false))
</script>

<template>
  <div class="page">
    <!-- ── 当前演练场生命周期 ─────────────────────── -->
    <PanelCard title="演练场生命周期">
      <template #extra>
        <a-button size="small" @click="load(true)">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
        <a-button type="primary" size="small" @click="openCreate">
          <Plus :size="13" style="vertical-align: -2px" /> 新建演练场
        </a-button>
      </template>

      <div v-if="current" class="lifecycle">
        <div class="lc-left">
          <div class="lc-name">
            {{ current.name }}
            <a-tag :color="SIM_STATE_COLOR[current.state]">{{ current.stateLabel || current.state }}</a-tag>
            <span class="lc-id num">{{ current.id }}</span>
          </div>
          <div class="lc-desc">{{ current.description || '暂无演练说明' }}</div>

          <a-steps
            :current="stepIndex"
            :status="stepStatus"
            size="small"
            class="lc-steps"
            :items="
              STEPS.map((s, i) => ({
                title: s.title,
                description: s.desc,
                disabled: true,
                status:
                  i < stepIndex ? 'finish' : i === stepIndex ? (current.state === 'PAUSED' ? 'error' : 'process') : 'wait',
              }))
            "
          />

          <div class="lc-flow">
            SETUP ──开盘──▶ RUNNING ──暂停──▶ PAUSED ──恢复──▶ RUNNING ──结束──▶ ENDED
            <span class="lc-flow-reset">（reset 可从任意状态回到 SETUP）</span>
          </div>
        </div>

        <div class="lc-right">
          <div class="lc-actions">
            <a-button
              type="primary"
              :disabled="!can('start', current.state)"
              :loading="acting === current.id + 'start'"
              @click="doStart(current)"
            >
              <Rocket :size="14" style="vertical-align: -2px" /> 开盘
            </a-button>
            <a-button
              :disabled="!can('pause', current.state)"
              :loading="acting === current.id + 'pause'"
              @click="doPause(current)"
            >
              <Pause :size="14" style="vertical-align: -2px" /> 暂停
            </a-button>
            <a-button
              :disabled="!can('resume', current.state)"
              :loading="acting === current.id + 'resume'"
              @click="doResume(current)"
            >
              <Play :size="14" style="vertical-align: -2px" /> 恢复
            </a-button>
            <a-button
              danger
              :disabled="!can('end', current.state)"
              :loading="acting === current.id + 'end'"
              @click="doEnd(current)"
            >
              <CircleStop :size="14" style="vertical-align: -2px" /> 结束
            </a-button>
            <a-button
              danger
              type="primary"
              :disabled="!can('reset', current.state)"
              :loading="acting === current.id + 'reset'"
              @click="doReset(current)"
            >
              <RotateCcw :size="14" style="vertical-align: -2px" /> 重置
            </a-button>
          </div>

          <a-descriptions :column="2" size="small" bordered class="lc-cfg">
            <a-descriptions-item label="每人账户上限">
              {{ current.cfg?.maxAccountsPerUser ?? '—' }} 个
            </a-descriptions-item>
            <a-descriptions-item label="基准电价">
              {{ price(current.cfg?.benchmarkPrice) }} 元/MWh
            </a-descriptions-item>
            <a-descriptions-item label="初始资金区间">
              {{ money(current.cfg?.initialCapitalMin) }} ~ {{ money(current.cfg?.initialCapitalMax) }}
            </a-descriptions-item>
            <a-descriptions-item label="涨跌幅限制">{{ pct(current.cfg?.priceLimitPct) }}</a-descriptions-item>
            <a-descriptions-item label="买/卖手续费率">
              {{ num(current.cfg?.feeRateBuy * 1000, 2) }}‰ / {{ num(current.cfg?.feeRateSell * 1000, 2) }}‰
            </a-descriptions-item>
            <a-descriptions-item label="保证金比例">{{ pct(current.cfg?.marginRatio) }}</a-descriptions-item>
            <a-descriptions-item label="偏差考核容差">
              {{ pct(current.cfg?.deviationTolerancePct) }}
            </a-descriptions-item>
            <a-descriptions-item label="裸卖空">
              <a-tag :color="current.cfg?.allowNakedShort ? 'warning' : 'default'">
                {{ current.cfg?.allowNakedShort ? '允许' : '禁止' }}
              </a-tag>
            </a-descriptions-item>
          </a-descriptions>
        </div>
      </div>

      <a-empty v-else description="尚未选择演练场，请先在顶栏切换或新建一个" />
    </PanelCard>

    <!-- ── 演练场列表 ─────────────────────────────── -->
    <PanelCard title="全部演练场" no-pad>
      <template #extra>
        <span class="text-muted" style="font-size: 11px">共 {{ simStore.sims.length }} 场</span>
      </template>
      <a-table
        :columns="columns"
        :data-source="simStore.sims"
        :loading="loading"
        :pagination="false"
        size="small"
        row-key="id"
        :scroll="{ x: 1360 }"
        :row-class-name="(r) => (r.id === simStore.currentId ? 'row-current' : '')"
      >
        <template #emptyText>
          <a-empty description="还没有演练场，点右上角新建一个" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'name'">
            <div class="cell-name">
              <a @click="simStore.setCurrent(record.id)">{{ record.name }}</a>
              <span class="num text-muted">{{ record.id }}</span>
            </div>
            <div class="cell-sub">{{ record.description || '—' }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'state'">
            <a-tag :color="SIM_STATE_COLOR[record.state]">{{ record.stateLabel || record.state }}</a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'baseDate'">
            <span class="num">{{ record.baseDate || '—' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'accountCount'">
            <span class="num">{{ record.accountCount }}</span>
            <span class="text-muted" style="font-size: 11px">
              （真人 {{ record.humanAccountCount }} / 机器人 {{ record.robotAccountCount }}）
            </span>
          </template>

          <template v-else-if="column.dataIndex === 'sessionCount'">
            <span class="num">{{ record.openSessionCount }}/{{ record.sessionCount }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'benchmarkPrice'">
            <span class="num">{{ price(record.cfg?.benchmarkPrice) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'createdAt'">
            <span class="num text-sub">{{ datetime(record.createdAt) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'lifecycle'">
            <a-space :size="2" wrap>
              <a-button
                v-if="can('start', record.state)"
                type="link"
                size="small"
                @click="doStart(record)"
              >开盘</a-button>
              <a-button
                v-if="can('pause', record.state)"
                type="link"
                size="small"
                @click="doPause(record)"
              >暂停</a-button>
              <a-button
                v-if="can('resume', record.state)"
                type="link"
                size="small"
                @click="doResume(record)"
              >恢复</a-button>
              <a-button
                v-if="can('end', record.state)"
                type="link"
                size="small"
                danger
                @click="doEnd(record)"
              >结束</a-button>
              <a-button
                v-if="can('reset', record.state)"
                type="link"
                size="small"
                danger
                @click="doReset(record)"
              >重置</a-button>
              <a-divider type="vertical" style="margin: 0 2px" />
              <a-button type="link" size="small" @click="openEdit(record)">
                <Pencil :size="12" style="vertical-align: -1px" /> 编辑
              </a-button>
              <a-button
                type="link"
                size="small"
                danger
                :disabled="!can('delete', record.state)"
                @click="doDelete(record)"
              >
                <Trash2 :size="12" style="vertical-align: -1px" /> 删除
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </PanelCard>

    <SimFormModal v-model:open="formOpen" :record="editing" @saved="onSaved" />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lifecycle {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
  gap: 20px;
}

@media (max-width: 1200px) {
  .lifecycle {
    grid-template-columns: 1fr;
  }
}

.lc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.lc-id {
  font-size: 11.5px;
  color: var(--text-muted);
}

.lc-desc {
  margin: 5px 0 16px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.7;
}

.lc-steps :deep(.ant-steps-item-description) {
  font-size: 11px !important;
  color: var(--text-muted) !important;
}

.lc-flow {
  margin-top: 14px;
  padding: 8px 10px;
  border: 1px dashed var(--border-strong);
  border-radius: 5px;
  font-family: Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-sub);
  letter-spacing: 0.3px;
}

.lc-flow-reset {
  color: var(--text-muted);
  font-family: inherit;
}

.lc-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.lc-cfg :deep(.ant-descriptions-item-label) {
  width: 110px;
  font-size: 11.5px;
}

.lc-cfg :deep(.ant-descriptions-item-content) {
  font-size: 12px;
}

.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.cell-name a {
  color: var(--text);
}

.cell-name a:hover {
  color: #79b8ff;
}

.cell-sub {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}

:deep(.row-current) > td {
  background: rgba(47, 129, 247, 0.07) !important;
}
</style>
