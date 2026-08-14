<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import * as fmt from '@/utils/format'

/**
 * 等宽数字 + 变动高亮。
 * 价格/盈亏/成交量刷新时闪一下背景（涨红跌绿），让交易员用余光就能捕捉到变化。
 */
const props = defineProps({
  value: { type: [Number, String], default: null },
  /** price | qty | amount | money | signed | signedMoney | pct | ratio | raw */
  type: { type: String, default: 'price' },
  /** 'self' 按自身正负着色；传数字则按该数字正负着色；null 不着色 */
  colorBy: { type: [String, Number], default: null },
  flash: { type: Boolean, default: true },
  strong: { type: Boolean, default: false },
  dim: { type: Boolean, default: false },
  prefix: { type: String, default: '' },
  suffix: { type: String, default: '' },
  dash: { type: String, default: '—' },
})

const text = computed(() => {
  const v = props.value
  if (v === null || v === undefined || v === '') return props.dash
  if (props.type === 'raw') return String(v)
  const n = Number(v)
  if (!Number.isFinite(n)) return props.dash
  switch (props.type) {
    case 'qty':
      return fmt.qty(n, props.dash)
    case 'amount':
      return fmt.amount(n, props.dash)
    case 'money':
      return fmt.money(n, props.dash)
    case 'signed':
      return fmt.signed(n, fmt.amount, props.dash)
    case 'signedMoney':
      return fmt.signed(n, fmt.money, props.dash)
    case 'pct':
      return fmt.pct(n, 2, props.dash)
    case 'ratio':
      return fmt.ratio(n, 1, props.dash)
    default:
      return fmt.price(n, props.dash)
  }
})

const colorClass = computed(() => {
  if (props.colorBy === null) return ''
  const basis = props.colorBy === 'self' ? Number(props.value) : Number(props.colorBy)
  return fmt.dirClass(basis)
})

const flashClass = ref('')
let timer = null

watch(
  () => props.value,
  (next, prev) => {
    if (!props.flash) return
    const a = Number(next)
    const b = Number(prev)
    if (!Number.isFinite(a) || !Number.isFinite(b) || a === b) return
    flashClass.value = a > b ? 'flash-up' : 'flash-down'
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => (flashClass.value = ''), 780)
  },
)

onBeforeUnmount(() => timer && clearTimeout(timer))
</script>

<template>
  <span
    class="mono num"
    :class="[colorClass, flashClass, { strong, dim }]"
  >{{ prefix }}{{ text }}{{ suffix }}</span>
</template>

<style scoped>
.num {
  padding: 0 1px;
  white-space: nowrap;
}
.strong {
  font-weight: 600;
}
.dim {
  color: var(--ets-text-weak);
}
</style>
