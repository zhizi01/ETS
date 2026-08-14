<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  CircleArrowDown,
  CloudLightning,
  CloudOff,
  Flame,
  Gavel,
  Megaphone,
  OctagonAlert,
  RefreshCw,
  RotateCcw,
  SlidersHorizontal,
  TrendingDown,
  Wind,
  Zap,
} from 'lucide-vue-next'
import PanelCard from '../components/PanelCard.vue'
import { eventApi } from '../api'
import { useSimStore } from '../stores/sim'
import { useWs } from '../composables/useWs'
import { dangerConfirm } from '../composables/useDanger'
import { MARKET_EVENT_TYPE, NOTICE_LEVEL, NOTICE_LEVEL_COLOR, labelOf } from '../utils/enums'
import { clampEventImpact, predictBenchmark } from '../utils/market'
import { datetime, duration, fromNow, num, pctSigned, price } from '../utils/format'

const simStore = useSimStore()
const simId = computed(() => simStore.currentId)

const fundamental = ref(null)
const activeEvents = ref([])
const history = ref([])
const notices = ref([])
const loading = ref(false)
const nowTs = ref(Date.now())
let tick = null

// ══════════════════ 取数 ══════════════════

async function loadAll() {
  if (!simId.value) {
    fundamental.value = null
    activeEvents.value = []
    history.value = []
    notices.value = []
    return
  }
  loading.value = true
  try {
    const [f, act, hist, nts] = await Promise.all([
      eventApi.fundamental(simId.value).catch(() => null),
      eventApi.active(simId.value).catch(() => []),
      eventApi.list(simId.value, 1, 20).catch(() => ({ list: [] })),
      eventApi.notices(simId.value, 15).catch(() => []),
    ])
    fundamental.value = f
    activeEvents.value = act || []
    history.value = hist?.list || []
    notices.value = nts || []
    syncSliders()
  } finally {
    loading.value = false
  }
}

// ══════════════════ 事件注入 ══════════════════

const draft = reactive({
  type: 'UNIT_OUTAGE',
  magnitude: 1,
  durationMinutes: 15,
  title: '',
  description: '',
  submitting: false,
})

const draftMeta = computed(() => MARKET_EVENT_TYPE.find((t) => t.value === draft.type) || MARKET_EVENT_TYPE[0])
const draftImpact = computed(() => draftMeta.value.impact * draft.magnitude)

const baseAnchor = computed(() => simStore.current?.cfg?.benchmarkPrice || 380)
const currentImpact = computed(() => fundamental.value?.eventImpact || 0)

/** 叠加本次事件后的总冲击与预估电价，与后端 clamp 口径一致 */
const projected = computed(() => {
  const nextImpact = clampEventImpact(currentImpact.value + draftImpact.value)
  const cur = fundamental.value?.benchmarkPrice || baseAnchor.value
  const next = predictBenchmark(baseAnchor.value, fundamental.value, nextImpact)
  return { nextImpact, cur, next, delta: cur > 0 ? next / cur - 1 : 0 }
})

/** 显式映射而不是整包动态取，保证 lucide 能被摇树 */
const EVENT_ICONS = {
  Flame,
  TrendingDown,
  Zap,
  CircleArrowDown,
  Wind,
  CloudOff,
  OctagonAlert,
  Gavel,
  CloudLightning,
}

function iconOf(name) {
  return EVENT_ICONS[name] || Zap
}

async function submitEvent() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  draft.submitting = true
  try {
    await eventApi.create({
      simId: simId.value,
      type: draft.type,
      magnitude: draft.magnitude,
      durationMinutes: draft.durationMinutes,
      title: draft.title?.trim() || null,
      description: draft.description?.trim() || null,
    })
    message.success(`已注入市场事件：${draftMeta.value.label}`)
    draft.title = ''
    draft.description = ''
    await loadAll()
  } finally {
    draft.submitting = false
  }
}

function revokeEvent(e) {
  dangerConfirm({
    title: `提前撤销「${e.title || labelOf('MarketEventType', e.type)}」？`,
    okText: '撤销事件',
    content: '撤销后该事件的价格冲击立即从基本面中剔除，基准电价会向常态回归。',
    success: '事件已撤销',
    onOk: async () => {
      await eventApi.remove(e.id)
      await loadAll()
    },
  })
}

// ══════════════════ 基本面直接调参 ══════════════════

const FACTORS = [
  { key: 'coalPriceIdx', label: '煤价指数', min: 0.5, max: 2, step: 0.01, base: 1, hint: '1.0 为基准，火电成本主锚' },
  { key: 'gasPriceIdx', label: '气价指数', min: 0.5, max: 2.5, step: 0.01, base: 1, hint: '影响燃机边际成本' },
  { key: 'loadFactor', label: '负荷系数', min: 0.6, max: 1.5, step: 0.01, base: 1, hint: '整体用电负荷水平' },
  { key: 'windFactor', label: '风电系数', min: 0.2, max: 2, step: 0.01, base: 1, hint: '风电出力，越高越压价' },
  { key: 'solarFactor', label: '光伏系数', min: 0.2, max: 2, step: 0.01, base: 1, hint: '光伏出力，越高越压价' },
  { key: 'outageRatio', label: '机组非停比例', min: 0, max: 0.3, step: 0.005, base: 0.03, hint: '非计划停运占比' },
]

const sliders = reactive({
  coalPriceIdx: 1,
  gasPriceIdx: 1,
  loadFactor: 1,
  windFactor: 1,
  solarFactor: 1,
  outageRatio: 0.03,
  benchmarkPrice: 380,
})
const overrideBenchmark = ref(false)
const savingFundamental = ref(false)

function syncSliders() {
  const f = fundamental.value
  if (!f) return
  FACTORS.forEach((x) => (sliders[x.key] = f[x.key] ?? x.base))
  sliders.benchmarkPrice = f.benchmarkPrice ?? 380
}

/** 拖动过程中的实时预估：直接指定基准价时就是该值，否则走合成模型 */
const sliderPreview = computed(() => {
  if (overrideBenchmark.value) return sliders.benchmarkPrice
  return predictBenchmark(baseAnchor.value, sliders, currentImpact.value)
})

function resetSliders() {
  FACTORS.forEach((x) => (sliders[x.key] = x.base))
  sliders.benchmarkPrice = baseAnchor.value
}

async function saveFundamental() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  savingFundamental.value = true
  try {
    const body = {}
    FACTORS.forEach((x) => (body[x.key] = sliders[x.key]))
    if (overrideBenchmark.value) body.benchmarkPrice = sliders.benchmarkPrice
    fundamental.value = await eventApi.updateFundamental(simId.value, body)
    syncSliders()
    message.success('基本面已更新，机器人下一轮 tick 起按新口径报价')
  } finally {
    savingFundamental.value = false
  }
}

// ══════════════════ 发布公告 ══════════════════

const notice = reactive({ title: '', content: '', level: 'INFO', submitting: false })

function publishNotice() {
  if (!simId.value) return message.warning('请先在顶栏选择演练场')
  if (!notice.title.trim()) return message.warning('请填写公告标题')
  if (!notice.content.trim()) return message.warning('请填写公告正文')

  const send = async () => {
    notice.submitting = true
    try {
      await eventApi.createNotice({
        simId: simId.value,
        title: notice.title.trim(),
        content: notice.content.trim(),
        level: notice.level,
      })
      message.success('公告已发布，全场用户端实时收到')
      notice.title = ''
      notice.content = ''
      await loadAll()
    } finally {
      notice.submitting = false
    }
  }

  if (notice.level === 'URGENT') {
    dangerConfirm({
      title: '以「紧急」级别发布公告？',
      okText: '发布紧急公告',
      content: h('div', { style: 'line-height:1.9' }, [
        h('div', { style: 'color:#f5222d;font-weight:600' }, notice.title.trim()),
        h('div', { style: 'color:#9aa4b0;margin-top:6px' }, '紧急公告会在所有在线用户端强提醒，请确认内容无误。'),
      ]),
      onOk: send,
    })
  } else {
    send()
  }
}

// ══════════════════ WebSocket ══════════════════

function onSim(data, msg) {
  const t = msg?.type
  if (t === 'fundamental') {
    fundamental.value = data
    if (!savingFundamental.value) syncSliders()
  } else if (t === 'event' && data) {
    activeEvents.value = [data, ...activeEvents.value.filter((e) => e.id !== data.id)]
    history.value = [data, ...history.value.filter((e) => e.id !== data.id)].slice(0, 20)
  } else if (t === 'eventRemoved' && data) {
    activeEvents.value = activeEvents.value.filter((e) => e.id !== data.id)
  } else if (t === 'notice' && data) {
    notices.value = [data, ...notices.value.filter((n) => n.id !== data.id)].slice(0, 15)
  }
}

useWs(() => (simId.value ? [`sim.${simId.value}`] : []), onSim)

onMounted(async () => {
  await simStore.load()
  await loadAll()
  tick = setInterval(() => (nowTs.value = Date.now()), 1000)
})

onBeforeUnmount(() => {
  if (tick) clearInterval(tick)
})

watch(simId, loadAll)

const liveEvents = computed(() =>
  activeEvents.value
    .map((e) => ({ ...e, remainMs: Math.max(0, (e.expireAt || 0) - nowTs.value) }))
    .sort((a, b) => a.remainMs - b.remainMs),
)
</script>

<template>
  <div class="page">
    <div v-if="!simId" class="empty-wrap">
      <a-empty description="请先在顶栏选择演练场" />
    </div>

    <template v-else>
      <!-- ══════ 当前基本面概览 ══════ -->
      <PanelCard title="市场基本面现状">
        <template #extra>
          <a-button size="small" :loading="loading" @click="loadAll">
            <RefreshCw :size="13" style="vertical-align: -2px" /> 刷新
          </a-button>
        </template>
        <div class="fund-strip">
          <div class="fs-main">
            <div class="fs-label">当前基准电价</div>
            <div class="fs-price num" :class="currentImpact > 0 ? 'up' : currentImpact < 0 ? 'down' : ''">
              {{ price(fundamental?.benchmarkPrice) }}
              <span class="fs-unit">元/MWh</span>
            </div>
            <div class="fs-sub">
              锚点 {{ price(baseAnchor) }} · 事件冲击
              <b :class="currentImpact > 0 ? 'up' : currentImpact < 0 ? 'down' : ''">{{ pctSigned(currentImpact) }}</b>
              · 更新于 {{ fundamental?.updatedAt ? fromNow(fundamental.updatedAt) : '—' }}
            </div>
          </div>
          <div class="fs-factors">
            <div v-for="f in FACTORS" :key="f.key" class="fs-item">
              <span class="fs-item-label">{{ f.label }}</span>
              <span
                class="num fs-item-value"
                :class="(fundamental?.[f.key] ?? f.base) > f.base ? 'up' : (fundamental?.[f.key] ?? f.base) < f.base ? 'down' : ''"
              >
                {{ num(fundamental?.[f.key] ?? f.base, f.key === 'outageRatio' ? 3 : 3) }}
              </span>
            </div>
          </div>
        </div>
      </PanelCard>

      <div class="row-2">
        <!-- ══════ 事件注入 ══════ -->
        <PanelCard title="事件注入">
          <template #extra>
            <span class="text-muted" style="font-size: 11px">九类事件 · 提交即刻生效并广播全场</span>
          </template>

          <div class="evt-grid">
            <button
              v-for="t in MARKET_EVENT_TYPE"
              :key="t.value"
              type="button"
              class="evt-card"
              :class="{ picked: draft.type === t.value, pos: t.impact > 0, neg: t.impact < 0 }"
              @click="draft.type = t.value"
            >
              <component :is="iconOf(t.icon)" :size="17" class="evt-icon" />
              <span class="evt-name">{{ t.label }}</span>
              <span class="evt-impact num" :class="t.impact > 0 ? 'up' : 'down'">
                {{ pctSigned(t.impact, 0) }}
              </span>
            </button>
          </div>

          <div class="evt-hint">{{ draftMeta.hint }}</div>

          <div class="evt-form">
            <div class="ef-row">
              <span class="ef-label">事件强度 magnitude</span>
              <a-slider
                v-model:value="draft.magnitude"
                :min="-1"
                :max="1"
                :step="0.05"
                :marks="{ '-1': '-1', 0: '0', 1: '1' }"
                class="ef-slider"
              />
              <a-input-number v-model:value="draft.magnitude" :min="-1" :max="1" :step="0.05" size="small" style="width: 84px" />
            </div>

            <div class="ef-row">
              <span class="ef-label">持续时长</span>
              <a-slider v-model:value="draft.durationMinutes" :min="1" :max="180" :step="1" class="ef-slider" />
              <a-input-number
                v-model:value="draft.durationMinutes"
                :min="1"
                :max="1440"
                size="small"
                style="width: 84px"
                addon-after="分"
              />
            </div>

            <div class="ef-row">
              <span class="ef-label">事件标题</span>
              <a-input v-model:value="draft.title" size="small" placeholder="留空则用事件类型名，如「600MW 主力机组非停」" />
            </div>

            <div class="ef-row">
              <span class="ef-label">事件描述</span>
              <a-input v-model:value="draft.description" size="small" placeholder="将随公告一并推送给全体学员" />
            </div>
          </div>

          <!-- 冲击预览 -->
          <div class="preview" :class="draftImpact > 0 ? 'p-up' : draftImpact < 0 ? 'p-down' : ''">
            <div class="pv-item">
              <span class="pv-label">本次事件冲击</span>
              <span class="pv-value num" :class="draftImpact > 0 ? 'up' : draftImpact < 0 ? 'down' : ''">
                {{ pctSigned(draftImpact) }}
              </span>
            </div>
            <span class="pv-arrow">＋</span>
            <div class="pv-item">
              <span class="pv-label">当前累计冲击</span>
              <span class="pv-value num">{{ pctSigned(currentImpact) }}</span>
            </div>
            <span class="pv-arrow">＝</span>
            <div class="pv-item">
              <span class="pv-label">生效后累计</span>
              <span class="pv-value num" :class="projected.nextImpact > 0 ? 'up' : projected.nextImpact < 0 ? 'down' : ''">
                {{ pctSigned(projected.nextImpact) }}
              </span>
            </div>
            <div class="pv-sep"></div>
            <div class="pv-item pv-price">
              <span class="pv-label">预计基准电价</span>
              <span class="pv-value num">
                {{ price(projected.cur) }}
                <span class="pv-to">→</span>
                <b :class="projected.delta > 0 ? 'up' : projected.delta < 0 ? 'down' : ''">{{ price(projected.next) }}</b>
                <span class="pv-delta" :class="projected.delta > 0 ? 'up' : projected.delta < 0 ? 'down' : ''">
                  （{{ pctSigned(projected.delta) }}）
                </span>
              </span>
            </div>
          </div>

          <a-button
            type="primary"
            danger
            block
            :loading="draft.submitting"
            style="margin-top: 12px"
            @click="submitEvent"
          >
            <Zap :size="14" style="vertical-align: -2px" />
            注入「{{ draftMeta.label }}」并广播全场
          </a-button>
        </PanelCard>

        <!-- ══════ 基本面调参 + 公告 ══════ -->
        <div class="right-col">
          <PanelCard title="基本面直接调参">
            <template #extra>
              <a-button size="small" type="text" @click="resetSliders">
                <RotateCcw :size="12" style="vertical-align: -2px" /> 恢复基准
              </a-button>
            </template>

            <div class="factor-list">
              <div v-for="f in FACTORS" :key="f.key" class="factor">
                <div class="fk-head">
                  <span class="fk-label">{{ f.label }}</span>
                  <span class="fk-hint">{{ f.hint }}</span>
                </div>
                <div class="fk-row">
                  <a-slider
                    v-model:value="sliders[f.key]"
                    :min="f.min"
                    :max="f.max"
                    :step="f.step"
                    class="fk-slider"
                  />
                  <a-input-number
                    v-model:value="sliders[f.key]"
                    :min="f.min"
                    :max="f.max"
                    :step="f.step"
                    :precision="3"
                    size="small"
                    style="width: 88px"
                  />
                </div>
              </div>

              <a-divider style="margin: 4px 0 10px" />

              <div class="factor">
                <div class="fk-head">
                  <a-checkbox v-model:checked="overrideBenchmark">
                    <span class="fk-label">直接指定基准电价</span>
                  </a-checkbox>
                  <span class="fk-hint">勾选后以该值为新锚点</span>
                </div>
                <div class="fk-row">
                  <a-slider
                    v-model:value="sliders.benchmarkPrice"
                    :min="Math.round(baseAnchor * 0.45)"
                    :max="Math.round(baseAnchor * 1.75)"
                    :step="1"
                    :disabled="!overrideBenchmark"
                    class="fk-slider"
                  />
                  <a-input-number
                    v-model:value="sliders.benchmarkPrice"
                    :min="1"
                    :step="5"
                    :precision="2"
                    :disabled="!overrideBenchmark"
                    size="small"
                    style="width: 88px"
                  />
                </div>
              </div>
            </div>

            <div class="fk-preview">
              <span class="text-sub">调整后基准电价预估</span>
              <span class="num fk-preview-value">{{ price(sliderPreview) }} 元/MWh</span>
              <span
                class="num"
                :class="sliderPreview > (fundamental?.benchmarkPrice || 0) ? 'up' : sliderPreview < (fundamental?.benchmarkPrice || 0) ? 'down' : ''"
              >
                {{ pctSigned(fundamental?.benchmarkPrice ? sliderPreview / fundamental.benchmarkPrice - 1 : 0) }}
              </span>
            </div>

            <a-button type="primary" block :loading="savingFundamental" style="margin-top: 10px" @click="saveFundamental">
              <SlidersHorizontal :size="14" style="vertical-align: -2px" /> 下发基本面参数
            </a-button>
          </PanelCard>

          <PanelCard title="发布公告">
            <a-form layout="vertical">
              <a-form-item label="公告级别">
                <a-radio-group v-model:value="notice.level" size="small" button-style="solid">
                  <a-radio-button v-for="l in NOTICE_LEVEL" :key="l.value" :value="l.value">
                    {{ l.label }}
                  </a-radio-button>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="标题">
                <a-input v-model:value="notice.title" :maxlength="60" placeholder="如：月度集中竞价申报窗口将于 15 分钟后关闭" />
              </a-form-item>
              <a-form-item label="正文">
                <a-textarea
                  v-model:value="notice.content"
                  :rows="3"
                  :maxlength="500"
                  show-count
                  placeholder="面向全体学员的交易组织提示、规则说明或风险警示"
                />
              </a-form-item>
              <a-button
                type="primary"
                :danger="notice.level === 'URGENT'"
                block
                :loading="notice.submitting"
                @click="publishNotice"
              >
                <Megaphone :size="14" style="vertical-align: -2px" /> 发布公告
              </a-button>
            </a-form>
          </PanelCard>
        </div>
      </div>

      <!-- ══════ 生效事件 + 历史 + 公告 ══════ -->
      <div class="row-3">
        <PanelCard title="生效中的事件">
          <template #extra>
            <a-tag :color="liveEvents.length ? 'error' : 'default'">{{ liveEvents.length }} 项</a-tag>
          </template>
          <div v-if="liveEvents.length" class="live-list">
            <div v-for="e in liveEvents" :key="e.id" class="live-item">
              <div class="li-head">
                <a-tag color="error" style="margin: 0">{{ labelOf('MarketEventType', e.type) }}</a-tag>
                <span class="li-title">{{ e.title }}</span>
                <span class="grow"></span>
                <a-button type="link" size="small" danger @click="revokeEvent(e)">提前撤销</a-button>
              </div>
              <div v-if="e.description" class="li-desc">{{ e.description }}</div>
              <div class="li-foot">
                <span class="num">
                  强度 <b :class="e.magnitude >= 0 ? 'up' : 'down'">{{ num(e.magnitude, 2) }}</b>
                </span>
                <span class="num">
                  冲击
                  <b :class="e.magnitude >= 0 ? 'up' : 'down'">
                    {{ pctSigned((MARKET_EVENT_TYPE.find((t) => t.value === e.type)?.impact || 0) * e.magnitude) }}
                  </b>
                </span>
                <span class="num text-warn">剩余 {{ duration(e.remainMs) }}</span>
              </div>
              <a-progress
                :percent="Math.min(100, (e.remainMs / Math.max(1, e.expireAt - e.effectiveAt)) * 100)"
                :show-info="false"
                size="small"
                stroke-color="#e3b341"
              />
            </div>
          </div>
          <a-empty v-else description="当前没有生效中的市场事件" />
        </PanelCard>

        <PanelCard title="事件注入历史" no-pad>
          <div class="hist">
            <div v-if="!history.length" class="hist-empty"><a-empty description="尚未注入过市场事件" /></div>
            <div v-for="e in history" :key="e.id" class="hist-item">
              <div class="hi-line">
                <a-tag :color="(MARKET_EVENT_TYPE.find((t) => t.value === e.type)?.impact || 0) > 0 ? 'red' : 'green'" style="margin: 0">
                  {{ labelOf('MarketEventType', e.type) }}
                </a-tag>
                <span class="hi-title">{{ e.title }}</span>
                <span class="grow"></span>
                <span class="num text-muted hi-time">{{ datetime(e.createdAt).slice(5, 16) }}</span>
              </div>
              <div class="hi-sub num">
                强度 {{ num(e.magnitude, 2) }} · 生效 {{ datetime(e.effectiveAt).slice(11, 16) }} ~
                {{ datetime(e.expireAt).slice(11, 16) }}
                <a-tag v-if="e.expireAt > nowTs" color="processing" style="margin-left: 6px">生效中</a-tag>
                <a-tag v-else style="margin-left: 6px">已过期</a-tag>
              </div>
            </div>
          </div>
        </PanelCard>

        <PanelCard title="已发布公告" no-pad>
          <div class="hist">
            <div v-if="!notices.length" class="hist-empty"><a-empty description="尚未发布公告" /></div>
            <div v-for="n in notices" :key="n.id" class="hist-item">
              <div class="hi-line">
                <a-tag :color="NOTICE_LEVEL_COLOR[n.level]" style="margin: 0">{{ labelOf('NoticeLevel', n.level) }}</a-tag>
                <span class="hi-title">{{ n.title }}</span>
                <span class="grow"></span>
                <span class="num text-muted hi-time">{{ fromNow(n.createdAt) }}</span>
              </div>
              <div class="hi-sub">{{ n.content }}</div>
            </div>
          </div>
        </PanelCard>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.empty-wrap {
  display: grid;
  place-items: center;
  height: 60vh;
}

/* ── 基本面概览条 ─────────────────────────────── */
.fund-strip {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
}

.fs-label {
  font-size: 11.5px;
  color: var(--text-muted);
}

.fs-price {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.15;
}

.fs-unit {
  margin-left: 5px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
}

.fs-sub {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.fs-factors {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px 22px;
  flex: 1;
  min-width: 420px;
}

.fs-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 10px;
  border-left: 1px solid var(--border-strong);
}

.fs-item-label {
  font-size: 11px;
  color: var(--text-muted);
}

.fs-item-value {
  font-size: 15px;
  font-weight: 600;
}

/* ── 布局 ─────────────────────────────────────── */
.row-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 12px;
}

.right-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1280px) {
  .row-2,
  .row-3 {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ── 事件卡片 ─────────────────────────────────── */
.evt-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.evt-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 11px 6px 9px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--bg-panel-2);
  color: var(--text-sub);
  cursor: pointer;
  transition: all 0.14s;
}

.evt-card:hover {
  border-color: #4b5563;
  background: #232a34;
  color: var(--text);
}

.evt-card.picked {
  border-color: var(--brand);
  background: rgba(47, 129, 247, 0.12);
  color: var(--text);
  box-shadow: 0 0 0 1px rgba(47, 129, 247, 0.35) inset;
}

.evt-card.picked.pos {
  border-color: #f5222d;
  background: rgba(245, 34, 45, 0.12);
  box-shadow: 0 0 0 1px rgba(245, 34, 45, 0.35) inset;
}

.evt-card.picked.neg {
  border-color: #00b578;
  background: rgba(0, 181, 120, 0.1);
  box-shadow: 0 0 0 1px rgba(0, 181, 120, 0.3) inset;
}

.evt-icon {
  opacity: 0.9;
}

.evt-name {
  font-size: 12px;
  font-weight: 600;
}

.evt-impact {
  font-size: 10.5px;
}

.evt-hint {
  margin: 10px 0 12px;
  padding: 7px 10px;
  border-left: 2px solid var(--brand);
  background: var(--brand-dim);
  border-radius: 0 4px 4px 0;
  font-size: 11.5px;
  color: var(--text-sub);
}

.evt-form {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.ef-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ef-label {
  flex: 0 0 108px;
  font-size: 12px;
  color: var(--text-sub);
}

.ef-slider {
  flex: 1;
  margin: 0;
}

/* ── 冲击预览 ─────────────────────────────────── */
.preview {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding: 11px 14px;
  border: 1px dashed var(--border-strong);
  border-radius: 6px;
  background: var(--bg-input);
}

.preview.p-up {
  border-color: rgba(245, 34, 45, 0.4);
}
.preview.p-down {
  border-color: rgba(0, 181, 120, 0.4);
}

.pv-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.pv-label {
  font-size: 10.5px;
  color: var(--text-muted);
}

.pv-value {
  font-size: 14px;
  font-weight: 600;
}

.pv-arrow {
  color: var(--text-muted);
  font-size: 13px;
}

.pv-sep {
  width: 1px;
  height: 30px;
  background: var(--border-strong);
}

.pv-price .pv-value {
  font-size: 15px;
}

.pv-to {
  margin: 0 5px;
  color: var(--text-muted);
}

.pv-delta {
  margin-left: 5px;
  font-size: 11.5px;
  font-weight: 400;
}

/* ── 基本面滑块 ───────────────────────────────── */
.factor-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.fk-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.fk-label {
  font-size: 12px;
  color: var(--text-sub);
}

.fk-hint {
  font-size: 10.5px;
  color: var(--text-muted);
}

.fk-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fk-slider {
  flex: 1;
  margin: 2px 0;
}

.fk-preview {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 12px;
  padding: 9px 12px;
  border-radius: 5px;
  background: var(--bg-input);
  font-size: 12px;
}

.fk-preview-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
}

/* ── 列表 ─────────────────────────────────────── */
.live-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}

.live-item {
  padding: 9px 11px;
  border: 1px solid rgba(245, 34, 45, 0.28);
  border-radius: 5px;
  background: rgba(245, 34, 45, 0.05);
}

.li-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.li-title {
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.li-desc {
  margin: 4px 0;
  font-size: 11.5px;
  color: var(--text-muted);
}

.li-foot {
  display: flex;
  gap: 16px;
  margin: 5px 0 3px;
  font-size: 11.5px;
  color: var(--text-sub);
}

.hist {
  max-height: 340px;
  overflow-y: auto;
}

.hist-empty {
  padding: 70px 0;
}

.hist-item {
  padding: 9px 14px;
  border-bottom: 1px solid var(--border);
}

.hist-item:hover {
  background: #1b222c;
}

.hi-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hi-title {
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hi-time {
  font-size: 11px;
  flex: 0 0 auto;
}

.hi-sub {
  margin-top: 3px;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.6;
}
</style>
