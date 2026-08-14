<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Filter, Plus, RefreshCw, Tags } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import StateTag from '@/components/common/StateTag.vue'
import NumFlash from '@/components/common/NumFlash.vue'
import { listingApi } from '@/api/user'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { useMarketSocket } from '@/composables/useMarketSocket'
import { usePolling } from '@/composables/usePolling'
import { instrumentLabel } from '@/utils/enums'
import { amount, datetime, price as fp, qty as fq } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

const rows = ref([])
const loading = ref(false)
const total = ref(0)

const filters = reactive({ side: undefined, instrumentKey: undefined, status: 'LISTED', mine: false, page: 1, size: 20 })

const createOpen = ref(false)
const takeOpen = ref(false)
const saving = ref(false)
const takeTarget = ref(null)
const takeQty = ref(null)
const createRef = ref(null)

const accountId = computed(() => acctStore.currentId)
const listingSessions = computed(() => simStore.sessionsOfMode('LISTING'))
const instrumentOptions = computed(() => {
  const set = new Map()
  for (const s of listingSessions.value) if (s.instrumentKey) set.set(s.instrumentKey, instrumentLabel(s.instrumentKey))
  return [...set].map(([value, label]) => ({ value, label }))
})

const form = reactive({
  sessionId: undefined,
  side: 'SELL',
  price: null,
  totalQty: null,
  minTakeQty: null,
  allowPartial: true,
  validHours: 0,
  title: '',
  remark: '',
})

const formSession = computed(() => simStore.sessionOf(form.sessionId))

async function load() {
  if (!simStore.currentSimId) return
  if (filters.mine && !accountId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const res = await listingApi.list({
      simId: simStore.currentSimId,
      side: filters.side,
      status: filters.status,
      mine: filters.mine,
      accountId: filters.mine ? accountId.value : undefined,
      page: filters.page,
      size: filters.size,
    })
    let list = res?.list || []
    if (filters.instrumentKey) {
      list = list.filter((x) => `${x.variety}_${x.periodType}` === filters.instrumentKey)
    }
    rows.value = list
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}

watch(() => [simStore.currentSimId, accountId.value], load, { immediate: true })
watch(() => [filters.side, filters.status, filters.mine, filters.instrumentKey, filters.page], load)

usePolling(load, 10_000, { immediate: false })

useMarketSocket({
  topics: () => {
    const sim = simStore.currentSimId
    if (!sim) return []
    return instrumentOptions.value.map((o) => `listing.${sim}.${o.value}`)
  },
  listing: () => load(),
})

function openCreate() {
  if (!accountId.value) {
    message.warning('请先选择交易账户')
    return
  }
  const live = listingSessions.value.find((s) => s.state === 'OPEN') || listingSessions.value[0]
  form.sessionId = live?.id
  form.side = acctStore.current?.memberType === 'GENCO' ? 'SELL' : 'BUY'
  form.price = live?.lastPrice || live?.prevClose || simStore.cfg.benchmarkPrice
  form.totalQty = null
  form.minTakeQty = live?.lotSize || null
  form.allowPartial = true
  form.validHours = 0
  form.title = ''
  form.remark = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!form.sessionId || !form.price || !form.totalQty) {
    message.warning('请填写场次、挂牌价格与总电量')
    return
  }
  saving.value = true
  try {
    await listingApi.create({
      accountId: accountId.value,
      sessionId: form.sessionId,
      side: form.side,
      price: Number(form.price),
      totalQty: Number(form.totalQty),
      minTakeQty: Number(form.minTakeQty || 0),
      allowPartial: form.allowPartial,
      validUntil: form.validHours > 0 ? Date.now() + form.validHours * 3600_000 : 0,
      title: form.title,
      remark: form.remark,
    })
    message.success('挂牌成功，已进入挂牌大厅')
    createOpen.value = false
    load()
    acctStore.refreshCurrent()
  } catch {
    // 校验失败原因由后端返回
  } finally {
    saving.value = false
  }
}

function openTake(row) {
  if (!accountId.value) {
    message.warning('请先选择交易账户')
    return
  }
  if (row.accountId === accountId.value) {
    message.warning('不可摘自己挂出的牌')
    return
  }
  takeTarget.value = row
  takeQty.value = row.allowPartial ? row.minTakeQty || row.remainQty : row.remainQty
  takeOpen.value = true
}

const takeError = computed(() => {
  const r = takeTarget.value
  const q = Number(takeQty.value)
  if (!r || !q) return ''
  if (!r.allowPartial && Math.abs(q - r.remainQty) > 1e-6) return `该挂牌不允许部分摘牌，必须整量摘取 ${fq(r.remainQty)} MWh`
  if (q < (r.minTakeQty || 0) - 1e-6) return `低于最小摘牌量 ${fq(r.minTakeQty)} MWh`
  if (q > r.remainQty + 1e-6) return `超过剩余可摘量 ${fq(r.remainQty)} MWh`
  return ''
})

const takeNotional = computed(() => (Number(takeQty.value) || 0) * (takeTarget.value?.price || 0))

async function submitTake() {
  if (takeError.value || !takeQty.value) return
  saving.value = true
  try {
    await listingApi.take(takeTarget.value.id, { accountId: accountId.value, quantity: Number(takeQty.value) })
    message.success('摘牌成交，购售电合同已生成')
    takeOpen.value = false
    load()
    acctStore.refreshCurrent()
  } catch {
    // 并发摘牌可能失败，原因由后端返回
  } finally {
    saving.value = false
  }
}

async function withdraw(row) {
  await listingApi.withdraw(row.id)
  message.success('已撤牌，冻结资金将退回')
  load()
  acctStore.refreshCurrent()
}

const columns = [
  { title: '挂牌方', key: 'lister', width: 160 },
  { title: '方向', key: 'side', width: 76 },
  { title: '标的', key: 'inst', width: 130 },
  { title: '挂牌价', key: 'price', width: 100, align: 'right' },
  { title: '总量 / 剩余', key: 'volume', width: 160, align: 'right' },
  { title: '最小摘牌量', key: 'minTakeQty', width: 106, align: 'right' },
  { title: '部分摘牌', key: 'allowPartial', width: 86, align: 'center' },
  { title: '有效期至', key: 'validUntil', width: 150 },
  { title: '状态', key: 'status', width: 92 },
  { title: '操作', key: 'op', width: 108, align: 'right' },
]

function isMine(row) {
  return row.accountId === accountId.value
}
function progress(row) {
  if (!row.totalQty) return 0
  return Math.round(((row.totalQty - row.remainQty) / row.totalQty) * 100)
}
</script>

<template>
  <div class="listing-view">
    <PanelBox title="挂牌摘牌大厅" :sub="`共 ${total} 条`" class="main">
      <template #extra>
        <a-button size="small" :loading="loading" @click="load">
          <template #icon><RefreshCw :size="12" /></template>
        </a-button>
        <a-button type="primary" size="small" @click="openCreate">
          <template #icon><Plus :size="12" /></template>我要挂牌
        </a-button>
      </template>

      <div class="bar">
        <Filter :size="13" class="fi" />
        <a-select
          v-model:value="filters.side"
          size="small"
          style="width: 108px"
          placeholder="买卖方向"
          allow-clear
          :options="[
            { value: 'SELL', label: '挂牌售电' },
            { value: 'BUY', label: '挂牌购电' },
          ]"
        />
        <a-select
          v-model:value="filters.instrumentKey"
          size="small"
          style="width: 140px"
          placeholder="品种标的"
          allow-clear
          :options="instrumentOptions"
        />
        <a-select
          v-model:value="filters.status"
          size="small"
          style="width: 118px"
          placeholder="状态"
          allow-clear
          :options="[
            { value: 'LISTED', label: '挂牌中' },
            { value: 'PARTIAL_TAKEN', label: '部分摘牌' },
            { value: 'FULLY_TAKEN', label: '全部摘牌' },
            { value: 'WITHDRAWN', label: '已撤牌' },
            { value: 'EXPIRED', label: '已过期' },
          ]"
        />
        <a-checkbox v-model:checked="filters.mine" :disabled="!accountId" class="ck">只看我挂的</a-checkbox>
        <span class="hint">摘牌即成交，成交价为挂牌价；不可摘自己的牌</span>
      </div>

      <div class="tb ets-scroll">
        <a-table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          :loading="loading"
          row-key="id"
          size="small"
          :scroll="{ x: 1180 }"
        >
          <template #emptyText>
            <EmptyHint
              text="暂无挂牌"
              hint="挂牌摘牌场次开市后，卖方可挂牌售电、买方可挂牌购电，对手方摘牌即成交"
            >
              <template #icon><Tags :size="24" :stroke-width="1.4" /></template>
            </EmptyHint>
          </template>

          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'lister'">
              <div class="two">
                <span>{{ record.accountName || '—' }}<a-tag v-if="isMine(record)" color="blue" :bordered="false">我</a-tag></span>
                <span class="mono sub">{{ record.accountCode }}{{ record.source === 'ROBOT' ? ' · 机器人' : '' }}</span>
              </div>
            </template>
            <template v-else-if="column.key === 'side'">
              <a-tag :color="record.side === 'SELL' ? 'green' : 'red'" :bordered="false">
                {{ record.side === 'SELL' ? '挂牌售电' : '挂牌购电' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'inst'">
              {{ instrumentLabel(`${record.variety}_${record.periodType}`) }}
            </template>
            <template v-else-if="column.key === 'price'">
              <span class="mono" :class="record.side === 'SELL' ? 'down' : 'up'">{{ fp(record.price) }}</span>
            </template>
            <template v-else-if="column.key === 'volume'">
              <div class="vol">
                <span class="mono">{{ fq(record.remainQty) }} / {{ fq(record.totalQty) }}</span>
                <a-progress
                  :percent="progress(record)"
                  size="small"
                  :show-info="false"
                  :stroke-color="record.side === 'SELL' ? '#00b578' : '#f5222d'"
                />
              </div>
            </template>
            <template v-else-if="column.key === 'minTakeQty'">
              <span class="mono">{{ fq(record.minTakeQty) }}</span>
            </template>
            <template v-else-if="column.key === 'allowPartial'">
              <a-tag :color="record.allowPartial ? 'blue' : 'orange'" :bordered="false">
                {{ record.allowPartial ? '允许' : '须整量' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'validUntil'">
              <span class="mono dim">{{ record.validUntil ? datetime(record.validUntil) : '跟随场次闭市' }}</span>
            </template>
            <template v-else-if="column.key === 'status'">
              <StateTag kind="listing" :value="record.status" />
            </template>
            <template v-else-if="column.key === 'op'">
              <a-popconfirm
                v-if="isMine(record) && ['LISTED', 'PARTIAL_TAKEN'].includes(record.status)"
                title="确认撤销该挂牌？"
                ok-text="撤牌"
                cancel-text="取消"
                @confirm="withdraw(record)"
              >
                <a-button type="link" size="small" danger>撤牌</a-button>
              </a-popconfirm>
              <a-button
                v-else-if="['LISTED', 'PARTIAL_TAKEN'].includes(record.status)"
                type="link"
                size="small"
                @click="openTake(record)"
              >
                摘牌
              </a-button>
              <span v-else class="dim">—</span>
            </template>
          </template>
        </a-table>
      </div>

      <div v-if="total > filters.size" class="pager">
        <a-pagination
          v-model:current="filters.page"
          :page-size="filters.size"
          :total="total"
          size="small"
          show-less-items
          :show-size-changer="false"
        />
      </div>
    </PanelBox>

    <!-- 我要挂牌 -->
    <a-modal v-model:open="createOpen" title="发起挂牌" :width="560" :confirm-loading="saving" ok-text="提交挂牌" cancel-text="取消" @ok="submitCreate">
      <a-form ref="createRef" :model="form" layout="vertical">
        <a-form-item label="交易场次" required>
          <a-select
            v-model:value="form.sessionId"
            :options="listingSessions.map((s) => ({ value: s.id, label: `${s.name}（${s.stateLabel}）` }))"
            placeholder="选择挂牌摘牌场次"
          />
          <div v-if="!listingSessions.length" class="hint warn">当前模拟盘尚未开设挂牌摘牌场次</div>
        </a-form-item>

        <a-form-item label="挂牌方向">
          <a-radio-group v-model:value="form.side" button-style="solid">
            <a-radio-button value="SELL">挂牌售电</a-radio-button>
            <a-radio-button value="BUY">挂牌购电</a-radio-button>
          </a-radio-group>
        </a-form-item>

        <div class="grid2">
          <a-form-item label="挂牌价格 (元/MWh)" required>
            <a-input-number v-model:value="form.price" :min="0" :step="formSession?.tickSize || 0.01" :precision="2" />
            <div v-if="formSession" class="hint">
              限价 {{ fp(formSession.priceFloor) }} ~ {{ fp(formSession.priceCap) }}
            </div>
          </a-form-item>
          <a-form-item label="挂牌总量 (MWh)" required>
            <a-input-number v-model:value="form.totalQty" :min="0" :step="formSession?.lotSize || 1" :precision="3" />
          </a-form-item>
          <a-form-item label="最小摘牌量 (MWh)">
            <a-input-number v-model:value="form.minTakeQty" :min="0" :step="formSession?.lotSize || 1" :precision="3" />
            <div class="hint">留空按场次最小交易单位</div>
          </a-form-item>
          <a-form-item label="有效期">
            <a-select
              v-model:value="form.validHours"
              :options="[
                { value: 0, label: '跟随场次闭市' },
                { value: 1, label: '1 小时' },
                { value: 4, label: '4 小时' },
                { value: 12, label: '12 小时' },
                { value: 24, label: '24 小时' },
              ]"
            />
          </a-form-item>
        </div>

        <a-form-item>
          <a-checkbox v-model:checked="form.allowPartial">允许部分摘牌</a-checkbox>
          <div class="hint">关闭后对手方必须一次性摘取全部电量</div>
        </a-form-item>

        <a-form-item label="标题">
          <a-input v-model:value="form.title" placeholder="如：8 月月度电量转让" :maxlength="40" />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model:value="form.remark" :rows="2" placeholder="交割要求、结算方式等补充说明" :maxlength="200" />
        </a-form-item>

        <div class="calc-box">
          <div class="ets-kv"><span>挂牌总金额</span><b class="mono">{{ amount((form.price || 0) * (form.totalQty || 0)) }} 元</b></div>
          <div class="ets-kv">
            <span>{{ form.side === 'BUY' ? '预计冻结货款' : '预计冻结保证金' }}</span>
            <b class="mono">
              {{
                amount(
                  (form.price || 0) *
                    (form.totalQty || 0) *
                    (form.side === 'BUY' ? 1 + (simStore.cfg.feeRateBuy || 0) : simStore.cfg.marginRatio || 0),
                )
              }} 元
            </b>
          </div>
        </div>
      </a-form>
    </a-modal>

    <!-- 摘牌 -->
    <a-modal v-model:open="takeOpen" title="摘牌" :width="460" :confirm-loading="saving" ok-text="确认摘牌" cancel-text="取消" :ok-button-props="{ disabled: !!takeError }" @ok="submitTake">
      <template v-if="takeTarget">
        <div class="take-head">
          <div class="th-l">
            <span class="tt">{{ takeTarget.title || instrumentLabel(`${takeTarget.variety}_${takeTarget.periodType}`) }}</span>
            <span class="ts">
              {{ takeTarget.accountName }}（{{ takeTarget.accountCode }}）
              {{ takeTarget.side === 'SELL' ? '挂牌售电' : '挂牌购电' }}
            </span>
          </div>
          <NumFlash :value="takeTarget.price" strong :flash="false" suffix=" 元/MWh" />
        </div>

        <div class="take-grid">
          <div class="ets-kv"><span>剩余可摘</span><b class="mono">{{ fq(takeTarget.remainQty) }} MWh</b></div>
          <div class="ets-kv"><span>最小摘牌量</span><b class="mono">{{ fq(takeTarget.minTakeQty) }} MWh</b></div>
          <div class="ets-kv"><span>部分摘牌</span><b>{{ takeTarget.allowPartial ? '允许' : '不允许，须整量' }}</b></div>
          <div class="ets-kv"><span>我方成为</span><b :class="takeTarget.side === 'SELL' ? 'up' : 'down'">{{ takeTarget.side === 'SELL' ? '购电方' : '售电方' }}</b></div>
        </div>

        <div class="take-input">
          <span class="ets-label">摘牌电量 (MWh)</span>
          <a-input-number
            v-model:value="takeQty"
            :min="0"
            :max="takeTarget.remainQty"
            :precision="3"
            size="large"
            :status="takeError ? 'error' : ''"
            style="width: 100%"
          />
          <div v-if="takeError" class="hint err">{{ takeError }}</div>
          <div v-else class="hint">
            成交金额 <b class="mono">{{ amount(takeNotional) }}</b> 元，成交价即挂牌价，摘牌后立即生成购售电合同
          </div>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<style scoped>
.listing-view {
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 8px;
}
.main {
  flex: 1;
  min-height: 0;
}
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ets-line);
  flex-wrap: wrap;
  flex: none;
}
.fi {
  color: var(--ets-text-faint);
}
.ck {
  font-size: 12px !important;
  color: var(--ets-text-weak);
}
.hint {
  font-size: 11px;
  color: var(--ets-text-faint);
  line-height: 1.6;
  margin-top: 3px;
}
.bar .hint {
  margin-left: auto;
  margin-top: 0;
}
.hint.warn {
  color: var(--ets-warn);
}
.hint.err {
  color: var(--ets-up);
}
.tb {
  flex: 1;
  min-height: 0;
}
.pager {
  padding: 8px 10px;
  border-top: 1px solid var(--ets-line);
  display: flex;
  justify-content: flex-end;
  flex: none;
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
.dim {
  color: var(--ets-text-faint);
}
.vol {
  display: flex;
  flex-direction: column;
  gap: 1px;
  align-items: flex-end;
}
.vol :deep(.ant-progress) {
  margin: 0;
  line-height: 1;
  width: 90px;
}

.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.calc-box {
  padding: 8px 10px;
  border-radius: 5px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}

.take-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ets-line);
}
.th-l {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.tt {
  font-size: 13px;
  font-weight: 600;
  color: var(--ets-text-strong);
}
.ts {
  font-size: 11px;
  color: var(--ets-text-faint);
}
.take-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
  padding: 10px 0;
}
.take-grid b {
  font-weight: 600;
}
.take-input {
  padding-top: 8px;
  border-top: 1px dashed var(--ets-line);
}
</style>
