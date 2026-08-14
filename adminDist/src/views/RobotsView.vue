<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Bot, Pencil, Plus, Power, RefreshCw, Sparkles, Trash2 } from 'lucide-vue-next'
import PanelCard from '../components/PanelCard.vue'
import RobotConfigForm from '../components/robot/RobotConfigForm.vue'
import { robotApi } from '../api'
import { useSimStore } from '../stores/sim'
import { askConfirm, dangerConfirm } from '../composables/useDanger'
import {
  GEN_TYPE,
  LOAD_PROFILE,
  MEMBER_TYPE,
  MEMBER_TYPE_COLOR,
  ROBOT_STRATEGY,
  STRATEGY_COLOR,
} from '../utils/enums'
import { fromNow, money, mwh, num, pnlClass } from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)

const loading = ref(false)
const rows = ref([])
const strategies = ref([])
const acting = ref('')
const filters = reactive({ strategy: null, memberType: null, enabled: null, keyword: '' })

const DEFAULT_CFG = {
  tickIntervalMs: 3000,
  aggressiveness: 0.5,
  priceBiasPct: 0,
  maxOrderMwh: 5000,
  spreadPct: 0.012,
  inventoryTargetMwh: 0,
  actProbability: 0.6,
  cancelProbability: 0.25,
  listingProbability: 0.2,
}

// ══════════════ 取数 ══════════════

async function load() {
  if (!simId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const page = await robotApi.list(simId.value, 1, 500)
    rows.value = page?.list || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadStrategies() {
  try {
    strategies.value = (await robotApi.strategies()) || []
  } catch {
    strategies.value = []
  }
}

const filtered = computed(() =>
  rows.value.filter((r) => {
    if (filters.strategy && r.strategy !== filters.strategy) return false
    if (filters.memberType && r.memberType !== filters.memberType) return false
    if (filters.enabled === 'on' && !r.enabled) return false
    if (filters.enabled === 'off' && r.enabled) return false
    if (filters.keyword) {
      const k = filters.keyword.toLowerCase()
      if (!`${r.name} ${r.id} ${r.accountCode}`.toLowerCase().includes(k)) return false
    }
    return true
  }),
)

const stat = computed(() => ({
  total: rows.value.length,
  running: rows.value.filter((r) => r.running).length,
  enabled: rows.value.filter((r) => r.enabled).length,
  orders: rows.value.reduce((a, b) => a + (b.orderCount || 0), 0),
  trades: rows.value.reduce((a, b) => a + (b.tradeCount || 0), 0),
  cancels: rows.value.reduce((a, b) => a + (b.cancelCount || 0), 0),
}))

// ══════════════ 启停 ══════════════

async function toggle(r) {
  acting.value = r.id
  try {
    await (r.enabled ? robotApi.disable(r.id) : robotApi.enable(r.id))
    message.success(`${r.name} 已${r.enabled ? '停用' : '启用'}`)
    await load()
  } finally {
    acting.value = ''
  }
}

function enableAll() {
  askConfirm({
    title: '全部启用该演练场的机器人？',
    okText: '全部启用',
    content: `将启用 ${rows.value.length} 台机器人并立即开始 tick 报价，市场流动性会迅速升高。`,
    onOk: async () => {
      const r = await robotApi.enableAll(simId.value)
      message.success(`已启用 ${r?.affected ?? 0} 台，当前运行 ${r?.running ?? 0} 台`)
      await load()
    },
  })
}

function disableAll() {
  dangerConfirm({
    title: '确认全部停用机器人？',
    width: 480,
    okText: '我确认全部停用',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, `将一次性停用 ${rows.value.length} 台机器人。`),
      h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '停用后市场将失去主要流动性来源，真人学员可能长时间无法成交，请确认这是本次演练的意图。'),
    ]),
    onOk: async () => {
      const r = await robotApi.disableAll(simId.value)
      message.success(`已停用 ${r?.affected ?? 0} 台，剩余运行 ${r?.running ?? 0} 台`)
      await load()
    },
  })
}

function doDelete(r) {
  dangerConfirm({
    title: `删除机器人「${r.name}」？`,
    content: '机器人及其绑定的交易账户将被移除，历史成交仍保留在市场统计中。',
    okText: '删除',
    success: '机器人已删除',
    onOk: async () => {
      await robotApi.remove(r.id)
      await load()
    },
  })
}

// ══════════════ 单个新建 / 编辑 ══════════════

const editOpen = ref(false)
const editing = ref(null)
const editSubmitting = ref(false)
const editForm = reactive({
  name: '',
  memberType: 'GENCO',
  strategy: 'MARKET_MAKER',
  initialCapital: null,
  genType: 'COAL',
  loadProfile: 'CONTINUOUS',
  enabled: true,
  cfg: { ...DEFAULT_CFG },
})

const editStrategyMeta = computed(() => strategies.value.find((s) => s.value === editForm.strategy) || null)

function openCreate() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  editing.value = null
  Object.assign(editForm, {
    name: '',
    memberType: 'GENCO',
    strategy: 'MARKET_MAKER',
    initialCapital: null,
    genType: 'COAL',
    loadProfile: 'CONTINUOUS',
    enabled: true,
    cfg: { ...DEFAULT_CFG, ...(strategies.value[0]?.defaultCfg || {}) },
  })
  editOpen.value = true
}

function openEdit(r) {
  editing.value = r
  Object.assign(editForm, {
    name: r.name,
    memberType: r.memberType,
    strategy: r.strategy,
    initialCapital: r.initialCapital,
    genType: r.genType || 'COAL',
    loadProfile: r.loadProfile || 'CONTINUOUS',
    enabled: r.enabled,
    cfg: { ...DEFAULT_CFG, ...(r.cfg || {}) },
  })
  editOpen.value = true
}

/** 换策略时把参数刷成该策略的官方默认值，避免沿用上一个策略的口径 */
function applyStrategyDefaults() {
  const meta = editStrategyMeta.value
  if (meta?.defaultCfg) editForm.cfg = { ...DEFAULT_CFG, ...meta.defaultCfg }
}

watch(
  () => editForm.strategy,
  () => {
    if (!editing.value) applyStrategyDefaults()
  },
)

async function submitEdit() {
  if (!editForm.name?.trim()) return message.warning('请填写机器人名称')
  editSubmitting.value = true
  try {
    if (editing.value) {
      await robotApi.update(editing.value.id, {
        name: editForm.name.trim(),
        strategy: editForm.strategy,
        enabled: editForm.enabled,
        cfg: { ...editForm.cfg },
      })
      message.success('机器人已更新')
    } else {
      await robotApi.create({
        simId: simId.value,
        name: editForm.name.trim(),
        memberType: editForm.memberType,
        strategy: editForm.strategy,
        initialCapital: editForm.initialCapital,
        genType: editForm.memberType === 'GENCO' ? editForm.genType : null,
        loadProfile: editForm.memberType === 'CONSUMER' ? editForm.loadProfile : null,
        cfg: { ...editForm.cfg },
        enabled: editForm.enabled,
      })
      message.success('机器人已创建，交易账户同步生成')
    }
    editOpen.value = false
    await load()
  } finally {
    editSubmitting.value = false
  }
}

// ══════════════ 批量生成 ══════════════

const batchOpen = ref(false)
const batchSubmitting = ref(false)
const batch = reactive({
  count: 12,
  capitalMin: 50000000,
  capitalMax: 200000000,
  strategyMix: {},
  memberTypeMix: {},
  useStrategyMix: false,
  useMemberMix: false,
})

function openBatch() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  batch.count = 12
  batch.capitalMin = 50000000
  batch.capitalMax = 200000000
  batch.useStrategyMix = false
  batch.useMemberMix = false
  batch.strategyMix = Object.fromEntries(ROBOT_STRATEGY.map((s) => [s.value, 2]))
  batch.memberTypeMix = { GENCO: 5, CONSUMER: 4, RETAILER: 3 }
  batchOpen.value = true
}

const strategyMixTotal = computed(() => Object.values(batch.strategyMix).reduce((a, b) => a + (Number(b) || 0), 0))
const memberMixTotal = computed(() => Object.values(batch.memberTypeMix).reduce((a, b) => a + (Number(b) || 0), 0))

async function submitBatch() {
  if (batch.capitalMin > batch.capitalMax) return message.warning('资金下限不能高于上限')
  batchSubmitting.value = true
  try {
    const body = {
      simId: simId.value,
      count: batch.useStrategyMix || batch.useMemberMix ? null : batch.count,
      capitalMin: batch.capitalMin,
      capitalMax: batch.capitalMax,
    }
    if (batch.useStrategyMix) body.strategyMix = pruneMix(batch.strategyMix)
    if (batch.useMemberMix) body.memberTypeMix = pruneMix(batch.memberTypeMix)
    const r = await robotApi.batch(body)
    message.success(`已生成 ${r?.created ?? 0} 台，当前共 ${r?.total ?? 0} 台，运行 ${r?.running ?? 0} 台`)
    batchOpen.value = false
    await load()
  } finally {
    batchSubmitting.value = false
  }
}

function pruneMix(mix) {
  const out = {}
  Object.entries(mix).forEach(([k, v]) => {
    const n = Number(v) || 0
    if (n > 0) out[k] = n
  })
  return out
}

// ══════════════ 表格 ══════════════

const columns = [
  { title: '机器人', dataIndex: 'name', width: 190, fixed: 'left' },
  { title: '主体类型', dataIndex: 'memberType', width: 106 },
  { title: '策略', dataIndex: 'strategy', width: 116 },
  { title: '绑定账户', dataIndex: 'accountCode', width: 96 },
  { title: '状态', dataIndex: 'enabled', width: 116 },
  { title: '下单', dataIndex: 'orderCount', width: 72, align: 'right' },
  { title: '成交', dataIndex: 'tradeCount', width: 72, align: 'right' },
  { title: '撤单', dataIndex: 'cancelCount', width: 72, align: 'right' },
  { title: '可用资金', dataIndex: 'available', width: 112, align: 'right' },
  { title: '购/售电量', dataIndex: 'mwh', width: 176, align: 'right' },
  { title: '总盈亏', dataIndex: 'totalPnl', width: 116, align: 'right' },
  { title: '最近动作', dataIndex: 'lastAct', width: 190 },
  { title: '操作', dataIndex: 'ops', width: 168, fixed: 'right' },
]

onMounted(() => {
  simStore.load().then(load)
  loadStrategies()
})

watch(simId, load)
</script>

<template>
  <div class="page">
    <PanelCard title="策略机器人">
      <template #extra>
        <a-button size="small" @click="load">
          <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
        </a-button>
        <a-button size="small" :disabled="!simId || !rows.length" @click="enableAll">
          <Power :size="13" style="vertical-align: -2px" /> 全部启用
        </a-button>
        <a-button size="small" danger :disabled="!simId || !rows.length" @click="disableAll">
          <Power :size="13" style="vertical-align: -2px" /> 全部停用
        </a-button>
        <a-button size="small" :disabled="!simId" @click="openBatch">
          <Sparkles :size="13" style="vertical-align: -2px" /> 批量生成
        </a-button>
        <a-button type="primary" size="small" :disabled="!simId" @click="openCreate">
          <Plus :size="13" style="vertical-align: -2px" /> 新建机器人
        </a-button>
      </template>

      <div class="toolbar">
        <div class="stat-strip">
          <span>共 <b class="num">{{ stat.total }}</b> 台</span>
          <span>运行中 <b class="num" style="color: #00b578">{{ stat.running }}</b></span>
          <span>已启用 <b class="num">{{ stat.enabled }}</b></span>
          <span class="text-sub">累计下单 <b class="num">{{ num(stat.orders, 0) }}</b> · 成交 <b class="num">{{ num(stat.trades, 0) }}</b> · 撤单 <b class="num">{{ num(stat.cancels, 0) }}</b></span>
        </div>
        <div class="filters">
          <a-select v-model:value="filters.strategy" size="small" style="width: 130px" placeholder="全部策略" allow-clear :options="ROBOT_STRATEGY" />
          <a-select v-model:value="filters.memberType" size="small" style="width: 120px" placeholder="全部主体" allow-clear :options="MEMBER_TYPE" />
          <a-select
            v-model:value="filters.enabled"
            size="small"
            style="width: 108px"
            placeholder="全部状态"
            allow-clear
            :options="[
              { value: 'on', label: '已启用' },
              { value: 'off', label: '已停用' },
            ]"
          />
          <a-input v-model:value="filters.keyword" size="small" style="width: 150px" placeholder="搜索名称/账户" allow-clear />
        </div>
      </div>
    </PanelCard>

    <PanelCard no-pad>
      <a-table
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        :pagination="{ pageSize: 20, size: 'small', showSizeChanger: true, showTotal: (t) => `共 ${t} 台机器人` }"
        size="small"
        row-key="id"
        :scroll="{ x: 1740 }"
      >
        <template #emptyText>
          <a-empty
            :description="
              !simId ? '请先在顶栏选择演练场' : rows.length ? '当前筛选条件下没有机器人' : '该演练场还没有机器人，可点「批量生成」快速投放'
            "
          />
        </template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'name'">
            <div class="c-name">
              <span class="run-dot" :class="record.running ? 'on' : 'off'"></span>
              {{ record.name }}
            </div>
            <div class="c-sub num">{{ record.id }}{{ record.genType ? ' · ' + record.genType : '' }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'memberType'">
            <a-tag :color="MEMBER_TYPE_COLOR[record.memberType]">{{ record.memberTypeLabel }}</a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'strategy'">
            <a-tag :color="STRATEGY_COLOR[record.strategy]">{{ record.strategyLabel }}</a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'accountCode'">
            <span class="num">{{ record.accountCode || '—' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'enabled'">
            <a-switch
              :checked="record.enabled"
              size="small"
              :loading="acting === record.id"
              @change="() => toggle(record)"
            />
            <span class="c-sub" style="margin-left: 6px">{{ record.running ? '运行中' : record.enabled ? '待调度' : '已停用' }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'orderCount'">
            <span class="num">{{ num(record.orderCount, 0) }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'tradeCount'">
            <span class="num">{{ num(record.tradeCount, 0) }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'cancelCount'">
            <span class="num text-sub">{{ num(record.cancelCount, 0) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'available'">
            <div class="num">{{ money(record.available) }}</div>
            <div class="c-sub num">冻结 {{ money(record.frozen + record.margin) }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'mwh'">
            <span class="num up">买 {{ mwh(record.buyMwh, false) }}</span>
            <span class="text-muted"> / </span>
            <span class="num down">卖 {{ mwh(record.sellMwh, false) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'totalPnl'">
            <span class="num" :class="pnlClass(record.totalPnl)">{{ num(record.totalPnl, 0) }}</span>
          </template>

          <template v-else-if="column.dataIndex === 'lastAct'">
            <div class="c-sub">{{ record.lastAction || '—' }}</div>
            <div class="c-sub num">{{ record.lastActAt ? fromNow(record.lastActAt) : '尚未动作' }}</div>
          </template>

          <template v-else-if="column.dataIndex === 'ops'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="openEdit(record)">
                <Pencil :size="12" style="vertical-align: -1px" /> 编辑
              </a-button>
              <a-button type="link" size="small" danger @click="doDelete(record)">
                <Trash2 :size="12" style="vertical-align: -1px" /> 删除
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </PanelCard>

    <!-- 策略说明 -->
    <PanelCard title="策略说明">
      <template #extra>
        <span class="text-muted" style="font-size: 11px">来自 GET /api/admin/robots/strategies</span>
      </template>
      <div v-if="strategies.length" class="strategy-grid">
        <div v-for="s in strategies" :key="s.value" class="strategy-card">
          <div class="sc-head">
            <a-tag :color="STRATEGY_COLOR[s.value]" style="margin: 0">{{ s.label }}</a-tag>
            <span class="num sc-code">{{ s.value }}</span>
          </div>
          <div class="sc-desc">{{ s.description }}</div>
          <div class="sc-meta">
            <span class="text-muted">适用主体</span>
            <a-tag
              v-for="m in s.suitableMemberTypes || []"
              :key="m"
              :color="MEMBER_TYPE_COLOR[m]"
              style="margin: 0 3px 0 0"
            >
              {{ MEMBER_TYPE.find((x) => x.value === m)?.label || m }}
            </a-tag>
          </div>
          <div class="sc-cfg">
            <span>tick {{ s.defaultCfg?.tickIntervalMs }}ms</span>
            <span>激进 {{ s.defaultCfg?.aggressiveness }}</span>
            <span>价差 {{ s.defaultCfg?.spreadPct }}</span>
            <span>单笔 {{ num(s.defaultCfg?.maxOrderMwh, 0) }} MWh</span>
            <span>下单 {{ s.defaultCfg?.actProbability }}</span>
            <span>撤单 {{ s.defaultCfg?.cancelProbability }}</span>
          </div>
        </div>
      </div>
      <a-empty v-else description="策略元数据加载中或后端不可达" />
    </PanelCard>

    <!-- 新建 / 编辑 -->
    <a-modal
      v-model:open="editOpen"
      :title="editing ? `编辑机器人 · ${editing.name}` : '新建机器人'"
      :width="860"
      :confirm-loading="editSubmitting"
      ok-text="保存"
      cancel-text="取消"
      centered
      @ok="submitEdit"
    >
      <a-form layout="vertical">
        <div class="g3">
          <a-form-item label="机器人名称" required>
            <a-input v-model:value="editForm.name" placeholder="如：华能燃煤一厂" :maxlength="40" />
          </a-form-item>
          <a-form-item label="主体类型">
            <a-select v-model:value="editForm.memberType" :disabled="!!editing" :options="MEMBER_TYPE" />
          </a-form-item>
          <a-form-item label="交易策略">
            <a-select v-model:value="editForm.strategy" :options="ROBOT_STRATEGY" />
          </a-form-item>
        </div>

        <div class="g3">
          <a-form-item v-if="editForm.memberType === 'GENCO'" label="机组类型">
            <a-select v-model:value="editForm.genType" :disabled="!!editing" :options="GEN_TYPE" />
          </a-form-item>
          <a-form-item v-if="editForm.memberType === 'CONSUMER'" label="负荷特性">
            <a-select v-model:value="editForm.loadProfile" :disabled="!!editing" :options="LOAD_PROFILE" />
          </a-form-item>
          <a-form-item label="初始资金（元）">
            <a-input-number
              v-model:value="editForm.initialCapital"
              :disabled="!!editing"
              :min="0"
              :step="10000000"
              style="width: 100%"
              placeholder="留空按主体类型取默认区间"
            />
          </a-form-item>
          <a-form-item label="创建后立即启用">
            <a-switch v-model:checked="editForm.enabled" size="small" />
          </a-form-item>
        </div>

        <a-alert
          v-if="editStrategyMeta"
          type="info"
          show-icon
          style="margin-bottom: 14px"
          :message="`${editStrategyMeta.label}：${editStrategyMeta.description}`"
        />

        <a-divider orientation="left" style="margin: 4px 0 14px">
          <span class="dt">行为参数 RobotConfig</span>
          <a-button
            v-if="editStrategyMeta"
            type="link"
            size="small"
            @click="editForm.cfg = { ...DEFAULT_CFG, ...editStrategyMeta.defaultCfg }"
          >
            恢复该策略默认值
          </a-button>
        </a-divider>

        <RobotConfigForm v-model="editForm.cfg" />
      </a-form>
    </a-modal>

    <!-- 批量生成 -->
    <a-modal
      v-model:open="batchOpen"
      title="批量生成机器人"
      :width="720"
      :confirm-loading="batchSubmitting"
      ok-text="生成"
      cancel-text="取消"
      centered
      @ok="submitBatch"
    >
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 14px"
        message="不配比时按内置默认结构生成（发电 / 用电 / 售电 ≈ 10 : 9 : 5）；配比与数量同时给出时以配比为准。"
      />

      <a-form layout="vertical">
        <div class="g3">
          <a-form-item label="生成台数">
            <a-input-number
              v-model:value="batch.count"
              :min="1"
              :max="200"
              :disabled="batch.useStrategyMix || batch.useMemberMix"
              style="width: 100%"
            />
          </a-form-item>
          <a-form-item label="资金下限（元）">
            <a-input-number v-model:value="batch.capitalMin" :min="0" :step="10000000" style="width: 100%" />
          </a-form-item>
          <a-form-item label="资金上限（元）">
            <a-input-number v-model:value="batch.capitalMax" :min="0" :step="10000000" style="width: 100%" />
          </a-form-item>
        </div>

        <a-divider orientation="left" style="margin: 4px 0 12px">
          <a-checkbox v-model:checked="batch.useStrategyMix">
            <span class="dt">按策略配比（共 {{ strategyMixTotal }} 台）</span>
          </a-checkbox>
        </a-divider>
        <div class="mix-grid" :class="{ dim: !batch.useStrategyMix }">
          <div v-for="s in ROBOT_STRATEGY" :key="s.value" class="mix-item">
            <span class="mix-label">{{ s.label }}</span>
            <a-input-number
              v-model:value="batch.strategyMix[s.value]"
              :min="0"
              :max="100"
              size="small"
              :disabled="!batch.useStrategyMix"
              style="width: 74px"
            />
          </div>
        </div>

        <a-divider orientation="left" style="margin: 14px 0 12px">
          <a-checkbox v-model:checked="batch.useMemberMix">
            <span class="dt">按主体类型配比（共 {{ memberMixTotal }} 台）</span>
          </a-checkbox>
        </a-divider>
        <div class="mix-grid" :class="{ dim: !batch.useMemberMix }">
          <div v-for="m in MEMBER_TYPE" :key="m.value" class="mix-item">
            <span class="mix-label">{{ m.label }}</span>
            <a-input-number
              v-model:value="batch.memberTypeMix[m.value]"
              :min="0"
              :max="100"
              size="small"
              :disabled="!batch.useMemberMix"
              style="width: 74px"
            />
          </div>
        </div>
      </a-form>

      <div class="batch-foot">
        <Bot :size="13" />
        生成的机器人会自动创建对应交易账户；若演练场已开盘，新账户按当前规则注资并立即锁定参数。
      </div>
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

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.c-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 12.5px;
}

.c-sub {
  font-size: 11px;
  color: var(--text-muted);
}

.run-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: 0 0 6px;
}
.run-dot.on {
  background: #00b578;
  box-shadow: 0 0 6px #00b578;
}
.run-dot.off {
  background: #484f58;
}

/* 策略说明卡片 */
.strategy-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 1200px) {
  .strategy-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.strategy-card {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--bg-panel-2);
}

.sc-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sc-code {
  font-size: 10.5px;
  color: var(--text-muted);
}

.sc-desc {
  margin: 7px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-sub);
  min-height: 40px;
}

.sc-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  margin-bottom: 7px;
}

.sc-cfg {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  padding-top: 7px;
  border-top: 1px dashed var(--border-strong);
  font-size: 10.5px;
  color: var(--text-muted);
  font-family: Consolas, monospace;
}

/* 表单 */
.g3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 16px;
}

.dt {
  font-size: 12px;
  color: var(--text-sub);
}

.mix-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
}

.mix-grid.dim {
  opacity: 0.45;
}

.mix-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.mix-label {
  font-size: 12px;
  color: var(--text-sub);
}

.batch-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-muted);
}
</style>
