<script setup>
import { computed } from 'vue'
import { EyeOff } from 'lucide-vue-next'
import NumFlash from '@/components/common/NumFlash.vue'
import { price as fp, qty as fq } from '@/utils/format'

const props = defineProps({
  depth: { type: Object, default: null },
  levels: { type: Number, default: 5 },
  lastPrice: { type: Number, default: 0 },
})
const emit = defineEmits(['pick'])

const hidden = computed(() => !!props.depth?.hidden)

function padTo(list, n) {
  const out = (list || []).slice(0, n)
  while (out.length < n) out.push(null)
  return out
}

/** 卖盘自上而下由高到低，卖一贴近中间的价差行 */
const asks = computed(() => padTo(props.depth?.asks, props.levels).slice().reverse())
const bids = computed(() => padTo(props.depth?.bids, props.levels))

const maxQty = computed(() => {
  const all = [...(props.depth?.asks || []), ...(props.depth?.bids || [])]
  return all.reduce((m, x) => Math.max(m, x?.quantity || 0), 0) || 1
})

const spread = computed(() => {
  const a = props.depth?.asks?.[0]?.price
  const b = props.depth?.bids?.[0]?.price
  if (!a || !b) return null
  return a - b
})

function widthOf(row) {
  return row ? `${Math.min(100, (row.quantity / maxQty.value) * 100)}%` : '0%'
}
</script>

<template>
  <div class="depth">
    <!-- 集中申报阶段盘口保密 -->
    <div v-if="hidden" class="sealed">
      <EyeOff :size="20" :stroke-width="1.5" />
      <div class="t">集中申报中，盘口保密</div>
      <p>本场次处于集中竞价申报阶段，只接受申报不撮合，出清前不公开任何价格与数量档位。</p>
      <div class="counts">
        <div class="cc buy">
          <span>买方申报</span>
          <b class="mono">{{ depth?.bidOrderCount ?? 0 }}</b>
          <i>笔</i>
        </div>
        <div class="cc sell">
          <span>卖方申报</span>
          <b class="mono">{{ depth?.askOrderCount ?? 0 }}</b>
          <i>笔</i>
        </div>
      </div>
    </div>

    <div v-else class="book">
      <div class="bh">
        <span>价格</span>
        <span>电量(MWh)</span>
        <span>笔数</span>
      </div>

      <div class="side">
        <div
          v-for="(row, i) in asks"
          :key="`a${i}`"
          class="lv ask"
          :class="{ blank: !row }"
          @click="row && emit('pick', { price: row.price, side: 'BUY' })"
        >
          <i class="bar" :style="{ width: widthOf(row) }" />
          <span class="tag mono">卖{{ levels - i }}</span>
          <span class="p down mono">{{ row ? fp(row.price) : '—' }}</span>
          <span class="q mono">{{ row ? fq(row.quantity) : '—' }}</span>
          <span class="c mono">{{ row ? row.orderCount : '—' }}</span>
        </div>
      </div>

      <div class="mid">
        <NumFlash :value="lastPrice" strong :color-by="lastPrice ? 'self' : null" />
        <span class="sp">
          价差
          <b class="mono">{{ spread === null ? '—' : fp(spread) }}</b>
        </span>
      </div>

      <div class="side">
        <div
          v-for="(row, i) in bids"
          :key="`b${i}`"
          class="lv bid"
          :class="{ blank: !row }"
          @click="row && emit('pick', { price: row.price, side: 'SELL' })"
        >
          <i class="bar" :style="{ width: widthOf(row) }" />
          <span class="tag mono">买{{ i + 1 }}</span>
          <span class="p up mono">{{ row ? fp(row.price) : '—' }}</span>
          <span class="q mono">{{ row ? fq(row.quantity) : '—' }}</span>
          <span class="c mono">{{ row ? row.orderCount : '—' }}</span>
        </div>
      </div>

      <div class="foot">
        <span>买盘合计 <b class="mono up">{{ fq(depth?.bidTotalQty || 0) }}</b></span>
        <span>卖盘合计 <b class="mono down">{{ fq(depth?.askTotalQty || 0) }}</b></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.depth {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.book {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.bh {
  display: grid;
  grid-template-columns: 34px 1fr 1fr 42px;
  gap: 4px;
  padding: 4px 9px;
  font-size: 10px;
  color: var(--ets-text-faint);
  border-bottom: 1px solid var(--ets-line);
}
.bh span:first-child {
  grid-column: 2;
  text-align: right;
}
.bh span:nth-child(2) {
  text-align: right;
}
.bh span:nth-child(3) {
  text-align: right;
}

.side {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.lv {
  position: relative;
  display: grid;
  grid-template-columns: 34px 1fr 1fr 42px;
  gap: 4px;
  align-items: center;
  flex: 1;
  min-height: 20px;
  padding: 0 9px;
  font-size: 11.5px;
  cursor: pointer;
  overflow: hidden;
}
.lv.blank {
  cursor: default;
  color: var(--ets-text-faint);
}
.lv:hover:not(.blank) {
  background: var(--ets-hover);
}
.bar {
  position: absolute;
  right: 0;
  top: 2px;
  bottom: 2px;
  border-radius: 2px;
  pointer-events: none;
}
.ask .bar {
  background: rgba(0, 181, 120, 0.13);
}
.bid .bar {
  background: rgba(245, 34, 45, 0.13);
}
.tag {
  position: relative;
  font-size: 10px;
  color: var(--ets-text-faint);
}
.p,
.q,
.c {
  position: relative;
  text-align: right;
}
.q {
  color: var(--ets-text);
}
.c {
  color: var(--ets-text-faint);
  font-size: 10px;
}

.mid {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 9px;
  border-top: 1px solid var(--ets-line);
  border-bottom: 1px solid var(--ets-line);
  background: var(--ets-raise);
  font-size: 14px;
  flex: none;
}
.sp {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.sp b {
  color: var(--ets-text-weak);
}

.foot {
  display: flex;
  justify-content: space-between;
  padding: 5px 9px;
  border-top: 1px solid var(--ets-line);
  font-size: 10px;
  color: var(--ets-text-faint);
  flex: none;
}

.sealed {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 18px 16px;
  text-align: center;
  color: var(--ets-warn);
}
.sealed .t {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.sealed p {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ets-text-faint);
  max-width: 280px;
}
.counts {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
.cc {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 8px 18px;
  border-radius: 6px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}
.cc span {
  font-size: 10px;
  color: var(--ets-text-faint);
}
.cc b {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.15;
}
.cc i {
  font-size: 10px;
  font-style: normal;
  color: var(--ets-text-faint);
}
.cc.buy b {
  color: var(--ets-up);
}
.cc.sell b {
  color: var(--ets-down);
}
</style>
