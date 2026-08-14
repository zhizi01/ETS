<script setup>
import { computed } from 'vue'
import NumFlash from '@/components/common/NumFlash.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import { instrumentLabel } from '@/utils/enums'
import { qty } from '@/utils/format'

const props = defineProps({
  quotes: { type: Array, default: () => [] },
  modelValue: { type: String, default: '' },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

/** 年度 → 月度 → 多日 → 现货，符合交易员从长周期看到短周期的习惯 */
const ORDER = { ANNUAL: 0, MONTHLY: 1, MULTI_DAY: 2, SPOT_DA: 3 }

const rows = computed(() =>
  [...props.quotes].sort((a, b) => {
    const d = (ORDER[a.variety] ?? 9) - (ORDER[b.variety] ?? 9)
    return d !== 0 ? d : String(a.instrumentKey).localeCompare(String(b.instrumentKey))
  }),
)
</script>

<template>
  <div class="quote-list">
    <div class="qh">
      <span class="c-name">标的</span>
      <span class="c-num">最新价</span>
      <span class="c-num">涨跌幅</span>
      <span class="c-num">成交量</span>
    </div>

    <div class="qb ets-scroll">
      <a-skeleton v-if="loading && !rows.length" active :paragraph="{ rows: 6 }" style="padding: 12px" />

      <EmptyHint
        v-else-if="!rows.length"
        compact
        text="暂无行情标的"
        hint="标的在管理端创建交易场次时自动初始化"
      />

      <div
        v-for="q in rows"
        :key="q.instrumentKey"
        class="qr"
        :class="{ on: q.instrumentKey === modelValue }"
        @click="emit('update:modelValue', q.instrumentKey)"
      >
        <div class="c-name">
          <span class="nm">{{ q.name || instrumentLabel(q.instrumentKey) }}</span>
          <span class="ik mono">{{ q.instrumentKey }}</span>
        </div>
        <div class="c-num">
          <NumFlash :value="q.last" :color-by="q.change" strong />
        </div>
        <div class="c-num">
          <NumFlash :value="q.changePct" type="pct" :color-by="q.changePct" />
        </div>
        <div class="c-num vol mono">{{ qty(q.volume) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quote-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.qh,
.qr {
  display: grid;
  grid-template-columns: 1.6fr 1fr 0.85fr 1fr;
  gap: 4px;
  align-items: center;
  padding: 0 9px;
}
.qh {
  height: 24px;
  flex: none;
  font-size: 10px;
  color: var(--ets-text-faint);
  border-bottom: 1px solid var(--ets-line);
  letter-spacing: 0.4px;
}
.qb {
  flex: 1;
  min-height: 0;
}
.qr {
  height: 40px;
  cursor: pointer;
  border-bottom: 1px solid var(--ets-line-soft);
  transition: background 0.1s;
}
.qr:hover {
  background: var(--ets-hover);
}
.qr.on {
  background: var(--ets-accent-soft);
  box-shadow: inset 2px 0 0 var(--ets-accent);
}
.c-name {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.nm {
  font-size: 12px;
  color: var(--ets-text-strong);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ik {
  font-size: 9px;
  color: var(--ets-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.c-num {
  text-align: right;
  font-size: 12px;
}
.qh .c-num {
  font-size: 10px;
}
.vol {
  color: var(--ets-text-weak);
  font-size: 11px;
}
</style>
