<script setup>
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import echarts, { ETS_THEME } from '../utils/charts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: [Number, String], default: 260 },
  /** 数据结构变化（如线条增删）时传 true，避免残留旧系列 */
  replaceMerge: { type: [String, Array], default: null },
  loading: { type: Boolean, default: false },
})

const el = ref(null)
const chart = shallowRef(null)
let observer = null

const cssHeight = () => (typeof props.height === 'number' ? `${props.height}px` : props.height)

function render() {
  if (!chart.value) return
  const opts = props.replaceMerge ? { replaceMerge: props.replaceMerge } : undefined
  chart.value.setOption(props.option || {}, opts)
}

function resize() {
  chart.value?.resize()
}

onMounted(() => {
  chart.value = echarts.init(el.value, ETS_THEME, { renderer: 'canvas' })
  render()
  // 侧边栏折叠、窗口缩放、栅格换行都会改变容器宽度，用 RO 兜住所有情况
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => resize())
    observer.observe(el.value)
  }
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})

watch(() => props.option, render, { deep: true })
watch(
  () => props.loading,
  (v) => (v ? chart.value?.showLoading('default', { maskColor: 'rgba(13,17,23,0.5)', textColor: '#8b949e' }) : chart.value?.hideLoading()),
)

defineExpose({ resize, instance: chart })
</script>

<template>
  <div ref="el" class="echart" :style="{ height: cssHeight() }"></div>
</template>

<style scoped>
.echart {
  width: 100%;
}
</style>
