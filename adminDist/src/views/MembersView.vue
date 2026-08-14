<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Ban, Copy, KeyRound, LockOpen, RefreshCw, Search, Wallet } from 'lucide-vue-next'
import PanelCard from '../components/PanelCard.vue'
import { memberApi } from '../api'
import { useSimStore } from '../stores/sim'
import { dangerConfirm } from '../composables/useDanger'
import { MEMBER_TYPE, MEMBER_TYPE_COLOR, ORDER_SOURCE, PERIOD_TYPE, SOURCE_COLOR, VARIETY } from '../utils/enums'
import { datetime, money, mwh, num, pct, pnlClass, price, thousands, unThousands } from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)
const tab = ref('accounts')

// ══════════════════ 交易用户 ══════════════════

const userState = reactive({ loading: false, keyword: '', list: [], total: 0, page: 1, size: 20 })

async function loadUsers() {
  userState.loading = true
  try {
    const p = await memberApi.users({
      keyword: userState.keyword || undefined,
      page: userState.page,
      size: userState.size,
    })
    userState.list = p?.list || []
    userState.total = p?.total || 0
  } catch {
    userState.list = []
    userState.total = 0
  } finally {
    userState.loading = false
  }
}

function searchUsers() {
  userState.page = 1
  loadUsers()
}

function toggleFreeze(u) {
  const freeze = !u.frozen
  dangerConfirm({
    title: freeze ? `冻结用户「${u.nickname || u.username}」？` : `解冻用户「${u.nickname || u.username}」？`,
    okText: freeze ? '冻结' : '解冻',
    content: freeze
      ? '冻结后该用户无法登录、无法申报，其名下账户的既有挂单保持不变。'
      : '解冻后该用户恢复登录与交易权限。',
    success: freeze ? '用户已冻结' : '用户已解冻',
    onOk: async () => {
      await (freeze ? memberApi.freeze(u.id) : memberApi.unfreeze(u.id))
      await loadUsers()
    },
  })
}

// 重置口令结果
const pwdResult = reactive({ open: false, user: '', password: '' })

function resetPwd(u) {
  dangerConfirm({
    title: `重置「${u.nickname || u.username}」的登录口令？`,
    okText: '重置口令',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', '系统将生成新口令并在下一步展示，请线下转达该学员。'),
      h('div', { style: 'color:#e3b341;margin-top:4px' }, '旧口令立即失效，该用户已登录的会话需要重新登录。'),
    ]),
    onOk: async () => {
      const r = await memberApi.resetPassword(u.id)
      pwdResult.user = u.username
      pwdResult.password = r?.password || ''
      pwdResult.open = true
      await loadUsers()
    },
  })
}

async function copyPwd() {
  try {
    await navigator.clipboard.writeText(pwdResult.password)
    message.success('新口令已复制到剪贴板')
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

const userColumns = [
  { title: '账号', dataIndex: 'username', width: 130 },
  { title: '昵称', dataIndex: 'nickname', width: 140 },
  { title: '所属单位', dataIndex: 'orgName', ellipsis: true },
  { title: '联系电话', dataIndex: 'phone', width: 130 },
  { title: '交易账户', dataIndex: 'accountCount', width: 92, align: 'right' },
  { title: '状态', dataIndex: 'frozen', width: 86 },
  { title: '最近登录', dataIndex: 'lastLoginAt', width: 152 },
  { title: '操作', dataIndex: 'ops', width: 180, fixed: 'right' },
]

// ══════════════════ 交易账户 ══════════════════

const acctState = reactive({
  loading: false,
  list: [],
  total: 0,
  page: 1,
  size: 20,
  memberType: null,
  source: null,
  keyword: '',
  simId: '',
})

async function loadAccounts() {
  const sid = acctState.simId || simId.value
  if (!sid) {
    acctState.list = []
    acctState.total = 0
    return
  }
  acctState.loading = true
  try {
    const p = await memberApi.accounts({
      simId: sid,
      memberType: acctState.memberType || undefined,
      source: acctState.source || undefined,
      keyword: acctState.keyword || undefined,
      page: acctState.page,
      size: acctState.size,
    })
    acctState.list = p?.list || []
    acctState.total = p?.total || 0
  } catch {
    acctState.list = []
    acctState.total = 0
  } finally {
    acctState.loading = false
  }
}

const accountRows = computed(() => acctState.list)

function reloadAccounts() {
  acctState.page = 1
  loadAccounts()
}

// 调整资金
const fundModal = reactive({ open: false, submitting: false, record: null, amount: 1000000, remark: '' })

function openFund(a) {
  fundModal.record = a
  fundModal.amount = 1000000
  fundModal.remark = ''
  fundModal.open = true
}

async function submitFund() {
  if (!fundModal.amount) return message.warning('请输入调整金额（正数注资、负数扣减）')
  fundModal.submitting = true
  try {
    await memberApi.adjustFund(fundModal.record.id, fundModal.amount, fundModal.remark)
    message.success(`账户 ${fundModal.record.code} 资金已调整 ${num(fundModal.amount, 2)} 元`)
    fundModal.open = false
    await loadAccounts()
  } finally {
    fundModal.submitting = false
  }
}

function forceCancel(a) {
  dangerConfirm({
    title: `强制撤销「${a.code} ${a.name}」的全部挂单？`,
    width: 480,
    okText: '强制撤单',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', { style: 'color:#f5222d;font-weight:600' }, '将撤销该账户在本演练场所有场次上的全部挂单。'),
      h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '撤单会释放对应的冻结货款与卖方保证金，通常用于处置异常报价或解救资金见底的账户。'),
    ]),
    onOk: async () => {
      const r = await memberApi.forceCancel(a.id)
      message.success(`已撤销 ${r?.cancelled ?? 0} 笔挂单`)
      await loadAccounts()
    },
  })
}

const acctColumns = [
  { title: '账户', dataIndex: 'code', width: 96, fixed: 'left' },
  { title: '主体名称', dataIndex: 'name', width: 170 },
  { title: '类型', dataIndex: 'memberType', width: 98 },
  { title: '来源', dataIndex: 'source', width: 84 },
  { title: '归属用户', dataIndex: 'userName', width: 120 },
  { title: '可用资金', dataIndex: 'available', width: 120, align: 'right' },
  { title: '冻结/保证金', dataIndex: 'frozenMargin', width: 134, align: 'right' },
  { title: '权益合计', dataIndex: 'equity', width: 120, align: 'right' },
  { title: '净持仓', dataIndex: 'position', width: 132, align: 'right' },
  { title: '总盈亏', dataIndex: 'totalPnl', width: 124, align: 'right' },
  { title: '收益率', dataIndex: 'returnPct', width: 88, align: 'right' },
  { title: '名次', dataIndex: 'rank', width: 64, align: 'right' },
  { title: '参数', dataIndex: 'paramsLocked', width: 84 },
  { title: '操作', dataIndex: 'ops', width: 168, fixed: 'right' },
]

// 账户详情抽屉
const detail = reactive({ open: false, record: null, positions: [], posLoading: false })

function instrumentLabel(key) {
  if (!key) return '—'
  const split = key.lastIndexOf('_')
  if (split <= 0) return key
  const v = VARIETY.find((x) => x.value === key.slice(0, split))?.label || key.slice(0, split)
  const p = PERIOD_TYPE.find((x) => x.value === key.slice(split + 1))?.label || key.slice(split + 1)
  return `${v}${p}`
}

async function openDetail(a) {
  detail.record = a
  detail.positions = []
  detail.open = true
  detail.posLoading = true
  try {
    detail.positions = (await memberApi.positions(a.id)) || []
  } catch {
    detail.positions = []
  } finally {
    detail.posLoading = false
  }
}

onMounted(async () => {
  await simStore.load()
  acctState.simId = simId.value
  await Promise.all([loadUsers(), loadAccounts()])
})

watch(simId, (v) => {
  acctState.simId = v
  reloadAccounts()
})
</script>

<template>
  <div class="page">
    <a-tabs v-model:activeKey="tab" size="small" class="tabs">
      <a-tab-pane key="accounts" tab="交易账户" />
      <a-tab-pane key="users" tab="交易用户" />
    </a-tabs>

    <!-- ══════════ 交易账户 ══════════ -->
    <template v-if="tab === 'accounts'">
      <PanelCard title="交易账户">
        <template #extra>
          <a-button size="small" @click="loadAccounts">
            <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
          </a-button>
        </template>
        <div class="filters">
          <a-select
            v-model:value="acctState.simId"
            size="small"
            style="width: 240px"
            placeholder="选择演练场"
            :options="simStore.sims.map((s) => ({ value: s.id, label: s.name }))"
            @change="reloadAccounts"
          />
          <a-select
            v-model:value="acctState.memberType"
            size="small"
            style="width: 128px"
            placeholder="全部主体类型"
            allow-clear
            :options="MEMBER_TYPE"
            @change="reloadAccounts"
          />
          <a-select
            v-model:value="acctState.source"
            size="small"
            style="width: 118px"
            placeholder="全部来源"
            allow-clear
            :options="ORDER_SOURCE"
            @change="reloadAccounts"
          />
          <a-input
            v-model:value="acctState.keyword"
            size="small"
            style="width: 180px"
            placeholder="搜索账户编号 / 名称"
            allow-clear
            @press-enter="reloadAccounts"
          />
          <a-button size="small" @click="reloadAccounts">搜索</a-button>
          <span class="text-muted" style="font-size: 11.5px">服务端搜索，共 {{ acctState.total }} 户</span>
        </div>
      </PanelCard>

      <PanelCard no-pad>
        <a-table
          :columns="acctColumns"
          :data-source="accountRows"
          :loading="acctState.loading"
          size="small"
          row-key="id"
          :scroll="{ x: 1780 }"
          :pagination="{
            current: acctState.page,
            pageSize: acctState.size,
            total: acctState.total,
            size: 'small',
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 个交易账户`,
          }"
          @change="
            (p) => {
              acctState.page = p.current
              acctState.size = p.pageSize
              loadAccounts()
            }
          "
        >
          <template #emptyText>
            <a-empty :description="acctState.simId ? '该筛选条件下没有交易账户' : '请先选择演练场'" />
          </template>

          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'code'">
              <a class="num" @click="openDetail(record)">{{ record.code }}</a>
            </template>

            <template v-else-if="column.dataIndex === 'name'">
              <div class="c-name">{{ record.name }}</div>
              <div v-if="record.frozen" class="c-sub text-danger">账户已冻结</div>
            </template>

            <template v-else-if="column.dataIndex === 'memberType'">
              <a-tag :color="MEMBER_TYPE_COLOR[record.memberType]">{{ record.memberTypeLabel }}</a-tag>
            </template>

            <template v-else-if="column.dataIndex === 'source'">
              <a-tag :color="SOURCE_COLOR[record.source]">{{ record.sourceLabel }}</a-tag>
            </template>

            <template v-else-if="column.dataIndex === 'userName'">
              <span class="text-sub">{{ record.userName || (record.source === 'ROBOT' ? '系统机器人' : '—') }}</span>
            </template>

            <template v-else-if="column.dataIndex === 'available'">
              <span class="num">{{ money(record.fund?.available) }}</span>
            </template>

            <template v-else-if="column.dataIndex === 'frozenMargin'">
              <span class="num text-sub">
                {{ money(record.fund?.frozen) }} / {{ money(record.fund?.margin) }}
              </span>
            </template>

            <template v-else-if="column.dataIndex === 'equity'">
              <div class="num">{{ money(record.equity) }}</div>
              <div class="c-sub num">注资 {{ money(record.fund?.totalDeposit) }}</div>
            </template>

            <template v-else-if="column.dataIndex === 'position'">
              <span class="num up">买 {{ mwh(record.stat?.buyMwh, false) }}</span>
              <span class="text-muted"> / </span>
              <span class="num down">卖 {{ mwh(record.stat?.sellMwh, false) }}</span>
            </template>

            <template v-else-if="column.dataIndex === 'totalPnl'">
              <div class="num" :class="pnlClass(record.stat?.totalPnl)">{{ num(record.stat?.totalPnl, 0) }}</div>
              <div class="c-sub num">浮盈 {{ num(record.stat?.unrealizedPnl, 0) }}</div>
            </template>

            <template v-else-if="column.dataIndex === 'returnPct'">
              <span class="num" :class="pnlClass(record.returnPct)">{{ pct(record.returnPct) }}</span>
            </template>

            <template v-else-if="column.dataIndex === 'rank'">
              <span class="num">{{ record.stat?.rank || '—' }}</span>
            </template>

            <template v-else-if="column.dataIndex === 'paramsLocked'">
              <a-tag :color="record.paramsLocked ? 'success' : 'default'">
                {{ record.paramsLocked ? '已锁定' : '可编辑' }}
              </a-tag>
            </template>

            <template v-else-if="column.dataIndex === 'ops'">
              <a-space :size="2">
                <a-button type="link" size="small" @click="openFund(record)">
                  <Wallet :size="12" style="vertical-align: -1px" /> 调资金
                </a-button>
                <a-button type="link" size="small" danger @click="forceCancel(record)">
                  <Ban :size="12" style="vertical-align: -1px" /> 强制撤单
                </a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </PanelCard>
    </template>

    <!-- ══════════ 交易用户 ══════════ -->
    <template v-else>
      <PanelCard title="交易用户">
        <template #extra>
          <a-button size="small" @click="loadUsers">
            <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
          </a-button>
        </template>
        <div class="filters">
          <a-input
            v-model:value="userState.keyword"
            size="small"
            style="width: 260px"
            placeholder="搜索账号 / 昵称 / 单位 / 电话"
            allow-clear
            @press-enter="searchUsers"
          >
            <template #prefix><Search :size="13" style="color: var(--text-muted)" /></template>
          </a-input>
          <a-button type="primary" size="small" @click="searchUsers">搜索</a-button>
        </div>
      </PanelCard>

      <PanelCard no-pad>
        <a-table
          :columns="userColumns"
          :data-source="userState.list"
          :loading="userState.loading"
          size="small"
          row-key="id"
          :scroll="{ x: 1120 }"
          :pagination="{
            current: userState.page,
            pageSize: userState.size,
            total: userState.total,
            size: 'small',
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 名交易用户`,
          }"
          @change="
            (p) => {
              userState.page = p.current
              userState.size = p.pageSize
              loadUsers()
            }
          "
        >
          <template #emptyText>
            <a-empty description="没有匹配的交易用户" />
          </template>

          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'username'">
              <span class="num">{{ record.username }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'orgName'">
              <span class="text-sub">{{ record.orgName || '—' }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'phone'">
              <span class="num text-sub">{{ record.phone || '—' }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'accountCount'">
              <span class="num">{{ record.accountCount ?? 0 }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'frozen'">
              <a-tag :color="record.frozen ? 'error' : 'success'">{{ record.frozen ? '已冻结' : '正常' }}</a-tag>
            </template>
            <template v-else-if="column.dataIndex === 'lastLoginAt'">
              <span class="num text-sub">{{ record.lastLoginAt ? datetime(record.lastLoginAt) : '从未登录' }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'ops'">
              <a-space :size="2">
                <a-button type="link" size="small" :danger="!record.frozen" @click="toggleFreeze(record)">
                  <component :is="record.frozen ? LockOpen : Ban" :size="12" style="vertical-align: -1px" />
                  {{ record.frozen ? '解冻' : '冻结' }}
                </a-button>
                <a-button type="link" size="small" @click="resetPwd(record)">
                  <KeyRound :size="12" style="vertical-align: -1px" /> 重置口令
                </a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </PanelCard>
    </template>

    <!-- 调整资金 -->
    <a-modal
      v-model:open="fundModal.open"
      title="调整账户资金"
      :width="480"
      :confirm-loading="fundModal.submitting"
      ok-text="确认调整"
      cancel-text="取消"
      centered
      @ok="submitFund"
    >
      <a-descriptions v-if="fundModal.record" :column="1" size="small" bordered style="margin-bottom: 14px">
        <a-descriptions-item label="交易账户">
          {{ fundModal.record.code }} · {{ fundModal.record.name }}
        </a-descriptions-item>
        <a-descriptions-item label="当前可用">
          <span class="num">{{ money(fundModal.record.fund?.available) }}</span>
        </a-descriptions-item>
        <a-descriptions-item label="调整后可用">
          <span class="num" :class="pnlClass(fundModal.amount)">
            {{ money((fundModal.record.fund?.available || 0) + (fundModal.amount || 0)) }}
          </span>
        </a-descriptions-item>
      </a-descriptions>

      <a-form layout="vertical">
        <a-form-item label="调整金额（元，正数注资 / 负数扣减）">
          <a-input-number
            v-model:value="fundModal.amount"
            :step="1000000"
            style="width: 100%"
            :formatter="thousands"
            :parser="unThousands"
          />
        </a-form-item>
        <a-form-item label="备注（写入资金流水与审计日志）">
          <a-input v-model:value="fundModal.remark" :maxlength="200" placeholder="如：演练补充授信额度" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 重置口令结果 -->
    <a-modal v-model:open="pwdResult.open" title="口令已重置" :width="440" :footer="null" centered>
      <a-result status="success" :title="`用户 ${pwdResult.user} 的新登录口令`">
        <template #extra>
          <div class="pwd-box">
            <span class="num pwd-text">{{ pwdResult.password }}</span>
            <a-button size="small" @click="copyPwd">
              <Copy :size="12" style="vertical-align: -1px" /> 复制
            </a-button>
          </div>
          <p class="pwd-tip">该口令仅此一次展示，请立即线下转达学员。关闭窗口后无法再次查看。</p>
          <a-button type="primary" @click="pwdResult.open = false">我已记录</a-button>
        </template>
      </a-result>
    </a-modal>

    <!-- 账户详情 -->
    <a-drawer v-model:open="detail.open" :width="560" :title="`账户详情 · ${detail.record?.code || ''}`" placement="right">
      <template v-if="detail.record">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="账户编号">{{ detail.record.code }}</a-descriptions-item>
          <a-descriptions-item label="主体名称">{{ detail.record.name }}</a-descriptions-item>
          <a-descriptions-item label="主体类型">{{ detail.record.memberTypeLabel }}</a-descriptions-item>
          <a-descriptions-item label="来源">{{ detail.record.sourceLabel }}</a-descriptions-item>
          <a-descriptions-item label="归属用户">{{ detail.record.userName || '—' }}</a-descriptions-item>
          <a-descriptions-item label="参数锁定">
            {{ detail.record.paramsLocked ? datetime(detail.record.lockedAt) : '未锁定' }}
          </a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left" style="margin: 16px 0 12px"><span class="dt">资金</span></a-divider>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="可用">{{ money(detail.record.fund?.available) }}</a-descriptions-item>
          <a-descriptions-item label="冻结货款">{{ money(detail.record.fund?.frozen) }}</a-descriptions-item>
          <a-descriptions-item label="卖方保证金">{{ money(detail.record.fund?.margin) }}</a-descriptions-item>
          <a-descriptions-item label="累计注资">{{ money(detail.record.fund?.totalDeposit) }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left" style="margin: 16px 0 12px"><span class="dt">交易统计</span></a-divider>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="已实现盈亏">
            <span :class="pnlClass(detail.record.stat?.realizedPnl)">{{ num(detail.record.stat?.realizedPnl, 2) }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="浮动盈亏">
            <span :class="pnlClass(detail.record.stat?.unrealizedPnl)">{{ num(detail.record.stat?.unrealizedPnl, 2) }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="成交笔数">{{ detail.record.stat?.tradeCount ?? 0 }}</a-descriptions-item>
          <a-descriptions-item label="手续费">{{ money(detail.record.stat?.feePaid) }}</a-descriptions-item>
          <a-descriptions-item label="购电电量">{{ mwh(detail.record.stat?.buyMwh) }}</a-descriptions-item>
          <a-descriptions-item label="售电电量">{{ mwh(detail.record.stat?.sellMwh) }}</a-descriptions-item>
          <a-descriptions-item label="购电均价">{{ price(detail.record.stat?.avgBuyPrice) }}</a-descriptions-item>
          <a-descriptions-item label="售电均价">{{ price(detail.record.stat?.avgSellPrice) }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left" style="margin: 16px 0 12px"><span class="dt">主体参数</span></a-divider>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="初始资金">{{ money(detail.record.params?.initialCapital) }}</a-descriptions-item>
          <a-descriptions-item label="风险偏好">{{ detail.record.params?.riskAppetite ?? '—' }}</a-descriptions-item>
          <template v-if="detail.record.memberType === 'GENCO'">
            <a-descriptions-item label="机组类型">{{ detail.record.params?.genType || '—' }}</a-descriptions-item>
            <a-descriptions-item label="装机容量">{{ detail.record.params?.installedCapacityMw ?? '—' }} MW</a-descriptions-item>
            <a-descriptions-item label="年度可发电量">{{ mwh(detail.record.params?.annualEnergyMwh) }}</a-descriptions-item>
            <a-descriptions-item label="变动成本">{{ price(detail.record.params?.variableCostPerMwh) }} 元/MWh</a-descriptions-item>
          </template>
          <template v-else-if="detail.record.memberType === 'CONSUMER'">
            <a-descriptions-item label="年度用电需求">{{ mwh(detail.record.params?.annualDemandMwh) }}</a-descriptions-item>
            <a-descriptions-item label="可承受最高价">{{ price(detail.record.params?.maxAcceptPricePerMwh) }} 元/MWh</a-descriptions-item>
            <a-descriptions-item label="负荷特性">{{ detail.record.params?.loadProfile || '—' }}</a-descriptions-item>
            <a-descriptions-item label="目录电价">{{ price(detail.record.params?.retailBenchmarkPrice) }} 元/MWh</a-descriptions-item>
          </template>
          <template v-else>
            <a-descriptions-item label="代理电量">{{ mwh(detail.record.params?.agentDemandMwh) }}</a-descriptions-item>
            <a-descriptions-item label="代理用户数">{{ detail.record.params?.agentUserCount ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="零售套餐电价">{{ price(detail.record.params?.retailPricePerMwh) }} 元/MWh</a-descriptions-item>
          </template>
          <a-descriptions-item label="单笔申报上限">{{ mwh(detail.record.params?.maxSingleOrderMwh) }}</a-descriptions-item>
          <a-descriptions-item label="最大持仓">{{ mwh(detail.record.params?.maxPositionMwh) }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left" style="margin: 16px 0 12px"><span class="dt">逐标的持仓</span></a-divider>
        <a-spin :spinning="detail.posLoading">
          <a-empty v-if="!detail.positions.length" description="暂无持仓" />
          <a-table
            v-else
            size="small"
            row-key="instrumentKey"
            :pagination="false"
            :data-source="detail.positions"
            :columns="[
              { title: '标的', dataIndex: 'instrumentKey' },
              { title: '净持仓', dataIndex: 'netQty', align: 'right' },
              { title: '浮动盈亏', dataIndex: 'unrealizedPnl', align: 'right' },
            ]"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'instrumentKey'">{{ instrumentLabel(record.instrumentKey) }}</template>
              <template v-else-if="column.dataIndex === 'netQty'">{{ mwh(record.netQty) }}</template>
              <template v-else-if="column.dataIndex === 'unrealizedPnl'">
                <span :class="pnlClass(record.unrealizedPnl)">{{ num(record.unrealizedPnl, 2) }}</span>
              </template>
            </template>
          </a-table>
        </a-spin>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tabs {
  margin-bottom: -6px;
}

.tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0;
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

.dt {
  font-size: 12px;
  color: var(--text-sub);
}

.pwd-box {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 12px;
  margin-bottom: 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 5px;
  background: var(--bg-input);
}

.pwd-text {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1.5px;
  color: #79b8ff;
}

.pwd-tip {
  margin: 0 0 14px;
  font-size: 11.5px;
  color: var(--text-muted);
  line-height: 1.7;
}
</style>
