<script setup>
import { computed, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { Ban, RefreshCw } from 'lucide-vue-next'
import EmptyHint from '@/components/common/EmptyHint.vue'
import StateTag from '@/components/common/StateTag.vue'
import { orderApi } from '@/api/user'
import { useAccountStore } from '@/stores/account'
import { instrumentLabel } from '@/utils/enums'
import { amount, datetime, price, qty, signed, time } from '@/utils/format'

const props = defineProps({
  /** 当前终端选中的场次，为空表示没有可聚焦的场次 */
  sessionId: { type: String, default: '' },
})

const acctStore = useAccountStore()
const tab = ref('orders')
const loading = ref(false)
const orders = ref([])
const trades = ref([])
const onlyActive = ref(true)
/** 默认看该账户的全部场次，勾选后收敛到当前标的所在场次 */
const scopeSession = ref(false)

const accountId = computed(() => acctStore.currentId)
const scoped = computed(() => scopeSession.value && !!props.sessionId)
const params = computed(() => ({
  accountId: accountId.value,
  sessionId: scoped.value ? props.sessionId : undefined,
  page: 1,
  size: 200,
}))

const shownOrders = computed(() =>
  onlyActive.value ? orders.value.filter((o) => ['ACTIVE', 'PARTIAL'].includes(o.status)) : orders.value,
)
const activeCount = computed(() => orders.value.filter((o) => ['ACTIVE', 'PARTIAL'].includes(o.status)).length)

async function load() {
  if (!accountId.value) {
    orders.value = []
    trades.value = []
    return
  }
  loading.value = true
  try {
    if (tab.value === 'orders') {
      orders.value = (await orderApi.list(params.value))?.list || []
    } else if (tab.value === 'trades') {
      trades.value = (await orderApi.trades(params.value))?.list || []
    } else {
      await acctStore.loadPositions()
    }
  } finally {
    loading.value = false
  }
}

watch([tab, accountId, () => props.sessionId, scopeSession], load, { immediate: true })

defineExpose({ reload: load })

async function cancel(row) {
  await orderApi.cancel(row.id)
  message.success('撤单指令已提交')
  load()
  acctStore.refreshCurrent()
}

/** 后端 cancel-all 必须指定场次，跨场次批撤时逐场次调用 */
const activeSessionIds = computed(() => [
  ...new Set(
    orders.value.filter((o) => ['ACTIVE', 'PARTIAL'].includes(o.status) && o.sessionId).map((o) => o.sessionId),
  ),
])

function cancelAll() {
  if (!activeCount.value) {
    message.info('当前没有可撤销的挂单')
    return
  }
  const targets = scoped.value ? [props.sessionId] : activeSessionIds.value
  Modal.confirm({
    title: '批量撤单',
    content: `将撤销${scoped.value ? '本场次' : `${targets.length} 个场次`}全部 ${activeCount.value} 笔未成交申报，确认执行？`,
    okText: '全部撤单',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: async () => {
      const results = await Promise.allSettled(
        targets.map((sid) => orderApi.cancelAll({ accountId: accountId.value, sessionId: sid })),
      )
      const n = results.reduce((sum, r) => sum + (r.status === 'fulfilled' ? r.value || 0 : 0), 0)
      message.success(`已撤销 ${n} 笔申报`)
      load()
      acctStore.refreshCurrent()
    },
  })
}

const orderColumns = [
  { title: '时间', key: 'createdAt', width: 78 },
  { title: '场次 / 标的', key: 'inst', width: 190 },
  { title: '方向', key: 'side', width: 62 },
  { title: '申报价', key: 'price', width: 92, align: 'right' },
  { title: '申报量', key: 'quantity', width: 96, align: 'right' },
  { title: '已成交', key: 'filledQty', width: 96, align: 'right' },
  { title: '成交均价', key: 'avgFillPrice', width: 92, align: 'right' },
  { title: '冻结金额', key: 'frozenAmount', width: 112, align: 'right' },
  { title: '有效期', key: 'tif', width: 58 },
  { title: '状态', key: 'status', width: 96 },
  { title: '操作', key: 'op', width: 68, align: 'right' },
]

const tradeColumns = [
  { title: '成交时间', key: 'tradedAt', width: 150 },
  { title: '标的', key: 'inst', width: 150 },
  { title: '方向', key: 'side', width: 62 },
  { title: '成交价', key: 'price', width: 92, align: 'right' },
  { title: '成交量', key: 'quantity', width: 96, align: 'right' },
  { title: '成交额', key: 'amount', width: 120, align: 'right' },
  { title: '手续费', key: 'fee', width: 96, align: 'right' },
  { title: '交易方式', key: 'mode', width: 110 },
  { title: '对手方', key: 'cp', width: 110 },
]

const posColumns = [
  { title: '标的', key: 'inst', width: 160 },
  { title: '净持仓', key: 'netQty', width: 110, align: 'right' },
  { title: '持仓均价', key: 'avgPrice', width: 100, align: 'right' },
  { title: '最新价', key: 'lastPrice', width: 100, align: 'right' },
  { title: '累计购电', key: 'buyQty', width: 110, align: 'right' },
  { title: '累计售电', key: 'sellQty', width: 110, align: 'right' },
  { title: '购电均价', key: 'avgBuy', width: 100, align: 'right' },
  { title: '售电均价', key: 'avgSell', width: 100, align: 'right' },
  { title: '浮动盈亏', key: 'unrealizedPnl', width: 120, align: 'right' },
]

function isBuyer(t) {
  return t.buyAccountId === accountId.value
}
function avgOf(a, b) {
  return b > 0 ? a / b : 0
}
</script>

<template>
  <div class="my-orders">
    <a-tabs v-model:activeKey="tab" size="small" class="tabs">
      <a-tab-pane key="orders">
        <template #tab>
          我的委托
          <a-badge v-if="activeCount" :count="activeCount" :offset="[3, -8]" :number-style="{ background: '#2f7fff' }" />
        </template>
      </a-tab-pane>
      <a-tab-pane key="trades" tab="我的成交" />
      <a-tab-pane key="positions" tab="我的持仓" />
      <template #rightExtra>
        <div class="extra">
          <a-checkbox v-if="sessionId && tab !== 'positions'" v-model:checked="scopeSession" class="ck">
            仅本场次
          </a-checkbox>
          <a-checkbox v-if="tab === 'orders'" v-model:checked="onlyActive" class="ck">只看未完成</a-checkbox>
          <a-button v-if="tab === 'orders'" size="small" danger :disabled="!activeCount" @click="cancelAll">
            <template #icon><Ban :size="12" /></template>全部撤单
          </a-button>
          <a-button size="small" :loading="loading" @click="load">
            <template #icon><RefreshCw :size="12" /></template>
          </a-button>
        </div>
      </template>
    </a-tabs>

    <div class="tb ets-scroll">
      <EmptyHint v-if="!accountId" compact text="请先选择交易账户" />

      <!-- 委托 -->
      <a-table
        v-else-if="tab === 'orders'"
        :columns="orderColumns"
        :data-source="shownOrders"
        :pagination="false"
        :loading="loading"
        row-key="id"
        size="small"
        :scroll="{ x: 1080 }"
      >
        <template #emptyText>
          <EmptyHint compact text="暂无委托" hint="在右侧下单面板提交申报后，委托会实时出现在这里" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            <span class="mono dim">{{ time(record.createdAt) }}</span>
          </template>
          <template v-else-if="column.key === 'inst'">
            <div class="two">
              <span>{{ record.sessionName || '—' }}</span>
              <span class="mono sub">{{ instrumentLabel(record.instrumentKey) }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'side'">
            <span :class="record.side === 'BUY' ? 'up' : 'down'">{{ record.side === 'BUY' ? '买入' : '卖出' }}</span>
          </template>
          <template v-else-if="column.key === 'price'">
            <span class="mono" :class="record.side === 'BUY' ? 'up' : 'down'">{{ price(record.price) }}</span>
          </template>
          <template v-else-if="column.key === 'quantity'"><span class="mono">{{ qty(record.quantity) }}</span></template>
          <template v-else-if="column.key === 'filledQty'"><span class="mono">{{ qty(record.filledQty) }}</span></template>
          <template v-else-if="column.key === 'avgFillPrice'">
            <span class="mono">{{ record.filledQty ? price(record.avgFillPrice) : '—' }}</span>
          </template>
          <template v-else-if="column.key === 'frozenAmount'"><span class="mono">{{ amount(record.frozenAmount) }}</span></template>
          <template v-else-if="column.key === 'tif'"><span class="mono dim">{{ record.timeInForce }}</span></template>
          <template v-else-if="column.key === 'status'">
            <a-tooltip :title="record.rejectReason">
              <StateTag kind="order" :value="record.status" :label="record.statusLabel" />
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-popconfirm
              v-if="['ACTIVE', 'PARTIAL'].includes(record.status)"
              title="确认撤销该笔申报？"
              ok-text="撤单"
              cancel-text="取消"
              @confirm="cancel(record)"
            >
              <a-button type="link" size="small" danger>撤单</a-button>
            </a-popconfirm>
            <span v-else class="dim">—</span>
          </template>
        </template>
      </a-table>

      <!-- 成交 -->
      <a-table
        v-else-if="tab === 'trades'"
        :columns="tradeColumns"
        :data-source="trades"
        :pagination="false"
        :loading="loading"
        row-key="id"
        size="small"
        :scroll="{ x: 1000 }"
      >
        <template #emptyText>
          <EmptyHint compact text="暂无成交" hint="申报被撮合后会生成成交记录与购售电合同" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'tradedAt'"><span class="mono dim">{{ datetime(record.tradedAt) }}</span></template>
          <template v-else-if="column.key === 'inst'">{{ instrumentLabel(record.instrumentKey || `${record.variety}_${record.periodType}`) }}</template>
          <template v-else-if="column.key === 'side'">
            <span :class="isBuyer(record) ? 'up' : 'down'">{{ isBuyer(record) ? '买入' : '卖出' }}</span>
          </template>
          <template v-else-if="column.key === 'price'">
            <span class="mono" :class="isBuyer(record) ? 'up' : 'down'">{{ price(record.price) }}</span>
          </template>
          <template v-else-if="column.key === 'quantity'"><span class="mono">{{ qty(record.quantity) }}</span></template>
          <template v-else-if="column.key === 'amount'"><span class="mono">{{ amount(record.amount) }}</span></template>
          <template v-else-if="column.key === 'fee'">
            <span class="mono dim">{{ amount(isBuyer(record) ? record.buyFee : record.sellFee) }}</span>
          </template>
          <template v-else-if="column.key === 'mode'">
            <StateTag kind="mode" :value="record.tradeMode" />
          </template>
          <template v-else-if="column.key === 'cp'">
            <span class="mono dim">{{ isBuyer(record) ? record.sellAccountCode : record.buyAccountCode }}</span>
          </template>
        </template>
      </a-table>

      <!-- 持仓 -->
      <a-table
        v-else
        :columns="posColumns"
        :data-source="acctStore.positions"
        :pagination="false"
        :loading="loading"
        row-key="instrumentKey"
        size="small"
        :scroll="{ x: 960 }"
      >
        <template #emptyText>
          <EmptyHint compact text="暂无持仓" hint="持仓按标的聚合，净持仓为正代表持有购电合约" />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'inst'">{{ instrumentLabel(record.instrumentKey) }}</template>
          <template v-else-if="column.key === 'netQty'">
            <span class="mono" :class="record.netQty > 0 ? 'up' : record.netQty < 0 ? 'down' : ''">
              {{ signed(record.netQty, qty) }}
            </span>
          </template>
          <template v-else-if="column.key === 'avgPrice'"><span class="mono">{{ price(record.avgPrice) }}</span></template>
          <template v-else-if="column.key === 'lastPrice'"><span class="mono">{{ price(record.lastPrice) }}</span></template>
          <template v-else-if="column.key === 'buyQty'"><span class="mono up">{{ qty(record.buyQty) }}</span></template>
          <template v-else-if="column.key === 'sellQty'"><span class="mono down">{{ qty(record.sellQty) }}</span></template>
          <template v-else-if="column.key === 'avgBuy'"><span class="mono">{{ price(avgOf(record.buyAmount, record.buyQty)) }}</span></template>
          <template v-else-if="column.key === 'avgSell'"><span class="mono">{{ price(avgOf(record.sellAmount, record.sellQty)) }}</span></template>
          <template v-else-if="column.key === 'unrealizedPnl'">
            <span class="mono" :class="record.unrealizedPnl > 0 ? 'up' : record.unrealizedPnl < 0 ? 'down' : ''">
              {{ signed(record.unrealizedPnl) }}
            </span>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<style scoped>
.my-orders {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.tabs {
  flex: none;
  padding: 0 10px;
  border-bottom: 1px solid var(--ets-line);
}
.tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0 !important;
}
.extra {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ck {
  font-size: 11px !important;
  color: var(--ets-text-weak);
}
.tb {
  flex: 1;
  min-height: 0;
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
</style>
