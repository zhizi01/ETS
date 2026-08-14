<script setup>
import EmptyHint from '@/components/common/EmptyHint.vue'
import { price, qty, time } from '@/utils/format'

defineProps({
  trades: { type: Array, default: () => [] },
})
</script>

<template>
  <div class="tape">
    <div class="th">
      <span>时间</span>
      <span>成交价</span>
      <span>电量</span>
      <span>方向</span>
    </div>
    <div class="tb ets-scroll">
      <EmptyHint
        v-if="!trades.length"
        compact
        text="暂无成交"
        hint="逐笔成交会在撮合发生时实时推送"
      />
      <div v-for="t in trades" :key="t.id" class="tr" :class="t.takerSide === 'BUY' ? 'b' : 's'">
        <span class="mono tm">{{ time(t.tradedAt) }}</span>
        <span class="mono tp">{{ price(t.price) }}</span>
        <span class="mono tq">{{ qty(t.quantity) }}</span>
        <span class="td">{{ t.takerSide === 'BUY' ? '主动买' : '主动卖' }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tape {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.th,
.tr {
  display: grid;
  grid-template-columns: 62px 1fr 1fr 52px;
  gap: 4px;
  padding: 0 9px;
  align-items: center;
}
.th {
  height: 22px;
  flex: none;
  font-size: 10px;
  color: var(--ets-text-faint);
  border-bottom: 1px solid var(--ets-line);
}
.th span:nth-child(2),
.th span:nth-child(3) {
  text-align: right;
}
.th span:last-child {
  text-align: right;
}
.tb {
  flex: 1;
  min-height: 0;
}
.tr {
  height: 21px;
  font-size: 11.5px;
  border-bottom: 1px solid var(--ets-line-soft);
}
.tr:hover {
  background: var(--ets-hover);
}
.tm {
  color: var(--ets-text-faint);
  font-size: 10.5px;
}
.tp,
.tq {
  text-align: right;
}
.tq {
  color: var(--ets-text-weak);
}
.td {
  text-align: right;
  font-size: 10px;
}
.tr.b .tp,
.tr.b .td {
  color: var(--ets-up);
}
.tr.s .tp,
.tr.s .td {
  color: var(--ets-down);
}
</style>
