import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, GaugeChart } from 'echarts/charts'
import {
  DatasetComponent,
  DataZoomComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GaugeChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  MarkLineComponent,
  GraphicComponent,
  DatasetComponent,
  CanvasRenderer,
])

export const ETS_THEME = 'ets-dark'

const AXIS = {
  axisLine: { lineStyle: { color: '#30363d' } },
  axisTick: { show: false },
  axisLabel: { color: '#8b949e', fontSize: 11 },
  splitLine: { lineStyle: { color: 'rgba(48,54,61,0.55)', type: 'dashed' } },
}

echarts.registerTheme(ETS_THEME, {
  color: ['#2f81f7', '#f5222d', '#00b578', '#e3b341', '#a371f7', '#39c5cf', '#db6d28', '#8b949e'],
  backgroundColor: 'transparent',
  textStyle: { color: '#c9d1d9', fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif' },
  title: { textStyle: { color: '#e6edf3', fontSize: 13, fontWeight: 600 } },
  legend: { textStyle: { color: '#8b949e', fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
  tooltip: {
    backgroundColor: 'rgba(13,17,23,0.94)',
    borderColor: '#30363d',
    borderWidth: 1,
    textStyle: { color: '#e6edf3', fontSize: 12 },
    axisPointer: { lineStyle: { color: '#3b444e' }, crossStyle: { color: '#3b444e' } },
  },
  categoryAxis: AXIS,
  valueAxis: AXIS,
  timeAxis: AXIS,
  logAxis: AXIS,
})

export default echarts
