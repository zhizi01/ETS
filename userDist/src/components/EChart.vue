<script setup>
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  option: { type: Object, default: () => ({}) },
  height: { type: [Number, String], default: 260 },
  /** 数据结构发生变化（换标的、换周期）时应传 true，避免残留旧 series */
  notMerge: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
})

const emit = defineEmits(['ready', 'click'])

const wrap = ref(null)
const el = ref(null)
const chart = shallowRef(null)
let observer = null

const styleHeight = () => (typeof props.height === 'number' ? `${props.height}px` : props.height)

function render() {
  if (!chart.value || props.empty) return
  chart.value.setOption(props.option || {}, { notMerge: props.notMerge, lazyUpdate: true })
}

function init() {
  if (!el.value || chart.value) return
  chart.value = echarts.init(el.value, null, { renderer: 'canvas' })
  chart.value.on('click', (params) => emit('click', params))
  render()
  emit('ready', chart.value)
}

function dispose() {
  if (chart.value) {
    chart.value.dispose()
    chart.value = null
  }
}

onMounted(() => {
  if (!props.empty) init()
  // 观察外壳而不是画布：空态时画布被 v-if 摘掉，观察它会在恢复后失效
  observer = new ResizeObserver(() => chart.value?.resize())
  if (wrap.value) observer.observe(wrap.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  dispose()
})

watch(() => props.option, render, { deep: true })

watch(
  () => props.empty,
  (isEmpty) => {
    if (isEmpty) dispose()
    // 空态期间 DOM 被 v-if 摘掉了，要等下一帧容器回来再初始化
    else requestAnimationFrame(init)
  },
)

watch(
  () => props.loading,
  (v) => (v ? chart.value?.showLoading('default', loadingCfg) : chart.value?.hideLoading()),
)

const loadingCfg = {
  text: '加载中',
  color: '#2f7fff',
  textColor: '#8b98a9',
  maskColor: 'rgba(13,17,23,0.55)',
  fontSize: 12,
}

defineExpose({ chart })
</script>

<template>
  <div ref="wrap" class="echart-wrap" :style="{ height: styleHeight() }">
    <div v-if="empty" class="echart-empty">
      <div class="pulse" />
      <span>{{ emptyText }}</span>
    </div>
    <div v-else ref="el" class="echart-canvas" />
  </div>
</template>

<style scoped>
.echart-wrap {
  position: relative;
  width: 100%;
  min-height: 80px;
}
.echart-canvas {
  width: 100%;
  height: 100%;
}
.echart-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  height: 100%;
  color: var(--ets-text-faint);
  font-size: 12px;
}
.pulse {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px dashed #303a48;
  animation: etsPulse 1.8s ease-in-out infinite;
}
@keyframes etsPulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.92);
  }
  50% {
    opacity: 0.75;
    transform: scale(1.04);
  }
}
</style>
