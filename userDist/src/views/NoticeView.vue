<script setup>
import { computed, onMounted, ref } from 'vue'
import { AlertTriangle, Bell, Megaphone, Radio, Settings2, Trash2 } from 'lucide-vue-next'
import PanelBox from '@/components/common/PanelBox.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import StateTag from '@/components/common/StateTag.vue'
import { useNoticeStore } from '@/stores/notice'
import { useSimStore } from '@/stores/sim'
import { socketState } from '@/composables/useMarketSocket'
import { MARKET_EVENT_TYPE } from '@/utils/enums'
import { datetime, ratio } from '@/utils/format'

const noticeStore = useNoticeStore()
const simStore = useSimStore()

const kind = ref('all')

const KIND_META = {
  notice: { icon: Megaphone, label: '市场公告', tone: '#2f7fff' },
  event: { icon: AlertTriangle, label: '市场事件', tone: '#f5a524' },
  state: { icon: Settings2, label: '状态变更', tone: '#8b98a9' },
  system: { icon: Bell, label: '系统消息', tone: '#9254de' },
}

const feed = computed(() =>
  kind.value === 'all' ? noticeStore.feed : noticeStore.feed.filter((x) => x.kind === kind.value),
)

const counts = computed(() => {
  const c = { all: noticeStore.feed.length, notice: 0, event: 0, state: 0, system: 0 }
  for (const f of noticeStore.feed) c[f.kind] = (c[f.kind] || 0) + 1
  return c
})

const fundamental = computed(() => noticeStore.fundamental)

onMounted(() => noticeStore.markAllRead())
</script>

<template>
  <div class="notice-view">
    <PanelBox class="main" title="公告与市场事件" :sub="`${feed.length} 条`">
      <template #extra>
        <span class="ws" :class="socketState.status">
          <Radio :size="12" />
          {{ socketState.status === 'open' ? '实时推送已连接' : '推送通道未连接' }}
        </span>
        <a-button size="small" :disabled="!noticeStore.feed.length" @click="noticeStore.clear()">
          <template #icon><Trash2 :size="12" /></template>清空
        </a-button>
      </template>

      <div class="filter">
        <a-radio-group v-model:value="kind" size="small" button-style="solid">
          <a-radio-button value="all">全部 {{ counts.all }}</a-radio-button>
          <a-radio-button value="notice">公告 {{ counts.notice }}</a-radio-button>
          <a-radio-button value="event">市场事件 {{ counts.event }}</a-radio-button>
          <a-radio-button value="state">状态变更 {{ counts.state }}</a-radio-button>
          <a-radio-button value="system">系统 {{ counts.system }}</a-radio-button>
        </a-radio-group>
        <span class="tip">
          消息经 WebSocket 主题 <b class="mono">sim.{{ simStore.currentSimId }}</b> 实时推送，仅保留本次会话期间收到的内容
        </span>
      </div>

      <div class="timeline ets-scroll">
        <EmptyHint
          v-if="!feed.length"
          text="暂无消息"
          hint="管理员发布公告、注入市场事件，或模拟盘与交易场次状态变化时，消息会实时出现在这条时间线上。"
        >
          <template #icon><Megaphone :size="26" :stroke-width="1.4" /></template>
        </EmptyHint>

        <div v-for="item in feed" :key="item.id" class="tl-item" :class="[item.kind, item.level]">
          <div class="tl-rail">
            <span class="dot" :style="{ background: KIND_META[item.kind]?.tone }">
              <component :is="KIND_META[item.kind]?.icon || Bell" :size="11" />
            </span>
          </div>
          <div class="tl-card">
            <div class="tl-head">
              <span class="tt">{{ item.title }}</span>
              <StateTag v-if="item.kind !== 'state'" kind="notice" :value="item.level" />
              <a-tag :bordered="false">{{ KIND_META[item.kind]?.label }}</a-tag>
              <span class="mono ts">{{ datetime(item.ts) }}</span>
            </div>
            <p v-if="item.content" class="tl-body">{{ item.content }}</p>
            <div v-if="item.kind === 'event'" class="tl-meta">
              <span v-if="item.eventType">事件类型 <b>{{ MARKET_EVENT_TYPE[item.eventType] || item.eventType }}</b></span>
              <span v-if="item.magnitude !== undefined && item.magnitude !== null">
                冲击幅度
                <b :class="item.magnitude > 0 ? 'up' : 'down'">{{ ratio(item.magnitude) }}</b>
              </span>
              <span v-if="item.raw?.expireAt">影响至 <b class="mono">{{ datetime(item.raw.expireAt) }}</b></span>
            </div>
          </div>
        </div>
      </div>
    </PanelBox>

    <!-- 基本面 -->
    <PanelBox class="side" title="市场基本面" sub="影响机器人报价的基准">
      <div v-if="!fundamental" class="side-empty">
        <EmptyHint
          compact
          text="尚未收到基本面推送"
          hint="燃料价格、负荷水平与新能源出力会周期性漂移，变化时通过行情通道推送"
        />
      </div>
      <div v-else class="fund">
        <div class="fi">
          <span>基准电价</span>
          <b class="mono">{{ (fundamental.benchmarkPrice ?? 0).toFixed(2) }} 元/MWh</b>
        </div>
        <div class="fi"><span>燃煤价格指数</span><b class="mono">{{ (fundamental.coalPriceIdx ?? 1).toFixed(3) }}</b></div>
        <div class="fi"><span>燃气价格指数</span><b class="mono">{{ (fundamental.gasPriceIdx ?? 1).toFixed(3) }}</b></div>
        <div class="fi"><span>负荷系数</span><b class="mono">{{ (fundamental.loadFactor ?? 1).toFixed(3) }}</b></div>
        <div class="fi"><span>风电出力系数</span><b class="mono">{{ (fundamental.windFactor ?? 1).toFixed(3) }}</b></div>
        <div class="fi"><span>光伏出力系数</span><b class="mono">{{ (fundamental.solarFactor ?? 1).toFixed(3) }}</b></div>
        <div class="fi"><span>非停比例</span><b class="mono">{{ ratio(fundamental.outageRatio ?? 0) }}</b></div>
        <div class="fi"><span>更新时间</span><b class="mono">{{ datetime(fundamental.updatedAt) }}</b></div>
        <p class="fnote">
          指数以 1.0 为基准。燃料指数上行会推高发电侧成本报价，负荷系数上行意味着需求增强，
          新能源大发则会压低现货出清价。
        </p>
      </div>
    </PanelBox>
  </div>
</template>

<style scoped>
.notice-view {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 8px;
  padding: 8px;
}
.main,
.side {
  min-height: 0;
}

.ws {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--ets-text-faint);
  font-weight: 400;
}
.ws.open {
  color: var(--ets-down);
}
.ws.closed,
.ws.connecting {
  color: var(--ets-warn);
}

.filter {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 11px;
  border-bottom: 1px solid var(--ets-line);
  flex: none;
}
.filter :deep(.ant-radio-button-wrapper) {
  font-size: 11px;
  padding-inline: 10px;
}
.tip {
  font-size: 11px;
  color: var(--ets-text-faint);
  margin-left: auto;
}
.tip b {
  color: var(--ets-text-weak);
}

.timeline {
  flex: 1;
  min-height: 0;
  padding: 14px 16px 20px;
}
.tl-item {
  display: flex;
  gap: 12px;
}
.tl-rail {
  position: relative;
  width: 20px;
  flex: none;
  display: flex;
  justify-content: center;
}
.tl-rail::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: -2px;
  width: 1px;
  background: var(--ets-line);
}
.tl-item:last-child .tl-rail::before {
  bottom: 50%;
}
.dot {
  position: relative;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #0d1117;
  flex: none;
  margin-top: 4px;
}
.tl-card {
  flex: 1;
  min-width: 0;
  margin-bottom: 12px;
  padding: 9px 12px;
  border-radius: 6px;
  background: var(--ets-raise);
  border: 1px solid var(--ets-line);
}
.tl-item.URGENT .tl-card {
  border-color: rgba(245, 34, 45, 0.4);
  background: rgba(245, 34, 45, 0.07);
}
.tl-item.WARN .tl-card {
  border-color: rgba(245, 165, 36, 0.32);
}
.tl-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.tt {
  font-size: 13px;
  font-weight: 600;
  color: var(--ets-text-strong);
}
.tl-item.URGENT .tt {
  color: var(--ets-up);
}
.ts {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--ets-text-faint);
}
.tl-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.75;
  color: var(--ets-text-weak);
  white-space: pre-wrap;
  word-break: break-word;
}
.tl-meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 7px;
  padding-top: 6px;
  border-top: 1px dashed var(--ets-line);
  font-size: 11px;
  color: var(--ets-text-faint);
}
.tl-meta b {
  color: var(--ets-text);
}

.side-empty {
  flex: 1;
  display: flex;
  align-items: center;
}
.fund {
  padding: 10px 12px;
}
.fi {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--ets-line-soft);
  font-size: 12px;
}
.fi > span {
  color: var(--ets-text-weak);
}
.fi b {
  color: var(--ets-text-strong);
  font-weight: 600;
}
.fnote {
  margin: 12px 0 0;
  font-size: 11px;
  line-height: 1.8;
  color: var(--ets-text-faint);
}

@media (max-width: 1120px) {
  .notice-view {
    grid-template-columns: minmax(0, 1fr);
  }
  .side {
    display: none;
  }
}
</style>
