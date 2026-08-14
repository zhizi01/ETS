<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Zap } from 'lucide-vue-next'
import NumFlash from '@/components/common/NumFlash.vue'
import { orderApi } from '@/api/user'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { TIME_IN_FORCE } from '@/utils/enums'
import { amount as fa, price as fp, qty as fq } from '@/utils/format'

const props = defineProps({
  session: { type: Object, default: null },
  depth: { type: Object, default: null },
  lastPrice: { type: Number, default: 0 },
})
const emit = defineEmits(['placed'])

const simStore = useSimStore()
const acctStore = useAccountStore()

const side = ref('BUY')
const priceVal = ref(null)
const qtyVal = ref(null)
const tif = ref('GFD')
const submitting = ref(false)

const cfg = computed(() => simStore.cfg)
const acct = computed(() => acctStore.current)
const summary = computed(() => acctStore.summary)

const tifOptions = computed(() => simStore.options('TimeInForce', TIME_IN_FORCE))

/** 只有 OPEN（滚动撮合）与 PRE_OPEN（集中申报）接受申报 */
const acceptable = computed(() => ['OPEN', 'PRE_OPEN'].includes(props.session?.state))
const blockReason = computed(() => {
  if (!simStore.tradable) return `模拟盘当前为「${simStore.sim?.stateLabel || simStore.simState}」，暂不接受申报`
  if (!acct.value) return '请先在「交易账户」页建立市场主体'
  if (!props.session) return '请选择一个交易场次'
  if (!acceptable.value) return `场次当前为「${props.session.stateLabel || props.session.state}」，不接受新申报`
  if (['BILATERAL', 'LISTING', 'SPOT_CLEARING'].includes(props.session.tradeMode)) {
    return `该场次为${props.session.tradeModeLabel || props.session.tradeMode}，请到对应页面操作`
  }
  return ''
})
const disabled = computed(() => !!blockReason.value)

const tick = computed(() => props.session?.tickSize || 0.01)
const lot = computed(() => props.session?.lotSize || 1)
const priceFloor = computed(() => props.session?.priceFloor || 0)
const priceCap = computed(() => props.session?.priceCap || 0)

/** 涨跌停按前收 × (1 ± priceLimitPct) 收敛，与后端 RiskService.checkPrice 一致 */
const limitBand = computed(() => {
  const prev = props.session?.prevClose || 0
  if (!prev) return null
  const pct = cfg.value.priceLimitPct || 0
  return { lo: prev * (1 - pct), hi: prev * (1 + pct) }
})

const effectiveRange = computed(() => {
  let lo = priceFloor.value || 0
  let hi = priceCap.value || 0
  if (limitBand.value) {
    lo = Math.max(lo, limitBand.value.lo)
    hi = hi ? Math.min(hi, limitBand.value.hi) : limitBand.value.hi
  }
  return { lo, hi }
})

const notional = computed(() => (Number(priceVal.value) || 0) * (Number(qtyVal.value) || 0))

/** 买入冻结货款+手续费，卖出冻结保证金 */
const frozenEstimate = computed(() =>
  side.value === 'BUY'
    ? notional.value * (1 + (cfg.value.feeRateBuy || 0))
    : notional.value * (cfg.value.marginRatio || 0),
)

const feeEstimate = computed(
  () => notional.value * (side.value === 'BUY' ? cfg.value.feeRateBuy || 0 : cfg.value.feeRateSell || 0),
)

const available = computed(() => summary.value?.available ?? acct.value?.fund?.available ?? 0)

/** 可售电量 = 年度可发电量 + 已购 − 已售（与后端 checkSellableEnergy 同口径） */
const sellable = computed(() => {
  if (cfg.value.allowNakedShort) return Infinity
  const p = acct.value?.params
  const s = acct.value?.stat
  const base = p?.annualEnergyMwh || 0
  return base + (s?.buyMwh || 0) - (s?.sellMwh || 0)
})

/** 按当前价格能买下的最大电量，受可用资金与单笔上限双重约束 */
const maxBuyQty = computed(() => {
  const p = Number(priceVal.value) || 0
  if (p <= 0) return 0
  const byFund = available.value / (p * (1 + (cfg.value.feeRateBuy || 0)))
  const cap = acct.value?.params?.maxSingleOrderMwh || Infinity
  return Math.max(0, Math.min(byFund, cap))
})

const overFund = computed(() => side.value === 'BUY' && frozenEstimate.value > available.value + 1e-6)
const overEnergy = computed(
  () => side.value === 'SELL' && Number(qtyVal.value) > sellable.value + 1e-6,
)
const overSingle = computed(() => {
  const cap = acct.value?.params?.maxSingleOrderMwh
  return !!cap && Number(qtyVal.value) > cap + 1e-6
})
const priceOut = computed(() => {
  const p = Number(priceVal.value)
  if (!p) return false
  const { lo, hi } = effectiveRange.value
  return (lo && p < lo - 1e-9) || (hi && p > hi + 1e-9)
})

// 换场次时把价格重置到一个合理的锚点，避免沿用上一个标的的价位
watch(
  () => props.session?.id,
  () => {
    priceVal.value = round(props.session?.lastPrice || props.session?.prevClose || props.lastPrice || cfg.value.benchmarkPrice)
    qtyVal.value = null
  },
  { immediate: true },
)

function round(v) {
  const t = tick.value || 0.01
  return v ? Math.round(v / t) * t : null
}

function fillFromBook(which) {
  if (props.depth?.hidden) {
    message.info('集中申报阶段盘口保密，无法按盘口价填入')
    return
  }
  const row = which === 'ask' ? props.depth?.asks?.[0] : props.depth?.bids?.[0]
  if (!row) {
    message.info('当前盘口该档位为空')
    return
  }
  priceVal.value = row.price
  if (!qtyVal.value) qtyVal.value = row.quantity
}

function fillLast() {
  const p = props.session?.lastPrice || props.lastPrice
  if (p) priceVal.value = round(p)
}

function setQtyPct(p) {
  const base = side.value === 'BUY' ? maxBuyQty.value : Math.min(sellable.value, acct.value?.params?.maxSingleOrderMwh ?? Infinity)
  if (!Number.isFinite(base) || base <= 0) {
    message.info('无法估算可申报电量，请手动填写')
    return
  }
  const raw = base * p
  const l = lot.value || 1
  qtyVal.value = Math.max(l, Math.floor(raw / l) * l)
}

/** 供交易终端在点击盘口档位时回填价格与方向 */
function applyQuote({ price: p, side: s }) {
  if (p) priceVal.value = p
  if (s) side.value = s
}

defineExpose({ applyQuote })

async function submit() {
  if (disabled.value) return
  if (!priceVal.value || !qtyVal.value) {
    message.warning('请填写申报价格与电量')
    return
  }
  submitting.value = true
  try {
    await orderApi.place({
      accountId: acct.value.id,
      sessionId: props.session.id,
      side: side.value,
      price: Number(priceVal.value),
      quantity: Number(qtyVal.value),
      timeInForce: tif.value,
    })
    message.success(`${side.value === 'BUY' ? '购电' : '售电'}申报已提交`)
    qtyVal.value = null
    emit('placed')
    acctStore.refreshCurrent()
  } catch {
    // 风控拒绝原因由后端返回中文，拦截器已弹出
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="ticket ets-scroll">
    <!-- 买卖切换 -->
    <div class="side-tabs">
      <button class="buy" :class="{ on: side === 'BUY' }" @click="side = 'BUY'">买入 · 购电</button>
      <button class="sell" :class="{ on: side === 'SELL' }" @click="side = 'SELL'">卖出 · 售电</button>
    </div>

    <div v-if="blockReason" class="block-tip">{{ blockReason }}</div>

    <div class="field">
      <div class="fl">
        <span>申报价格</span>
        <span class="unit">元/MWh</span>
      </div>
      <a-input-number
        v-model:value="priceVal"
        :min="0"
        :step="tick"
        :precision="2"
        :disabled="disabled"
        :status="priceOut ? 'error' : ''"
        size="large"
        class="big-num"
      />
      <div class="quick">
        <button :disabled="disabled" @click="fillFromBook('bid')">买一 {{ depth?.bids?.[0] ? fp(depth.bids[0].price) : '—' }}</button>
        <button :disabled="disabled" @click="fillFromBook('ask')">卖一 {{ depth?.asks?.[0] ? fp(depth.asks[0].price) : '—' }}</button>
        <button :disabled="disabled" @click="fillLast">最新价</button>
      </div>
      <div class="rng">
        <span v-if="effectiveRange.hi">
          可报区间 <b class="mono">{{ fp(effectiveRange.lo) }} ~ {{ fp(effectiveRange.hi) }}</b>
        </span>
        <span v-if="limitBand" class="lim">
          涨跌幅 ±{{ ((cfg.priceLimitPct || 0) * 100).toFixed(0) }}%（前收 {{ fp(session?.prevClose) }}）
        </span>
      </div>
      <div v-if="priceOut" class="err">申报价格超出允许区间，提交将被风控拒绝</div>
    </div>

    <div class="field">
      <div class="fl">
        <span>申报电量</span>
        <span class="unit">MWh</span>
      </div>
      <a-input-number
        v-model:value="qtyVal"
        :min="0"
        :step="lot"
        :precision="3"
        :disabled="disabled"
        :status="overFund || overEnergy || overSingle ? 'error' : ''"
        size="large"
        class="big-num"
        placeholder="0"
      />
      <div class="quick">
        <button v-for="p in [0.25, 0.5, 0.75, 1]" :key="p" :disabled="disabled" @click="setQtyPct(p)">
          {{ p === 1 ? '全部' : p * 100 + '%' }}
        </button>
      </div>
      <div class="rng">
        <span v-if="session">
          场次限额 <b class="mono">{{ fq(session.minQty) }} ~ {{ fq(session.maxQty) }}</b>
          · 步长 <b class="mono">{{ fq(session.lotSize) }}</b>
        </span>
      </div>
      <div v-if="overSingle" class="err">超过账户单笔申报上限 {{ fq(acct?.params?.maxSingleOrderMwh) }} MWh</div>
      <div v-else-if="overFund" class="err">可用资金不足，需冻结 {{ fa(frozenEstimate) }} 元</div>
      <div v-else-if="overEnergy" class="err">可售电量不足，剩余 {{ fq(sellable) }} MWh</div>
    </div>

    <div class="field">
      <div class="fl"><span>有效期</span></div>
      <a-radio-group v-model:value="tif" :disabled="disabled" size="small" button-style="solid" class="tif">
        <a-radio-button v-for="o in tifOptions" :key="o.value" :value="o.value">{{ o.value }}</a-radio-button>
      </a-radio-group>
      <div class="rng">{{ TIME_IN_FORCE[tif] }}</div>
    </div>

    <div class="calc">
      <div class="ets-kv">
        <span>可用资金</span>
        <NumFlash :value="available" type="money" suffix=" 元" />
      </div>
      <div class="ets-kv">
        <span>{{ side === 'BUY' ? '可购电量估算' : '可售电量' }}</span>
        <span class="mono">
          {{ side === 'BUY' ? fq(maxBuyQty) : Number.isFinite(sellable) ? fq(sellable) : '不限' }} MWh
        </span>
      </div>
      <div class="ets-kv">
        <span>申报金额</span>
        <span class="mono">{{ fa(notional) }} 元</span>
      </div>
      <div class="ets-kv">
        <span>预估手续费</span>
        <span class="mono">{{ fa(feeEstimate) }} 元</span>
      </div>
      <div class="ets-kv strong">
        <span>{{ side === 'BUY' ? '预计冻结货款' : '预计冻结保证金' }}</span>
        <span class="mono" :class="side === 'BUY' ? 'up' : 'down'">{{ fa(frozenEstimate) }} 元</span>
      </div>
    </div>

    <a-button
      class="submit"
      :class="side === 'BUY' ? 'b' : 's'"
      size="large"
      block
      :loading="submitting"
      :disabled="disabled"
      @click="submit"
    >
      <Zap :size="14" style="vertical-align: -2px; margin-right: 4px" />
      {{ side === 'BUY' ? '提交购电申报' : '提交售电申报' }}
    </a-button>

    <p class="foot-tip">
      {{
        session?.tradeMode === 'CALL_AUCTION'
          ? '集中竞价：申报期内只报单不撮合，闭市后按统一出清价成交，边际单按比例分配。'
          : '滚动撮合：价格优先、时间优先，成交价取先到方（Maker）报价。'
      }}
    </p>
  </div>
</template>

<style scoped>
.ticket {
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 11px;
  height: 100%;
  min-height: 0;
}

.side-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.side-tabs button {
  height: 32px;
  border: 1px solid var(--ets-line);
  border-radius: 5px;
  background: var(--ets-raise);
  color: var(--ets-text-weak);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: 0.13s;
}
.side-tabs .buy.on {
  background: var(--ets-up);
  border-color: var(--ets-up);
  color: #fff;
}
.side-tabs .sell.on {
  background: var(--ets-down);
  border-color: var(--ets-down);
  color: #fff;
}
.side-tabs button:not(.on):hover {
  color: var(--ets-text-strong);
  border-color: #3a4757;
}

.block-tip {
  padding: 7px 9px;
  border-radius: 5px;
  background: rgba(245, 165, 36, 0.09);
  border: 1px dashed rgba(245, 165, 36, 0.32);
  color: var(--ets-warn);
  font-size: 11px;
  line-height: 1.6;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.fl {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ets-text-weak);
}
.unit {
  color: var(--ets-text-faint);
}
.big-num :deep(input) {
  font-family: var(--ets-mono);
  font-size: 16px !important;
  font-weight: 600;
  text-align: right;
}

.quick {
  display: flex;
  gap: 4px;
}
.quick button {
  flex: 1;
  height: 22px;
  padding: 0 4px;
  border: 1px solid var(--ets-line);
  border-radius: 4px;
  background: transparent;
  color: var(--ets-text-weak);
  font-size: 10.5px;
  font-family: var(--ets-mono);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
}
.quick button:hover:not(:disabled) {
  border-color: var(--ets-accent);
  color: #7fb0ff;
}
.quick button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.rng {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 10.5px;
  color: var(--ets-text-faint);
  line-height: 1.5;
}
.rng b {
  color: var(--ets-text-weak);
  font-weight: 500;
}
.lim {
  color: #6d7a8b;
}
.err {
  font-size: 10.5px;
  color: var(--ets-up);
  line-height: 1.5;
}

.tif :deep(.ant-radio-button-wrapper) {
  font-size: 11px;
  padding-inline: 8px;
  font-family: var(--ets-mono);
}

.calc {
  padding: 8px 9px;
  border-radius: 5px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}
.calc .strong {
  margin-top: 3px;
  padding-top: 6px;
  border-top: 1px dashed var(--ets-line);
  font-weight: 600;
}

.submit {
  height: 38px !important;
  font-weight: 700 !important;
  border: none !important;
}
.submit.b {
  background: var(--ets-up) !important;
  color: #fff !important;
}
.submit.s {
  background: var(--ets-down) !important;
  color: #fff !important;
}
.submit:disabled {
  background: #2a323d !important;
  color: var(--ets-text-faint) !important;
}

.foot-tip {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ets-text-faint);
}
</style>
