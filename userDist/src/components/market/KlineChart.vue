<script setup>
import { computed } from 'vue'
import EChart from '@/components/EChart.vue'
import { CHART_COLORS, baseAxis, baseTooltip } from '@/utils/echarts'
import { price, qty } from '@/utils/format'

const props = defineProps({
  bars: { type: Array, default: () => [] },
  interval: { type: String, default: '1m' },
  loading: { type: Boolean, default: false },
  height: { type: [Number, String], default: '100%' },
})

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 分钟级看时分，日线看月日 */
function labelOf(ts) {
  const d = new Date(ts)
  if (props.interval === '1d') return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  if (props.interval === '1h') return `${pad(d.getDate())}日${pad(d.getHours())}时`
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const option = computed(() => {
  const bars = props.bars || []
  const axis = bars.map((b) => labelOf(b.ts))
  // ECharts K 线数据顺序固定为 [open, close, low, high]
  const candles = bars.map((b) => [b.open, b.close, b.low, b.high])
  const volumes = bars.map((b, i) => ({
    value: b.volume,
    itemStyle: { color: b.close >= b.open ? 'rgba(245,34,45,0.55)' : 'rgba(0,181,120,0.55)' },
    _i: i,
  }))

  return {
    animation: false,
    grid: [
      { left: 8, right: 58, top: 12, height: '62%', containLabel: false },
      { left: 8, right: 58, top: '74%', height: '17%', containLabel: false },
    ],
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    tooltip: {
      ...baseTooltip,
      formatter: (ps) => {
        const i = ps[0]?.dataIndex ?? 0
        const b = bars[i]
        if (!b) return ''
        const up = b.close >= b.open
        const c = up ? CHART_COLORS.up : CHART_COLORS.down
        return `<div style="font-size:11px;line-height:1.75">
          <div style="color:#8b98a9">${labelOf(b.ts)}</div>
          <div>开 <b style="color:${c}">${price(b.open)}</b>　高 <b style="color:${c}">${price(b.high)}</b></div>
          <div>低 <b style="color:${c}">${price(b.low)}</b>　收 <b style="color:${c}">${price(b.close)}</b></div>
          <div>成交量 <b>${qty(b.volume)}</b> MWh</div>
          <div>成交额 <b>${price(b.amount)}</b> 元　${b.tradeCount || 0} 笔</div>
        </div>`
      },
    },
    xAxis: [
      { ...baseAxis, type: 'category', data: axis, boundaryGap: true, splitLine: { show: false }, axisLabel: { ...baseAxis.axisLabel, hideOverlap: true } },
      { ...baseAxis, type: 'category', gridIndex: 1, data: axis, axisLabel: { show: false }, splitLine: { show: false } },
    ],
    yAxis: [
      {
        ...baseAxis,
        scale: true,
        position: 'right',
        axisLine: { show: false },
        axisLabel: { ...baseAxis.axisLabel, formatter: (v) => v.toFixed(0) },
      },
      {
        ...baseAxis,
        gridIndex: 1,
        scale: true,
        position: 'right',
        splitNumber: 2,
        axisLine: { show: false },
        splitLine: { show: false },
        axisLabel: { ...baseAxis.axisLabel, formatter: (v) => (v >= 10000 ? (v / 10000).toFixed(1) + '万' : v) },
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: bars.length > 120 ? 60 : 0, end: 100 },
    ],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        data: candles,
        barMaxWidth: 12,
        itemStyle: {
          color: CHART_COLORS.up,
          color0: CHART_COLORS.down,
          borderColor: CHART_COLORS.up,
          borderColor0: CHART_COLORS.down,
        },
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: volumes,
        barMaxWidth: 12,
      },
    ],
  }
})
</script>

<template>
  <EChart
    :option="option"
    :height="height"
    :loading="loading"
    :empty="!bars.length"
    empty-text="该周期暂无成交，K 线在首笔成交后生成"
  />
</template>
