<script setup>
import { computed, ref, watch } from 'vue'
import { RefreshCw, Wallet } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import NumFlash from '@/components/common/NumFlash.vue'
import StateTag from '@/components/common/StateTag.vue'
import { accountApi, assetApi } from '@/api/user'
import { useAccountStore } from '@/stores/account'
import { useSimStore } from '@/stores/sim'
import { usePolling } from '@/composables/usePolling'
import { FLOW_TYPE, instrumentLabel } from '@/utils/enums'
import { amount, datetime, price, qty, signed } from '@/utils/format'

const acctStore = useAccountStore()
const simStore = useSimStore()

const tab = ref('positions')
const loading = ref(false)
const flows = ref([])
const flowTotal = ref(0)
const flowPage = ref(1)
const contracts = ref([])
const contractTotal = ref(0)
const contractPage = ref(1)
const contractStatus = ref(undefined)
const bills = ref([])
const billTotal = ref(0)
const billPage = ref(1)

const accountId = computed(() => acctStore.currentId)
const s = computed(() => acctStore.summary)

/** 资金占用率：冻结+保证金 / 现金合计 */
const usedPct = computed(() => {
  const total = s.value?.fundTotal || 0
  if (!total) return 0
  return ((s.value.frozen || 0) + (s.value.margin || 0)) / total
})

async function loadTab() {
  if (!accountId.value) return
  loading.value = true
  try {
    if (tab.value === 'positions') {
      await acctStore.loadPositions()
    } else if (tab.value === 'flows') {
      const r = await accountApi.flows(accountId.value, flowPage.value, 20)
      flows.value = r?.list || []
      flowTotal.value = r?.total || 0
    } else if (tab.value === 'contracts') {
      const r = await assetApi.contracts({
        accountId: accountId.value,
        status: contractStatus.value,
        page: contractPage.value,
        size: 20,
      })
      contracts.value = r?.list || []
      contractTotal.value = r?.total || 0
    } else {
      const r = await assetApi.bills({ accountId: accountId.value, page: billPage.value, size: 20 })
      bills.value = r?.list || []
      billTotal.value = r?.total || 0
    }
  } finally {
    loading.value = false
  }
}

watch([tab, accountId, flowPage, contractPage, billPage, contractStatus], loadTab, { immediate: true })
usePolling(() => acctStore.loadSummary(), 8000, { immediate: true })

function refresh() {
  acctStore.refreshCurrent()
  loadTab()
}

const posColumns = [
  { title: '标的', key: 'inst', width: 150 },
  { title: '净持仓 (MWh)', key: 'netQty', width: 120, align: 'right' },
  { title: '持仓均价', key: 'avgPrice', width: 100, align: 'right' },
  { title: '最新价', key: 'lastPrice', width: 100, align: 'right' },
  { title: '累计购电', key: 'buyQty', width: 110, align: 'right' },
  { title: '累计售电', key: 'sellQty', width: 110, align: 'right' },
  { title: '购电金额', key: 'buyAmount', width: 130, align: 'right' },
  { title: '售电金额', key: 'sellAmount', width: 130, align: 'right' },
  { title: '浮动盈亏', key: 'unrealizedPnl', width: 120, align: 'right' },
  { title: '已实现盈亏', key: 'realizedPnl', width: 120, align: 'right' },
]

const flowColumns = [
  { title: '时间', key: 'createdAt', width: 150 },
  { title: '类型', key: 'type', width: 110 },
  { title: '变动额', key: 'amount', width: 140, align: 'right' },
  { title: '变动后可用', key: 'balanceAfter', width: 140, align: 'right' },
  { title: '关联单号', key: 'refId', width: 130 },
  { title: '备注', key: 'remark' },
]

const contractColumns = [
  { title: '合同号', key: 'id', width: 110 },
  { title: '签订时间', key: 'signedAt', width: 150 },
  { title: '标的', key: 'inst', width: 130 },
  { title: '我方角色', key: 'role', width: 84 },
  { title: '对手方', key: 'cp', width: 160 },
  { title: '合同电价', key: 'price', width: 100, align: 'right' },
  { title: '合同电量', key: 'quantity', width: 110, align: 'right' },
  { title: '合同金额', key: 'amount', width: 130, align: 'right' },
  { title: '交割区间', key: 'delivery', width: 180 },
  { title: '已交割', key: 'deliveredQty', width: 100, align: 'right' },
  { title: '状态', key: 'status', width: 90 },
]

const billColumns = [
  { title: '结算周期', key: 'period', width: 100 },
  { title: '结算时间', key: 'settledAt', width: 150 },
  { title: '合同电量', key: 'contractQty', width: 110, align: 'right' },
  { title: '实际交割', key: 'actualQty', width: 110, align: 'right' },
  { title: '偏差电量', key: 'deviationQty', width: 110, align: 'right' },
  { title: '偏差考核', key: 'deviationPenalty', width: 120, align: 'right' },
  { title: '现货电量', key: 'spotQty', width: 110, align: 'right' },
  { title: '手续费', key: 'feeTotal', width: 110, align: 'right' },
  { title: '收入', key: 'revenue', width: 130, align: 'right' },
  { title: '成本', key: 'cost', width: 130, align: 'right' },
  { title: '利润', key: 'profit', width: 130, align: 'right' },
  { title: '排名', key: 'rank', width: 70, align: 'right' },
]

function iAmBuyer(c) {
  return c.buyAccountId === accountId.value
}
</script>

<template>
  <div class="assets-view ets-scroll">
    <div v-if="!accountId" class="ets-panel empty-panel">
      <EmptyHint text="请先选择交易账户" hint="在顶栏切换器中选择一个市场主体后查看其资金与持仓">
        <template #icon><Wallet :size="26" :stroke-width="1.4" /></template>
      </EmptyHint>
    </div>

    <template v-else>
      <!-- 资金总览 -->
      <PanelBox class="overview" :flex="false" title="资金总览" :sub="acctStore.current ? `${acctStore.current.code} · ${acctStore.current.name}` : ''">
        <template #extra>
          <a-button size="small" :loading="loading" @click="refresh"><RefreshCw :size="12" /></a-button>
        </template>

        <div class="cards">
          <div class="card hero">
            <span class="k">总权益</span>
            <NumFlash :value="s?.equity" type="money" strong class="v-big" />
            <span class="k2">现金合计 + 浮动盈亏</span>
          </div>
          <div class="card">
            <span class="k">可用资金</span>
            <NumFlash :value="s?.available" type="money" strong class="v" />
            <span class="k2">可用于新申报</span>
          </div>
          <div class="card">
            <span class="k">冻结货款</span>
            <NumFlash :value="s?.frozen" type="money" class="v" />
            <span class="k2">挂单占用</span>
          </div>
          <div class="card">
            <span class="k">售电保证金</span>
            <NumFlash :value="s?.margin" type="money" class="v" />
            <span class="k2">按成交额 × {{ ((simStore.cfg.marginRatio || 0) * 100).toFixed(0) }}% 计提</span>
          </div>
          <div class="card">
            <span class="k">累计盈亏</span>
            <NumFlash :value="s?.totalPnl" type="signedMoney" color-by="self" strong class="v" />
            <span class="k2">已实现 {{ amount(s?.realizedPnl) }} / 浮动 {{ amount(s?.unrealizedPnl) }}</span>
          </div>
          <div class="card">
            <span class="k">今日盈亏</span>
            <NumFlash :value="s?.todayPnl" type="signedMoney" color-by="self" strong class="v" />
            <span class="k2">收益率 {{ ((s?.returnPct || 0) * 100).toFixed(2) }}%</span>
          </div>
        </div>

        <div class="strip">
          <div class="st">
            <span>资金占用率</span>
            <a-progress
              :percent="Math.round(usedPct * 100)"
              size="small"
              :stroke-color="usedPct > 0.8 ? '#f5222d' : '#2f7fff'"
              style="width: 150px; margin: 0"
            />
          </div>
          <div class="st"><span>持仓标的</span><b class="mono">{{ s?.positionCount ?? 0 }}</b></div>
          <div class="st"><span>净持仓电量</span><b class="mono">{{ qty(s?.positionMwh) }} MWh</b></div>
          <div class="st"><span>持仓市值</span><b class="mono">{{ amount(s?.positionValue) }} 元</b></div>
          <div class="st"><span>成交笔数</span><b class="mono">{{ s?.tradeCount ?? 0 }}</b></div>
          <div class="st"><span>购电均价</span><b class="mono up">{{ price(s?.avgBuyPrice) }}</b></div>
          <div class="st"><span>售电均价</span><b class="mono down">{{ price(s?.avgSellPrice) }}</b></div>
          <div class="st"><span>累计手续费</span><b class="mono">{{ amount(s?.feePaid) }}</b></div>
          <div class="st">
            <span>当前排名</span>
            <b class="mono gold">{{ s?.rank ? `${s.rank} / ${s.totalAccounts}` : '—' }}</b>
          </div>
        </div>
      </PanelBox>

      <!-- 明细 -->
      <PanelBox class="detail">
        <template #title />
        <a-tabs v-model:activeKey="tab" size="small" class="tabs">
          <a-tab-pane key="positions" tab="持仓明细" />
          <a-tab-pane key="flows" tab="资金流水" />
          <a-tab-pane key="contracts" tab="购售电合同" />
          <a-tab-pane key="bills" tab="结算单" />
          <template #rightExtra>
            <a-select
              v-if="tab === 'contracts'"
              v-model:value="contractStatus"
              size="small"
              style="width: 118px"
              placeholder="全部状态"
              allow-clear
              :options="[
                { value: 'EFFECTIVE', label: '已生效' },
                { value: 'DELIVERING', label: '交割中' },
                { value: 'DELIVERED', label: '已交割' },
                { value: 'TERMINATED', label: '已终止' },
              ]"
            />
          </template>
        </a-tabs>

        <div class="tb ets-scroll">
          <a-table
            v-if="tab === 'positions'"
            :columns="posColumns"
            :data-source="acctStore.positions"
            :pagination="false"
            :loading="loading"
            row-key="instrumentKey"
            size="small"
            :scroll="{ x: 1180 }"
          >
            <template #emptyText><EmptyHint compact text="暂无持仓" hint="成交后按标的自动聚合持仓" /></template>
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
              <template v-else-if="column.key === 'buyAmount'"><span class="mono">{{ amount(record.buyAmount) }}</span></template>
              <template v-else-if="column.key === 'sellAmount'"><span class="mono">{{ amount(record.sellAmount) }}</span></template>
              <template v-else-if="column.key === 'unrealizedPnl'">
                <span class="mono" :class="record.unrealizedPnl > 0 ? 'up' : record.unrealizedPnl < 0 ? 'down' : ''">
                  {{ signed(record.unrealizedPnl) }}
                </span>
              </template>
              <template v-else-if="column.key === 'realizedPnl'">
                <span class="mono" :class="record.realizedPnl > 0 ? 'up' : record.realizedPnl < 0 ? 'down' : ''">
                  {{ signed(record.realizedPnl) }}
                </span>
              </template>
            </template>
          </a-table>

          <a-table
            v-else-if="tab === 'flows'"
            :columns="flowColumns"
            :data-source="flows"
            :loading="loading"
            row-key="id"
            size="small"
            :scroll="{ x: 900 }"
            :pagination="{ current: flowPage, pageSize: 20, total: flowTotal, size: 'small', showSizeChanger: false, onChange: (p) => (flowPage = p) }"
          >
            <template #emptyText><EmptyHint compact text="暂无资金流水" hint="注资、冻结、成交收付与结算都会在这里留痕" /></template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'createdAt'"><span class="mono dim">{{ datetime(record.createdAt) }}</span></template>
              <template v-else-if="column.key === 'type'">
                <a-tag :bordered="false" :color="record.amount > 0 ? 'red' : 'green'">
                  {{ FLOW_TYPE[record.type] || record.type }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'amount'">
                <span class="mono" :class="record.amount > 0 ? 'up' : 'down'">{{ signed(record.amount) }}</span>
              </template>
              <template v-else-if="column.key === 'balanceAfter'"><span class="mono">{{ amount(record.balanceAfter) }}</span></template>
              <template v-else-if="column.key === 'refId'"><span class="mono dim">{{ record.refId || '—' }}</span></template>
              <template v-else-if="column.key === 'remark'"><span class="dim">{{ record.remark || '—' }}</span></template>
            </template>
          </a-table>

          <a-table
            v-else-if="tab === 'contracts'"
            :columns="contractColumns"
            :data-source="contracts"
            :loading="loading"
            row-key="id"
            size="small"
            :scroll="{ x: 1340 }"
            :pagination="{ current: contractPage, pageSize: 20, total: contractTotal, size: 'small', showSizeChanger: false, onChange: (p) => (contractPage = p) }"
          >
            <template #emptyText><EmptyHint compact text="暂无购售电合同" hint="每笔成交都会自动生成一份合同并进入交割流程" /></template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'id'"><span class="mono">{{ record.id }}</span></template>
              <template v-else-if="column.key === 'signedAt'"><span class="mono dim">{{ datetime(record.signedAt) }}</span></template>
              <template v-else-if="column.key === 'inst'">{{ instrumentLabel(`${record.variety}_${record.periodType}`) }}</template>
              <template v-else-if="column.key === 'role'">
                <span :class="iAmBuyer(record) ? 'up' : 'down'">{{ iAmBuyer(record) ? '购电方' : '售电方' }}</span>
              </template>
              <template v-else-if="column.key === 'cp'">
                <div class="two">
                  <span>{{ iAmBuyer(record) ? record.sellAccountName : record.buyAccountName }}</span>
                  <span class="mono sub">{{ iAmBuyer(record) ? record.sellAccountCode : record.buyAccountCode }}</span>
                </div>
              </template>
              <template v-else-if="column.key === 'price'"><span class="mono">{{ price(record.price) }}</span></template>
              <template v-else-if="column.key === 'quantity'"><span class="mono">{{ qty(record.quantity) }}</span></template>
              <template v-else-if="column.key === 'amount'"><span class="mono">{{ amount(record.amount) }}</span></template>
              <template v-else-if="column.key === 'delivery'">
                <span class="mono dim">{{ record.deliveryStart }} ~ {{ record.deliveryEnd }}</span>
              </template>
              <template v-else-if="column.key === 'deliveredQty'"><span class="mono">{{ qty(record.deliveredQty) }}</span></template>
              <template v-else-if="column.key === 'status'"><StateTag kind="contract" :value="record.status" /></template>
            </template>
          </a-table>

          <a-table
            v-else
            :columns="billColumns"
            :data-source="bills"
            :loading="loading"
            row-key="id"
            size="small"
            :scroll="{ x: 1360 }"
            :pagination="{ current: billPage, pageSize: 20, total: billTotal, size: 'small', showSizeChanger: false, onChange: (p) => (billPage = p) }"
          >
            <template #emptyText>
              <EmptyHint
                compact
                text="暂无结算单"
                hint="结算由管理端按周期触发，会核对合同电量与实际交割电量并计提偏差考核费用"
              />
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'period'">
                <a-tag :bordered="false" color="blue">{{ record.period }}</a-tag>
              </template>
              <template v-else-if="column.key === 'settledAt'"><span class="mono dim">{{ datetime(record.settledAt) }}</span></template>
              <template v-else-if="column.key === 'contractQty'"><span class="mono">{{ qty(record.contractQty) }}</span></template>
              <template v-else-if="column.key === 'actualQty'"><span class="mono">{{ qty(record.actualQty) }}</span></template>
              <template v-else-if="column.key === 'deviationQty'">
                <span class="mono" :class="record.deviationQty ? 'up' : ''">{{ signed(record.deviationQty, qty) }}</span>
              </template>
              <template v-else-if="column.key === 'deviationPenalty'">
                <span class="mono down">{{ amount(record.deviationPenalty) }}</span>
              </template>
              <template v-else-if="column.key === 'spotQty'"><span class="mono">{{ qty(record.spotQty) }}</span></template>
              <template v-else-if="column.key === 'feeTotal'"><span class="mono">{{ amount(record.feeTotal) }}</span></template>
              <template v-else-if="column.key === 'revenue'"><span class="mono up">{{ amount(record.revenue) }}</span></template>
              <template v-else-if="column.key === 'cost'"><span class="mono down">{{ amount(record.cost) }}</span></template>
              <template v-else-if="column.key === 'profit'">
                <span class="mono" :class="record.profit > 0 ? 'up' : record.profit < 0 ? 'down' : ''">
                  {{ signed(record.profit) }}
                </span>
              </template>
              <template v-else-if="column.key === 'rank'"><span class="mono gold">{{ record.rank || '—' }}</span></template>
            </template>
          </a-table>
        </div>
      </PanelBox>
    </template>
  </div>
</template>

<style scoped>
.assets-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}
.empty-panel {
  padding: 30px;
}
.overview {
  flex: none;
}
.cards {
  display: grid;
  grid-template-columns: 1.4fr repeat(5, 1fr);
  gap: 1px;
  background: var(--ets-line);
}
.card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 11px 13px;
  background: var(--ets-panel);
}
.card.hero {
  background: linear-gradient(135deg, rgba(47, 127, 255, 0.09), transparent 70%);
}
.card .k {
  font-size: 11px;
  color: var(--ets-text-faint);
}
.card .k2 {
  font-size: 10px;
  color: #4f5a6b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.v {
  font-size: 17px !important;
}
.v-big {
  font-size: 24px !important;
}

.strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  padding: 9px 13px;
  border-top: 1px solid var(--ets-line);
  background: var(--ets-raise);
}
.st {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--ets-text-faint);
}
.st b {
  color: var(--ets-text-strong);
  font-weight: 600;
  font-size: 12px;
}
.gold {
  color: var(--ets-gold) !important;
}

.detail {
  flex: 1;
  min-height: 320px;
}
.detail :deep(.ets-panel-title) {
  display: none;
}
.tabs {
  flex: none;
  padding: 6px 10px 0;
  border-bottom: 1px solid var(--ets-line);
}
.tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0 !important;
}
.tb {
  flex: 1;
  min-height: 0;
  padding-bottom: 6px;
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

@media (max-width: 1400px) {
  .cards {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
