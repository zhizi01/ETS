<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import {
  Factory,
  Lock,
  Pencil,
  Plus,
  RefreshCw,
  Store,
  Trash2,
  TriangleAlert,
  Zap,
} from 'lucide-vue-next'
import NumFlash from '@/components/common/NumFlash.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { accountApi } from '@/api/user'
import { GEN_TYPE, GEN_TYPE_COST, LOAD_PROFILE, MEMBER_TYPE } from '@/utils/enums'
import { datetime, money } from '@/utils/format'

const simStore = useSimStore()
const acctStore = useAccountStore()

const saving = ref(false)
const open = ref(false)
const editing = ref(null)
const formRef = ref(null)

const cfg = computed(() => simStore.cfg)
/** 模拟盘一旦离开 SETUP，全部账户参数即被冻结 */
const simLocked = computed(() => simStore.sim?.state && simStore.sim.state !== 'SETUP')
const reachedMax = computed(() => acctStore.accounts.length >= (cfg.value.maxAccountsPerUser || 3))

const MEMBER_META = {
  GENCO: { icon: Factory, tone: '#f5222d', desc: '拥有发电机组，通过售电获取超过变动成本的价差收益' },
  CONSUMER: { icon: Zap, tone: '#2f7fff', desc: '有刚性用电需求，低于目录电价采购即产生节约收益' },
  RETAILER: { icon: Store, tone: '#9254de', desc: '代理终端用户购电，赚取零售套餐价与批发价之间的价差' },
}

function blankForm() {
  return {
    name: '',
    memberType: 'GENCO',
    params: {
      initialCapital: Math.min(50_000_000, cfg.value.initialCapitalMax || 50_000_000),
      genType: 'COAL',
      installedCapacityMw: 600,
      annualEnergyMwh: 3_000_000,
      variableCostPerMwh: 320,
      minOutputRatio: 0.4,
      maxOutputRatio: 1,
      forcedOutageRate: 0.03,
      annualDemandMwh: null,
      maxAcceptPricePerMwh: null,
      loadProfile: null,
      retailBenchmarkPrice: null,
      agentDemandMwh: null,
      agentUserCount: null,
      retailPricePerMwh: null,
      maxSingleOrderMwh: 50_000,
      maxPositionMwh: 2_000_000,
      riskAppetite: 3,
    },
  }
}

const form = reactive(blankForm())

const genTypeOptions = computed(() =>
  simStore.options('GenType', GEN_TYPE).map((o) => ({
    value: o.value,
    label: `${o.label}（典型成本 ${o.typicalCost ?? GEN_TYPE_COST[o.value] ?? '—'} 元/MWh）`,
  })),
)
const loadProfileOptions = computed(() => simStore.options('LoadProfile', LOAD_PROFILE))
const memberOptions = computed(() => simStore.options('MemberType', MEMBER_TYPE))

const rules = {
  name: [{ required: true, message: '请填写账户名称' }],
  memberType: [{ required: true, message: '请选择市场主体类型' }],
}

/** 切主体类型时清掉不属于该类型的字段，避免把上一类的残值提交上去 */
watch(
  () => form.memberType,
  (t) => {
    if (editing.value) return
    const p = form.params
    if (t === 'GENCO') {
      p.genType ||= 'COAL'
      p.installedCapacityMw ??= 600
      p.annualEnergyMwh ??= 3_000_000
      p.variableCostPerMwh ??= GEN_TYPE_COST[p.genType] || 320
      p.annualDemandMwh = p.maxAcceptPricePerMwh = p.retailBenchmarkPrice = null
      p.loadProfile = null
      p.agentDemandMwh = p.agentUserCount = p.retailPricePerMwh = null
    } else if (t === 'CONSUMER') {
      p.annualDemandMwh ??= 1_200_000
      p.maxAcceptPricePerMwh ??= 430
      p.loadProfile ||= 'CONTINUOUS'
      p.retailBenchmarkPrice ??= 450
      p.genType = null
      p.installedCapacityMw = p.annualEnergyMwh = p.variableCostPerMwh = null
      p.minOutputRatio = p.maxOutputRatio = p.forcedOutageRate = null
      p.agentDemandMwh = p.agentUserCount = p.retailPricePerMwh = null
    } else {
      p.agentDemandMwh ??= 800_000
      p.agentUserCount ??= 400
      p.retailPricePerMwh ??= 460
      p.genType = null
      p.installedCapacityMw = p.annualEnergyMwh = p.variableCostPerMwh = null
      p.minOutputRatio = p.maxOutputRatio = p.forcedOutageRate = null
      p.annualDemandMwh = p.maxAcceptPricePerMwh = p.retailBenchmarkPrice = null
      p.loadProfile = null
    }
  },
)

watch(
  () => form.params.genType,
  (g) => {
    if (!editing.value && g && GEN_TYPE_COST[g]) form.params.variableCostPerMwh = GEN_TYPE_COST[g]
  },
)

function reset(src) {
  const base = blankForm()
  Object.assign(form, base, src ? { name: src.name, memberType: src.memberType } : {})
  form.params = src?.params ? { ...base.params, ...src.params } : base.params
}

function openCreate() {
  if (simLocked.value) return
  editing.value = null
  reset(null)
  open.value = true
}

function openEdit(a) {
  editing.value = a
  reset(a)
  open.value = true
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  const params = pruneParams(form.memberType, form.params)
  saving.value = true
  try {
    if (editing.value) {
      await accountApi.update(editing.value.id, { name: form.name, params })
      message.success('账户参数已更新')
    } else {
      await accountApi.create({ simId: simStore.currentSimId, name: form.name, memberType: form.memberType, params })
      message.success('市场主体建档成功')
    }
    open.value = false
    await acctStore.load(simStore.currentSimId)
  } catch {
    // 后端会给出「缺少必填参数」等中文原因，拦截器已弹出
  } finally {
    saving.value = false
  }
}

/** 只提交本主体类型用得上的字段，其余置 null，与后端分支校验保持一致 */
function pruneParams(type, p) {
  const common = {
    initialCapital: num(p.initialCapital),
    maxSingleOrderMwh: num(p.maxSingleOrderMwh),
    maxPositionMwh: num(p.maxPositionMwh),
    riskAppetite: p.riskAppetite ?? 3,
  }
  if (type === 'GENCO') {
    return {
      ...common,
      genType: p.genType,
      installedCapacityMw: num(p.installedCapacityMw),
      annualEnergyMwh: num(p.annualEnergyMwh),
      variableCostPerMwh: num(p.variableCostPerMwh),
      minOutputRatio: num(p.minOutputRatio),
      maxOutputRatio: num(p.maxOutputRatio),
      forcedOutageRate: num(p.forcedOutageRate),
    }
  }
  if (type === 'CONSUMER') {
    return {
      ...common,
      annualDemandMwh: num(p.annualDemandMwh),
      maxAcceptPricePerMwh: num(p.maxAcceptPricePerMwh),
      loadProfile: p.loadProfile,
      retailBenchmarkPrice: num(p.retailBenchmarkPrice),
    }
  }
  return {
    ...common,
    agentDemandMwh: num(p.agentDemandMwh),
    agentUserCount: p.agentUserCount == null ? null : Number(p.agentUserCount),
    retailPricePerMwh: num(p.retailPricePerMwh),
  }
}

function num(v) {
  return v === null || v === undefined || v === '' ? null : Number(v)
}

function confirmDelete(a) {
  Modal.confirm({
    title: `删除交易账户 ${a.code}`,
    content: `「${a.name}」及其全部申报、成交与流水记录都会被清除，此操作不可恢复。`,
    okText: '确认删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: async () => {
      await accountApi.remove(a.id)
      message.success('账户已删除')
      if (acctStore.currentId === a.id) acctStore.setCurrent('')
      await acctStore.load(simStore.currentSimId)
    },
  })
}

function refresh() {
  acctStore.load(simStore.currentSimId)
}

function lockedOf(a) {
  return !!a && (a.paramsLocked || simLocked.value)
}

const formLocked = computed(() => !!editing.value && lockedOf(editing.value))

function metaOf(memberType) {
  return MEMBER_META[memberType] || { icon: Factory, tone: '#8b98a9', desc: '' }
}

const capitalHint = computed(
  () => `允许区间 ${money(cfg.value.initialCapitalMin)} ~ ${money(cfg.value.initialCapitalMax)} 元`,
)
</script>

<template>
  <div class="accounts ets-scroll">
    <!-- 锁定横幅：产品硬规则，必须显眼 -->
    <div v-if="simLocked || acctStore.anyLocked" class="lock-banner">
      <Lock :size="15" />
      <div>
        <strong>参数已锁定，开盘后不可修改</strong>
        <span>
          模拟盘已进入「{{ simStore.sim?.stateLabel || simStore.simState }}」阶段，
          交易账户的主体参数与初始资金在开盘瞬间被冻结，新增、修改、删除均已关闭。如需调整请联系管理员重置演练。
        </span>
      </div>
    </div>

    <header class="head">
      <div>
        <h2>我的交易账户</h2>
        <p>
          在同一模拟盘下最多可建立
          <b class="mono">{{ cfg.maxAccountsPerUser }}</b>
          个市场主体，分别扮演发电企业、电力用户或售电公司。当前已建
          <b class="mono">{{ acctStore.accounts.length }}</b> 个。
        </p>
      </div>
      <div class="ops">
        <a-button size="small" :loading="acctStore.loading" @click="refresh">
          <template #icon><RefreshCw :size="13" /></template>刷新
        </a-button>
        <a-tooltip :title="simLocked ? '开盘后不可新增账户' : reachedMax ? '已达账户数量上限' : ''">
          <a-button type="primary" size="small" :disabled="simLocked || reachedMax" @click="openCreate">
            <template #icon><Plus :size="13" /></template>创建市场主体
          </a-button>
        </a-tooltip>
      </div>
    </header>

    <a-spin :spinning="acctStore.loading && !acctStore.loaded">
      <div v-if="!acctStore.accounts.length && acctStore.loaded" class="ets-panel empty">
        <EmptyHint
          text="还没有市场主体"
          hint="创建交易账户后才能参与申报。请先选择主体类型并填写机组/负荷参数，开盘时系统会按初始资金自动注资。"
        >
          <template #action>
            <a-button type="primary" size="small" :disabled="simLocked" @click="openCreate">
              立即建档
            </a-button>
          </template>
        </EmptyHint>
      </div>

      <div v-else class="grid">
        <article
          v-for="a in acctStore.accounts"
          :key="a.id"
          class="acct-card ets-panel"
          :class="{ on: a.id === acctStore.currentId }"
          @click="acctStore.setCurrent(a.id)"
        >
          <div class="ac-head">
            <div class="ident">
              <span class="badge" :style="{ background: metaOf(a.memberType).tone + '22', color: metaOf(a.memberType).tone }">
                <component :is="metaOf(a.memberType).icon" :size="15" />
              </span>
              <div>
                <div class="nm">
                  {{ a.name }}
                  <a-tag :bordered="false" color="default" class="mono">{{ a.code }}</a-tag>
                </div>
                <div class="ty">{{ a.memberTypeLabel || MEMBER_TYPE[a.memberType] }}</div>
              </div>
            </div>
            <a-tag v-if="lockedOf(a)" color="orange" :bordered="false">
              <Lock :size="10" style="vertical-align: -1px" /> 已锁定
            </a-tag>
            <a-tag v-else color="blue" :bordered="false">可编辑</a-tag>
          </div>

          <p class="ac-desc">{{ metaOf(a.memberType).desc }}</p>

          <div class="ac-params">
            <template v-if="a.memberType === 'GENCO'">
              <div class="ets-kv"><span>机组类型</span><b>{{ GEN_TYPE[a.params?.genType] || '—' }}</b></div>
              <div class="ets-kv"><span>装机容量</span><NumFlash :value="a.params?.installedCapacityMw" type="qty" suffix=" MW" :flash="false" /></div>
              <div class="ets-kv"><span>年度电量</span><NumFlash :value="a.params?.annualEnergyMwh" type="qty" suffix=" MWh" :flash="false" /></div>
              <div class="ets-kv"><span>变动成本</span><NumFlash :value="a.params?.variableCostPerMwh" suffix=" 元/MWh" :flash="false" /></div>
            </template>
            <template v-else-if="a.memberType === 'CONSUMER'">
              <div class="ets-kv"><span>年度需求</span><NumFlash :value="a.params?.annualDemandMwh" type="qty" suffix=" MWh" :flash="false" /></div>
              <div class="ets-kv"><span>可承受最高价</span><NumFlash :value="a.params?.maxAcceptPricePerMwh" suffix=" 元/MWh" :flash="false" /></div>
              <div class="ets-kv"><span>负荷特性</span><b>{{ LOAD_PROFILE[a.params?.loadProfile] || '—' }}</b></div>
              <div class="ets-kv"><span>目录电价</span><NumFlash :value="a.params?.retailBenchmarkPrice" suffix=" 元/MWh" :flash="false" /></div>
            </template>
            <template v-else>
              <div class="ets-kv"><span>代理电量</span><NumFlash :value="a.params?.agentDemandMwh" type="qty" suffix=" MWh" :flash="false" /></div>
              <div class="ets-kv"><span>代理户数</span><NumFlash :value="a.params?.agentUserCount" type="qty" suffix=" 户" :flash="false" /></div>
              <div class="ets-kv"><span>零售电价</span><NumFlash :value="a.params?.retailPricePerMwh" suffix=" 元/MWh" :flash="false" /></div>
              <div class="ets-kv"><span>风险偏好</span><b class="mono">{{ a.params?.riskAppetite ?? 3 }} / 5</b></div>
            </template>
          </div>

          <div class="ac-fund">
            <div class="f">
              <span>初始资金</span>
              <NumFlash :value="a.params?.initialCapital" type="money" :flash="false" strong />
            </div>
            <div class="f">
              <span>可用资金</span>
              <NumFlash :value="a.fund?.available" type="money" strong />
            </div>
            <div class="f">
              <span>冻结</span>
              <NumFlash :value="a.fund?.frozen" type="money" />
            </div>
            <div class="f">
              <span>保证金</span>
              <NumFlash :value="a.fund?.margin" type="money" />
            </div>
            <div class="f">
              <span>累计盈亏</span>
              <NumFlash :value="a.stat?.totalPnl" type="signedMoney" color-by="self" strong />
            </div>
            <div class="f">
              <span>收益率</span>
              <NumFlash :value="a.returnPct" type="pct" color-by="self" />
            </div>
          </div>

          <footer class="ac-foot">
            <span class="mono t">
              {{ lockedOf(a) && a.lockedAt ? `锁定于 ${datetime(a.lockedAt)}` : `建档于 ${datetime(a.createdAt)}` }}
            </span>
            <div class="btns" @click.stop>
              <a-tooltip :title="lockedOf(a) ? '参数已锁定，开盘后不可修改' : ''">
                <a-button size="small" :disabled="lockedOf(a)" @click="openEdit(a)">
                  <template #icon><Pencil :size="12" /></template>编辑
                </a-button>
              </a-tooltip>
              <a-tooltip :title="lockedOf(a) ? '参数已锁定，开盘后不可删除' : ''">
                <a-button size="small" danger :disabled="lockedOf(a)" @click="confirmDelete(a)">
                  <template #icon><Trash2 :size="12" /></template>删除
                </a-button>
              </a-tooltip>
            </div>
          </footer>
        </article>
      </div>
    </a-spin>

    <!-- 建档 / 编辑 -->
    <a-modal
      v-model:open="open"
      :title="editing ? `编辑市场主体 ${editing.code}` : '创建市场主体'"
      :width="720"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="submit"
    >
      <a-alert
        v-if="formLocked"
        type="warning"
        show-icon
        banner
        message="参数已锁定，开盘后不可修改"
        style="margin-bottom: 14px"
      />

      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" :disabled="formLocked">
        <div class="row">
          <a-form-item label="账户名称" name="name" class="c2">
            <a-input v-model:value="form.name" placeholder="如：华能一厂 / 苏州晶硅制造 / 华电售电" :maxlength="40" />
          </a-form-item>
          <a-form-item label="市场主体类型" name="memberType" class="c1">
            <a-select
              v-model:value="form.memberType"
              :disabled="!!editing"
              :options="memberOptions"
            />
            <div v-if="editing" class="hint">主体类型确定后不可更改，如需切换请删除后重建</div>
          </a-form-item>
        </div>

        <a-divider orientation="left" orientation-margin="0">
          <span class="dv">{{ MEMBER_TYPE[form.memberType] }}参数</span>
        </a-divider>

        <!-- 发电企业 -->
        <div v-if="form.memberType === 'GENCO'" class="row">
          <a-form-item label="机组类型" class="c2">
            <a-select v-model:value="form.params.genType" :options="genTypeOptions" />
          </a-form-item>
          <a-form-item label="装机容量 (MW)" class="c1">
            <a-input-number v-model:value="form.params.installedCapacityMw" :min="0" :step="50" />
          </a-form-item>
          <a-form-item label="年度可发电量 (MWh)" class="c1">
            <a-input-number v-model:value="form.params.annualEnergyMwh" :min="0" :step="100000" />
          </a-form-item>
          <a-form-item label="变动成本 (元/MWh)" class="c1">
            <a-input-number v-model:value="form.params.variableCostPerMwh" :min="0" :step="10" />
            <div class="hint">低于此价售电即亏损，是报价的成本底线</div>
          </a-form-item>
          <a-form-item label="非计划停运率" class="c1">
            <a-input-number v-model:value="form.params.forcedOutageRate" :min="0" :max="1" :step="0.01" />
          </a-form-item>
          <a-form-item label="最小出力系数" class="c1">
            <a-input-number v-model:value="form.params.minOutputRatio" :min="0" :max="1" :step="0.05" />
          </a-form-item>
          <a-form-item label="最大出力系数" class="c1">
            <a-input-number v-model:value="form.params.maxOutputRatio" :min="0" :max="1.2" :step="0.05" />
          </a-form-item>
        </div>

        <!-- 电力用户 -->
        <div v-else-if="form.memberType === 'CONSUMER'" class="row">
          <a-form-item label="年度用电需求 (MWh)" class="c1">
            <a-input-number v-model:value="form.params.annualDemandMwh" :min="0" :step="50000" />
          </a-form-item>
          <a-form-item label="可承受最高购电价 (元/MWh)" class="c1">
            <a-input-number v-model:value="form.params.maxAcceptPricePerMwh" :min="0" :step="10" />
          </a-form-item>
          <a-form-item label="负荷特性" class="c1">
            <a-select v-model:value="form.params.loadProfile" :options="loadProfileOptions" placeholder="请选择" />
          </a-form-item>
          <a-form-item label="目录电价基准 (元/MWh)" class="c1">
            <a-input-number v-model:value="form.params.retailBenchmarkPrice" :min="0" :step="10" />
            <div class="hint">用于测算相对目录电价的节约收益，留空按可承受价 ×1.05 推算</div>
          </a-form-item>
        </div>

        <!-- 售电公司 -->
        <div v-else class="row">
          <a-form-item label="代理用户总电量 (MWh)" class="c1">
            <a-input-number v-model:value="form.params.agentDemandMwh" :min="0" :step="50000" />
          </a-form-item>
          <a-form-item label="代理户数" class="c1">
            <a-input-number v-model:value="form.params.agentUserCount" :min="0" :step="10" :precision="0" />
          </a-form-item>
          <a-form-item label="零售套餐电价 (元/MWh)" class="c1">
            <a-input-number v-model:value="form.params.retailPricePerMwh" :min="0" :step="10" />
            <div class="hint">批发采购价低于该价即为代理价差收益</div>
          </a-form-item>
        </div>

        <a-divider orientation="left" orientation-margin="0"><span class="dv">资金与风控</span></a-divider>

        <div class="row">
          <a-form-item label="初始资金 (元)" class="c1">
            <a-input-number
              v-model:value="form.params.initialCapital"
              :min="cfg.initialCapitalMin"
              :max="cfg.initialCapitalMax"
              :step="1000000"
            />
            <div class="hint">{{ capitalHint }}，开盘时由系统一次性注资</div>
          </a-form-item>
          <a-form-item label="单笔申报上限 (MWh)" class="c1">
            <a-input-number v-model:value="form.params.maxSingleOrderMwh" :min="0" :step="1000" />
          </a-form-item>
          <a-form-item label="最大持仓 (MWh)" class="c1">
            <a-input-number v-model:value="form.params.maxPositionMwh" :min="0" :step="10000" />
          </a-form-item>
          <a-form-item label="风险偏好" class="c3">
            <a-slider
              v-model:value="form.params.riskAppetite"
              :min="1"
              :max="5"
              :marks="{ 1: '保守', 2: '稳健', 3: '中性', 4: '进取', 5: '激进' }"
            />
          </a-form-item>
        </div>

        <div class="tip-box">
          <TriangleAlert :size="13" />
          <span>
            开盘（模拟盘进入「交易中」）瞬间，以上全部参数将被永久锁定。
            风控会按单笔上限、最大持仓与可售电量校验每一笔申报，请按真实业务规模填写。
          </span>
        </div>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.accounts {
  flex: 1;
  min-height: 0;
  padding: 14px 16px 26px;
}

.lock-banner {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 11px 14px;
  margin-bottom: 12px;
  border: 1px solid rgba(245, 165, 36, 0.35);
  border-left: 3px solid var(--ets-warn);
  border-radius: var(--ets-radius);
  background: rgba(245, 165, 36, 0.08);
  color: var(--ets-warn);
}
.lock-banner strong {
  display: block;
  font-size: 13px;
  margin-bottom: 3px;
}
.lock-banner span {
  display: block;
  font-size: 12px;
  line-height: 1.7;
  color: #c8a468;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
}
.head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ets-text-weak);
}
.head b {
  color: var(--ets-text-strong);
}
.ops {
  display: flex;
  gap: 8px;
  flex: none;
}

.empty {
  padding: 22px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: 12px;
}

.acct-card {
  padding: 13px 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.acct-card:hover {
  border-color: #35507d;
}
.acct-card.on {
  border-color: var(--ets-accent);
  box-shadow: inset 0 0 0 1px rgba(47, 127, 255, 0.25);
}

.ac-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.ident {
  display: flex;
  gap: 10px;
  min-width: 0;
}
.badge {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.nm {
  font-size: 14px;
  font-weight: 700;
  color: var(--ets-text-strong);
  display: flex;
  align-items: center;
  gap: 6px;
}
.ty {
  font-size: 11px;
  color: var(--ets-text-faint);
  margin-top: 1px;
}
.ac-desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.65;
  color: var(--ets-text-faint);
}

.ac-params {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
  padding: 8px 10px;
  border-radius: 5px;
  background: var(--ets-raise);
}
.ac-params b {
  font-weight: 600;
  color: var(--ets-text);
}

.ac-fund {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 7px 10px;
}
.ac-fund .f {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.ac-fund .f > span {
  font-size: 10px;
  color: var(--ets-text-faint);
}

.ac-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ets-line);
}
.ac-foot .t {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.btns {
  display: flex;
  gap: 6px;
}

/* 表单栅格 */
.row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 14px;
}
.c1 {
  grid-column: span 1;
}
.c2 {
  grid-column: span 2;
}
.c3 {
  grid-column: span 3;
}
.hint {
  margin-top: 3px;
  font-size: 11px;
  color: var(--ets-text-faint);
  line-height: 1.55;
}
.dv {
  font-size: 12px;
  color: var(--ets-text-weak);
}
.tip-box {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 6px;
  padding: 9px 11px;
  border-radius: 5px;
  background: rgba(245, 165, 36, 0.07);
  border: 1px dashed rgba(245, 165, 36, 0.3);
  color: #c8a468;
  font-size: 11px;
  line-height: 1.7;
}

@media (max-width: 1000px) {
  .row {
    grid-template-columns: repeat(2, 1fr);
  }
  .c3 {
    grid-column: span 2;
  }
}
</style>
