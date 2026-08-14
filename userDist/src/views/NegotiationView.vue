<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Handshake, MessageSquarePlus, RefreshCw, Send } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import StateTag from '@/components/common/StateTag.vue'
import { negoApi } from '@/api/user'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { useMarketSocket } from '@/composables/useMarketSocket'
import { usePolling } from '@/composables/usePolling'
import { NEGO_ACTION, instrumentLabel } from '@/utils/enums'
import { amount, datetime, price as fp, qty as fq, time } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

const list = ref([])
const listLoading = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const activeId = ref('')
const role = ref('all')
const statusFilter = ref(undefined)
const acting = ref('')
const bodyRef = ref(null)

const proposeOpen = ref(false)
const counterOpen = ref(false)
const counterparties = ref([])
const saving = ref(false)

const accountId = computed(() => acctStore.currentId)
const bilateralSessions = computed(() => simStore.sessionsOfMode('BILATERAL'))

const propose = reactive({
  counterpartyAccountId: undefined,
  sessionId: undefined,
  side: 'BUY',
  price: null,
  quantity: null,
  message: '',
  expireMinutes: 30,
})

const counterForm = reactive({ price: null, quantity: null, message: '' })

async function loadList() {
  if (!accountId.value) {
    list.value = []
    return
  }
  listLoading.value = true
  try {
    const res = await negoApi.list({
      accountId: accountId.value,
      role: role.value,
      status: statusFilter.value,
      page: 1,
      size: 100,
    })
    list.value = res?.list || []
    if (!activeId.value || !list.value.some((x) => x.id === activeId.value)) {
      activeId.value = list.value[0]?.id || ''
    }
  } finally {
    listLoading.value = false
  }
}

async function loadDetail() {
  if (!activeId.value) {
    detail.value = null
    return
  }
  detailLoading.value = true
  try {
    detail.value = await negoApi.detail(activeId.value)
    await nextTick()
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  } finally {
    detailLoading.value = false
  }
}

watch([accountId, role, statusFilter], loadList, { immediate: true })
watch(activeId, loadDetail)

usePolling(loadList, 12_000, { immediate: false })

useMarketSocket({
  nego: (data) => {
    if (!data?.id) return
    const i = list.value.findIndex((x) => x.id === data.id)
    if (i >= 0) list.value[i] = data
    else list.value = [data, ...list.value]
    if (data.id === activeId.value) detail.value = data
  },
})

// ── 权限判定 ───────────────────────────────────────
const isActive = computed(() => ['PROPOSED', 'COUNTERED'].includes(detail.value?.status))
const myTurn = computed(() => isActive.value && detail.value?.currentTurnAccountId === accountId.value)
const canWithdraw = computed(() => isActive.value && detail.value?.currentTurnAccountId !== accountId.value)
const roundsLeft = computed(() => 10 - (detail.value?.rounds?.length || 0))

const mySide = computed(() => {
  const n = detail.value
  if (!n) return null
  return n.initiatorAccountId === accountId.value ? n.initiatorSide : n.initiatorSide === 'BUY' ? 'SELL' : 'BUY'
})

function otherOf(n) {
  if (!n) return {}
  const iAmInitiator = n.initiatorAccountId === accountId.value
  return {
    name: iAmInitiator ? n.counterpartyAccountName : n.initiatorAccountName,
    code: iAmInitiator ? n.counterpartyAccountCode : n.initiatorAccountCode,
    iAmInitiator,
  }
}

function sideOfRow(n) {
  const iAmInitiator = n.initiatorAccountId === accountId.value
  const s = iAmInitiator ? n.initiatorSide : n.initiatorSide === 'BUY' ? 'SELL' : 'BUY'
  return s
}

// ── 动作 ───────────────────────────────────────────
async function act(kind) {
  if (!detail.value) return
  acting.value = kind
  try {
    const body = { accountId: accountId.value }
    if (kind === 'accept') {
      detail.value = await negoApi.accept(detail.value.id, body)
      message.success('已接受报价，购售电合同已生成')
    } else if (kind === 'reject') {
      detail.value = await negoApi.reject(detail.value.id, { ...body, message: '暂不接受该报价' })
      message.success('已拒绝本次协商')
    } else if (kind === 'withdraw') {
      detail.value = await negoApi.withdraw(detail.value.id, body)
      message.success('已撤回邀约')
    }
    loadList()
    acctStore.refreshCurrent()
  } catch {
    // 状态冲突或资金不足由后端返回中文原因
  } finally {
    acting.value = ''
  }
}

function openCounter() {
  counterForm.price = detail.value?.price
  counterForm.quantity = detail.value?.quantity
  counterForm.message = ''
  counterOpen.value = true
}

async function submitCounter() {
  if (!counterForm.price) {
    message.warning('请填写还价价格')
    return
  }
  saving.value = true
  try {
    detail.value = await negoApi.counter(detail.value.id, {
      accountId: accountId.value,
      price: Number(counterForm.price),
      quantity: Number(counterForm.quantity || 0),
      message: counterForm.message,
    })
    message.success('还价已发出，等待对手方回应')
    counterOpen.value = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function openPropose() {
  if (!accountId.value) {
    message.warning('请先选择交易账户')
    return
  }
  const sess = bilateralSessions.value.find((s) => s.state === 'OPEN') || bilateralSessions.value[0]
  propose.sessionId = sess?.id
  propose.side = acctStore.current?.memberType === 'GENCO' ? 'SELL' : 'BUY'
  propose.price = sess?.lastPrice || sess?.prevClose || simStore.cfg.benchmarkPrice
  propose.quantity = null
  propose.message = ''
  propose.expireMinutes = 30
  propose.counterpartyAccountId = undefined
  proposeOpen.value = true
  try {
    counterparties.value = (await negoApi.counterparties(simStore.currentSimId, accountId.value)) || []
  } catch {
    counterparties.value = []
  }
}

async function submitPropose() {
  if (!propose.counterpartyAccountId || !propose.sessionId || !propose.price || !propose.quantity) {
    message.warning('请填写对手方、场次、价格与电量')
    return
  }
  saving.value = true
  try {
    const n = await negoApi.propose({
      accountId: accountId.value,
      counterpartyAccountId: propose.counterpartyAccountId,
      sessionId: propose.sessionId,
      side: propose.side,
      price: Number(propose.price),
      quantity: Number(propose.quantity),
      message: propose.message,
      expireMinutes: propose.expireMinutes,
    })
    message.success('邀约已发出')
    proposeOpen.value = false
    await loadList()
    activeId.value = n?.id || activeId.value
  } finally {
    saving.value = false
  }
}

const counterpartyOptions = computed(() =>
  counterparties.value.map((c) => ({
    value: c.accountId,
    label: `${c.code} · ${c.name}（${c.memberTypeLabel}${c.source === 'ROBOT' ? ' · 机器人' : ''}）`,
  })),
)
</script>

<template>
  <div class="nego-view">
    <!-- 左：会话列表 -->
    <PanelBox class="col-list" title="协商会话" :sub="`${list.length} 条`">
      <template #extra>
        <a-button size="small" :loading="listLoading" @click="loadList"><RefreshCw :size="12" /></a-button>
        <a-button type="primary" size="small" @click="openPropose">
          <template #icon><MessageSquarePlus :size="12" /></template>发起
        </a-button>
      </template>

      <div class="filters">
        <a-radio-group v-model:value="role" size="small" button-style="solid">
          <a-radio-button value="all">全部</a-radio-button>
          <a-radio-button value="initiator">我发起的</a-radio-button>
          <a-radio-button value="counterparty">发给我的</a-radio-button>
        </a-radio-group>
        <a-select
          v-model:value="statusFilter"
          size="small"
          style="width: 100%"
          placeholder="全部状态"
          allow-clear
          :options="[
            { value: 'PROPOSED', label: '已发起' },
            { value: 'COUNTERED', label: '已还价' },
            { value: 'ACCEPTED', label: '已达成' },
            { value: 'REJECTED', label: '已拒绝' },
            { value: 'WITHDRAWN', label: '已撤回' },
            { value: 'EXPIRED', label: '已过期' },
          ]"
        />
      </div>

      <div class="sessions ets-scroll">
        <EmptyHint
          v-if="!list.length && !listLoading"
          compact
          text="暂无协商会话"
          hint="点击「发起」向同盘的其它市场主体发出购售电邀约"
        />
        <div
          v-for="n in list"
          :key="n.id"
          class="sess"
          :class="{ on: n.id === activeId, turn: n.currentTurnAccountId === accountId }"
          @click="activeId = n.id"
        >
          <div class="s1">
            <span class="who">{{ otherOf(n).name || otherOf(n).code || '—' }}</span>
            <StateTag kind="nego" :value="n.status" />
          </div>
          <div class="s2">
            <a-tag :color="sideOfRow(n) === 'BUY' ? 'red' : 'green'" :bordered="false">
              {{ sideOfRow(n) === 'BUY' ? '我方购电' : '我方售电' }}
            </a-tag>
            <span class="mono pq">{{ fp(n.price) }} × {{ fq(n.quantity) }}</span>
          </div>
          <div class="s3">
            <span>{{ instrumentLabel(`${n.variety}_${n.periodType}`) }}</span>
            <span class="mono">{{ time(n.updatedAt || n.createdAt) }}</span>
          </div>
          <span v-if="n.currentTurnAccountId === accountId" class="badge">待我回应</span>
        </div>
      </div>
    </PanelBox>

    <!-- 右：报价往来时间线 -->
    <PanelBox class="col-chat">
      <template #title />
      <div v-if="!detail" class="no-sel">
        <EmptyHint
          text="选择一个协商会话"
          hint="双边协商采用轮流还价机制，最多 10 轮。达成一致后立即生成购售电合同，不进入公开订单簿。"
        >
          <template #icon><Handshake :size="26" :stroke-width="1.4" /></template>
          <template #action>
            <a-button type="primary" size="small" @click="openPropose">发起协商</a-button>
          </template>
        </EmptyHint>
      </div>

      <template v-else>
        <header class="chat-head">
          <div class="ch-l">
            <span class="ch-name">{{ otherOf(detail).name }}</span>
            <span class="mono ch-code">{{ otherOf(detail).code }}</span>
            <StateTag kind="nego" :value="detail.status" />
            <a-tag :bordered="false" :color="mySide === 'BUY' ? 'red' : 'green'">
              我方{{ mySide === 'BUY' ? '购电' : '售电' }}
            </a-tag>
          </div>
          <div class="ch-r">
            <span>{{ instrumentLabel(`${detail.variety}_${detail.periodType}`) }}</span>
            <span v-if="detail.deliveryStart" class="mono">交割 {{ detail.deliveryStart }} ~ {{ detail.deliveryEnd }}</span>
            <span>第 {{ detail.rounds?.length || 0 }} / 10 轮</span>
            <span v-if="isActive && detail.expireAt" class="mono warn">失效 {{ datetime(detail.expireAt) }}</span>
          </div>
        </header>

        <div class="current">
          <div class="cur-item">
            <span>当前报价</span>
            <b class="mono big" :class="mySide === 'BUY' ? 'up' : 'down'">{{ fp(detail.price) }}</b>
            <i>元/MWh</i>
          </div>
          <div class="cur-item">
            <span>协商电量</span>
            <b class="mono big">{{ fq(detail.quantity) }}</b>
            <i>MWh</i>
          </div>
          <div class="cur-item">
            <span>合同金额</span>
            <b class="mono big">{{ amount(detail.price * detail.quantity) }}</b>
            <i>元</i>
          </div>
          <div class="cur-item">
            <span>当前轮到</span>
            <b :class="myTurn ? 'up' : ''">{{ myTurn ? '我方回应' : isActive ? '对手方回应' : '已结束' }}</b>
          </div>
        </div>

        <div ref="bodyRef" class="chat-body ets-scroll">
          <a-spin :spinning="detailLoading">
            <div
              v-for="r in detail.rounds || []"
              :key="`${r.round}-${r.at}`"
              class="bubble-row"
              :class="{ me: r.byAccountId === accountId }"
            >
              <div class="bubble" :class="r.action">
                <div class="b-head">
                  <span class="b-who">{{ r.byAccountId === accountId ? '我方' : otherOf(detail).name }}</span>
                  <span class="mono b-code">{{ r.byAccountCode }}</span>
                  <a-tag :bordered="false" class="b-act">第 {{ r.round }} 轮 · {{ NEGO_ACTION[r.action] || r.action }}</a-tag>
                </div>
                <div class="b-quote">
                  <span class="q">
                    <i>价格</i><b class="mono">{{ fp(r.price) }}</b>
                  </span>
                  <span class="q">
                    <i>电量</i><b class="mono">{{ fq(r.quantity) }}</b>
                  </span>
                  <span class="q">
                    <i>金额</i><b class="mono">{{ amount(r.price * r.quantity) }}</b>
                  </span>
                </div>
                <div v-if="r.message" class="b-msg">{{ r.message }}</div>
                <div class="b-time mono">{{ datetime(r.at) }}</div>
              </div>
            </div>
          </a-spin>
        </div>

        <footer class="chat-foot">
          <div v-if="!isActive" class="closed">
            本次协商已{{ detail.status === 'ACCEPTED' ? '达成' : '结束' }}
            <template v-if="detail.contractId">
              ，合同编号 <b class="mono">{{ detail.contractId }}</b>
            </template>
          </div>
          <template v-else>
            <span class="turn-tip" :class="{ mine: myTurn }">
              {{ myTurn ? `轮到我方回应，还剩 ${roundsLeft} 轮还价机会` : '已发出报价，等待对手方回应' }}
            </span>
            <div class="acts">
              <a-button size="small" :disabled="!canWithdraw" :loading="acting === 'withdraw'" @click="act('withdraw')">
                撤回
              </a-button>
              <a-button size="small" danger :disabled="!myTurn" :loading="acting === 'reject'" @click="act('reject')">
                拒绝
              </a-button>
              <a-button size="small" :disabled="!myTurn || roundsLeft <= 0" @click="openCounter">
                <template #icon><Send :size="12" /></template>还价
              </a-button>
              <a-popconfirm
                title="接受该报价将立即成交并生成购售电合同，确认？"
                ok-text="接受成交"
                cancel-text="再想想"
                :disabled="!myTurn"
                @confirm="act('accept')"
              >
                <a-button type="primary" size="small" :disabled="!myTurn" :loading="acting === 'accept'">
                  接受
                </a-button>
              </a-popconfirm>
            </div>
          </template>
        </footer>
      </template>
    </PanelBox>

    <!-- 发起协商 -->
    <a-modal v-model:open="proposeOpen" title="发起双边协商" :width="540" :confirm-loading="saving" ok-text="发出邀约" cancel-text="取消" @ok="submitPropose">
      <a-form layout="vertical">
        <a-form-item label="对手方" required>
          <a-select
            v-model:value="propose.counterpartyAccountId"
            show-search
            option-filter-prop="label"
            :options="counterpartyOptions"
            placeholder="选择同一模拟盘下的其它市场主体"
          />
        </a-form-item>
        <a-form-item label="交易场次" required>
          <a-select
            v-model:value="propose.sessionId"
            :options="bilateralSessions.map((s) => ({ value: s.id, label: `${s.name}（${s.stateLabel}）` }))"
            placeholder="选择双边协商场次"
          />
          <div v-if="!bilateralSessions.length" class="hint warn">当前模拟盘尚未开设双边协商场次</div>
        </a-form-item>
        <a-form-item label="我方方向">
          <a-radio-group v-model:value="propose.side" button-style="solid">
            <a-radio-button value="BUY">我方购电</a-radio-button>
            <a-radio-button value="SELL">我方售电</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <div class="grid3">
          <a-form-item label="报价 (元/MWh)" required>
            <a-input-number v-model:value="propose.price" :min="0" :precision="2" />
          </a-form-item>
          <a-form-item label="电量 (MWh)" required>
            <a-input-number v-model:value="propose.quantity" :min="0" :precision="3" />
          </a-form-item>
          <a-form-item label="有效期 (分钟)">
            <a-input-number v-model:value="propose.expireMinutes" :min="5" :max="1440" :precision="0" />
          </a-form-item>
        </div>
        <a-form-item label="附言">
          <a-textarea v-model:value="propose.message" :rows="2" placeholder="说明交割安排、价格依据等" :maxlength="200" />
        </a-form-item>
        <div class="calc-box">
          <div class="ets-kv">
            <span>合同金额</span>
            <b class="mono">{{ amount((propose.price || 0) * (propose.quantity || 0)) }} 元</b>
          </div>
          <div class="hint">双边成交不进入公开订单簿、不影响最新价，但计入成交量统计</div>
        </div>
      </a-form>
    </a-modal>

    <!-- 还价 -->
    <a-modal v-model:open="counterOpen" title="还价" :width="440" :confirm-loading="saving" ok-text="发出还价" cancel-text="取消" @ok="submitCounter">
      <div class="grid2">
        <a-form-item label="还价价格 (元/MWh)">
          <a-input-number v-model:value="counterForm.price" :min="0" :precision="2" />
        </a-form-item>
        <a-form-item label="电量 (MWh)">
          <a-input-number v-model:value="counterForm.quantity" :min="0" :precision="3" />
        </a-form-item>
      </div>
      <a-form-item label="附言">
        <a-textarea v-model:value="counterForm.message" :rows="2" placeholder="给出还价理由更容易达成" :maxlength="200" />
      </a-form-item>
      <div class="calc-box">
        <div class="ets-kv">
          <span>还价后金额</span>
          <b class="mono">{{ amount((counterForm.price || 0) * (counterForm.quantity || 0)) }} 元</b>
        </div>
        <div class="hint">还价后轮到对手方回应，剩余 {{ roundsLeft - 1 }} 轮</div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.nego-view {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 292px minmax(0, 1fr);
  gap: 8px;
  padding: 8px;
}
.col-list,
.col-chat {
  min-height: 0;
}

.filters {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 9px;
  border-bottom: 1px solid var(--ets-line);
  flex: none;
}
.filters :deep(.ant-radio-button-wrapper) {
  font-size: 11px;
  padding-inline: 9px;
}

.sessions {
  flex: 1;
  min-height: 0;
}
.sess {
  position: relative;
  padding: 9px 10px;
  border-bottom: 1px solid var(--ets-line-soft);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.sess:hover {
  background: var(--ets-hover);
}
.sess.on {
  background: var(--ets-accent-soft);
  box-shadow: inset 2px 0 0 var(--ets-accent);
}
.s1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.who {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ets-text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.s2 {
  display: flex;
  align-items: center;
  gap: 5px;
}
.pq {
  font-size: 11px;
  color: var(--ets-text-weak);
}
.s3 {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--ets-text-faint);
}
.badge {
  position: absolute;
  right: 10px;
  bottom: 8px;
  padding: 0 5px;
  height: 15px;
  line-height: 15px;
  border-radius: 3px;
  background: var(--ets-up);
  color: #fff;
  font-size: 9px;
}

.col-chat :deep(.ets-panel-title) {
  display: none;
}
.no-sel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--ets-line);
  flex-wrap: wrap;
  flex: none;
}
.ch-l {
  display: flex;
  align-items: center;
  gap: 7px;
}
.ch-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.ch-code {
  font-size: 11px;
  color: var(--ets-text-faint);
}
.ch-r {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--ets-text-faint);
  flex-wrap: wrap;
}
.ch-r .warn {
  color: var(--ets-warn);
}

.current {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding: 10px 12px;
  background: var(--ets-raise);
  border-bottom: 1px solid var(--ets-line);
  flex: none;
}
.cur-item {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.cur-item > span {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.cur-item b {
  font-size: 13px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.cur-item b.big {
  font-size: 17px;
}
.cur-item i {
  font-style: normal;
  font-size: 10px;
  color: var(--ets-text-faint);
}

.chat-body {
  flex: 1;
  min-height: 0;
  padding: 14px 16px;
  background: repeating-linear-gradient(180deg, transparent 0 27px, rgba(255, 255, 255, 0.012) 27px 28px);
}
.bubble-row {
  display: flex;
  margin-bottom: 12px;
}
.bubble-row.me {
  justify-content: flex-end;
}
.bubble {
  max-width: 460px;
  min-width: 240px;
  padding: 9px 11px;
  border-radius: 8px;
  background: var(--ets-panel);
  border: 1px solid var(--ets-line);
  border-left: 2px solid #3a4757;
}
.bubble-row.me .bubble {
  background: rgba(47, 127, 255, 0.09);
  border-color: rgba(47, 127, 255, 0.3);
  border-left-color: var(--ets-accent);
}
.bubble.ACCEPT {
  border-left-color: var(--ets-down);
}
.bubble.REJECT,
.bubble.WITHDRAW {
  border-left-color: var(--ets-up);
}
.b-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.b-who {
  font-size: 12px;
  font-weight: 600;
  color: var(--ets-text-strong);
}
.b-code {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.b-act {
  margin-left: auto !important;
  font-size: 10px !important;
}
.b-quote {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.b-quote .q {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.b-quote i {
  font-style: normal;
  font-size: 10px;
  color: var(--ets-text-faint);
}
.b-quote b {
  font-size: 13px;
  color: var(--ets-text-strong);
}
.b-msg {
  margin-top: 7px;
  padding: 6px 8px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.03);
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ets-text);
}
.b-time {
  margin-top: 6px;
  font-size: 10px;
  color: var(--ets-text-faint);
  text-align: right;
}

.chat-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  border-top: 1px solid var(--ets-line);
  background: var(--ets-panel);
  flex: none;
}
.turn-tip {
  font-size: 11.5px;
  color: var(--ets-text-faint);
}
.turn-tip.mine {
  color: var(--ets-warn);
  font-weight: 600;
}
.acts {
  display: flex;
  gap: 6px;
  margin-left: auto;
}
.closed {
  font-size: 12px;
  color: var(--ets-text-weak);
}

.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.grid3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 12px;
}
.hint {
  font-size: 11px;
  color: var(--ets-text-faint);
  margin-top: 3px;
  line-height: 1.6;
}
.hint.warn {
  color: var(--ets-warn);
}
.calc-box {
  padding: 8px 10px;
  border-radius: 5px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}

@media (max-width: 1180px) {
  .nego-view {
    grid-template-columns: 240px minmax(0, 1fr);
  }
  .current {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
