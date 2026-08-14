<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  BellRing,
  CandlestickChart,
  Handshake,
  LineChart,
  Sun,
  Tags,
  Trophy,
  Wallet,
} from 'lucide-vue-next'
import { useSimStore } from '@/stores/sim'
import { useNoticeStore } from '@/stores/notice'

const props = defineProps({ simId: { type: String, required: true } })

const route = useRoute()
const simStore = useSimStore()
const noticeStore = useNoticeStore()

const ITEMS = [
  { key: 'accounts', label: '交易账户', icon: Wallet, hint: '主体建档与参数' },
  { key: 'terminal', label: '交易终端', icon: CandlestickChart, hint: '撮合与竞价申报' },
  { key: 'listings', label: '挂牌摘牌', icon: Tags, hint: '挂牌大厅' },
  { key: 'negotiations', label: '双边协商', icon: Handshake, hint: '邀约与还价' },
  { key: 'spot', label: '日前现货', icon: Sun, hint: '24 点曲线申报' },
  { key: 'assets', label: '我的资产', icon: LineChart, hint: '资金持仓合同' },
  { key: 'performance', label: '绩效排行', icon: Trophy, hint: '盈亏与排名' },
  { key: 'notices', label: '公告事件', icon: BellRing, hint: '市场消息' },
]

const active = computed(() => route.meta?.nav || '')
const setupHint = computed(() => simStore.isSetup)
const badge = computed(() => noticeStore.unread)

function to(key) {
  return `/sim/${props.simId}/${key}`
}
</script>

<template>
  <nav class="side-nav ets-scroll">
    <router-link
      v-for="item in ITEMS"
      :key="item.key"
      class="nav-item"
      :class="{ on: active === item.key }"
      :to="to(item.key)"
    >
      <component :is="item.icon" :size="15" :stroke-width="1.7" />
      <span class="lb">{{ item.label }}</span>
      <span v-if="item.key === 'notices' && badge" class="dot">{{ badge > 99 ? '99+' : badge }}</span>
      <span v-else-if="item.key === 'accounts' && setupHint" class="pill">筹备</span>
    </router-link>

    <div class="nav-foot">
      <div class="tip">
        {{ setupHint ? '筹备中：请先完成主体建档并填齐参数，开盘后参数将被锁定' : '交易时段：申报即时进入撮合队列' }}
      </div>
    </div>
  </nav>
</template>

<style scoped>
.side-nav {
  width: var(--ets-nav-w);
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 8px 10px;
  background: var(--ets-panel);
  border-right: 1px solid var(--ets-line);
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--ets-radius);
  color: var(--ets-text-weak);
  font-size: 13px;
  text-decoration: none;
  transition: background 0.12s, color 0.12s;
  position: relative;
}
.nav-item:hover {
  background: var(--ets-hover);
  color: var(--ets-text-strong);
}
.nav-item.on {
  background: var(--ets-accent-soft);
  color: #7fb0ff;
  font-weight: 600;
}
.nav-item.on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 2px;
  border-radius: 1px;
  background: var(--ets-accent);
}
.lb {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
}
.dot {
  min-width: 17px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--ets-up);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  font-family: var(--ets-mono);
}
.pill {
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
  border-radius: 3px;
  background: rgba(47, 127, 255, 0.18);
  color: #6ba4ff;
  font-size: 10px;
}
.nav-foot {
  margin-top: auto;
  padding: 10px 4px 0;
}
.tip {
  font-size: 11px;
  line-height: 1.7;
  color: var(--ets-text-faint);
  border-top: 1px dashed var(--ets-line);
  padding-top: 9px;
}

@media (max-width: 1100px) {
  .side-nav {
    width: 52px;
    padding: 8px 6px;
  }
  .lb,
  .nav-foot {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .dot,
  .pill {
    position: absolute;
    top: 2px;
    right: 2px;
  }
}
</style>
