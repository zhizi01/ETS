import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart, PieChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  CandlestickChart,
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  AxisPointerComponent,
  MarkLineComponent,
  GraphicComponent,
  CanvasRenderer,
])

export const CHART_COLORS = {
  up: '#f5222d',
  down: '#00b578',
  accent: '#2f7fff',
  gold: '#e2b33c',
  purple: '#9254de',
  cyan: '#13c2c2',
  text: '#8b98a9',
  textStrong: '#d7dde7',
  line: '#262d38',
  lineSoft: '#1e242e',
  panel: '#161b22',
}

/** 所有图表共用的深色底座，避免每张图重复写坐标轴样式 */
export const baseAxis = {
  axisLine: { lineStyle: { color: CHART_COLORS.line } },
  axisTick: { show: false },
  axisLabel: {
    color: CHART_COLORS.text,
    fontSize: 10,
    fontFamily: 'JetBrains Mono, Consolas, monospace',
  },
  splitLine: { lineStyle: { color: CHART_COLORS.lineSoft, type: 'dashed' } },
}

export const baseTooltip = {
  trigger: 'axis',
  backgroundColor: 'rgba(22,27,34,0.96)',
  borderColor: CHART_COLORS.line,
  borderWidth: 1,
  padding: [7, 10],
  textStyle: { color: CHART_COLORS.textStrong, fontSize: 11 },
  axisPointer: {
    type: 'cross',
    lineStyle: { color: '#3d4859', type: 'dashed' },
    crossStyle: { color: '#3d4859' },
    label: { backgroundColor: '#2b3543', color: '#d7dde7', fontSize: 10 },
  },
}

export default echarts
