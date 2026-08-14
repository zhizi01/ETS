<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bot,
  Calculator,
  CalendarClock,
  Cpu,
  LayoutDashboard,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  RefreshCw,
  Server,
  Users,
  Wifi,
  WifiOff,
  Zap,
} from 'lucide-vue-next'
import SimPicker from '../components/SimPicker.vue'
import { useAuthStore } from '../stores/auth'
import { useSimStore } from '../stores/sim'
import { useMetaStore } from '../stores/meta'
import { socket } from '../composables/useWs'
import { clock } from '../utils/format'
import { SIM_STATE_COLOR } from '../utils/enums'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const simStore = useSimStore()
const meta = useMetaStore()

const collapsed = ref(localStorage.getItem('ets_admin_collapsed') === '1')
const now = ref(Date.now())
let timer = null

const MENU = [
  {
    group: '运行监控',
    items: [{ key: 'dashboard', label: '监控大屏', icon: LayoutDashboard }],
  },
  {
    group: '演练组织',
    items: [
      { key: 'sims', label: '模拟盘管理', icon: Server },
      { key: 'sessions', label: '交易场次', icon: CalendarClock },
      { key: 'robots', label: '机器人管理', icon: Bot },
    ],
  },
  {
    group: '市场主体',
    items: [
      { key: 'members', label: '参与者管理', icon: Users },
      { key: 'intervene', label: '市场干预', icon: Zap },
    ],
  },
  {
    group: '清算与运维',
    items: [
      { key: 'settle', label: '结算管理', icon: Calculator },
      { key: 'sys', label: '系统监控', icon: Cpu },
    ],
  },
]

const selectedKeys = computed(() => [route.name])
const pageTitle = computed(() => route.meta?.title || '')
const wsOk = computed(() => socket.state.status === 'open')
const wsText = computed(() => {
  if (socket.state.status === 'open') return '推送正常'
  if (socket.state.status === 'connecting') return '连接中'
  return socket.state.retries ? `断线重连 ${socket.state.retries}` : '未连接'
})

function toggle() {
  collapsed.value = !collapsed.value
  localStorage.setItem('ets_admin_collapsed', collapsed.value ? '1' : '0')
}

function go(key) {
  if (route.name !== key) router.push({ name: key })
}

function logout() {
  socket.close()
  auth.logout()
  router.replace('/login')
}

onMounted(() => {
  meta.load()
  simStore.load()
  socket.connect()
  timer = setInterval(() => (now.value = Date.now()), 1000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

// 切换演练场时，页面各自的 watch 会重新拉数，这里只负责状态栏展示
watch(
  () => simStore.currentId,
  () => {},
)
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <!-- ── 左侧固定侧边栏 ─────────────────────────── -->
    <aside class="sider">
      <div class="brand">
        <div class="brand-mark">ETS</div>
        <div v-show="!collapsed" class="brand-text">
          <div class="brand-name">电力交易模拟器</div>
          <div class="brand-sub">管理控制台</div>
        </div>
      </div>

      <nav class="nav">
        <div v-for="g in MENU" :key="g.group" class="nav-group">
          <div v-show="!collapsed" class="nav-group-title">{{ g.group }}</div>
          <a-tooltip
            v-for="it in g.items"
            :key="it.key"
            :title="collapsed ? it.label : ''"
            placement="right"
          >
            <button
              class="nav-item"
              :class="{ active: selectedKeys.includes(it.key) }"
              type="button"
              @click="go(it.key)"
            >
              <component :is="it.icon" :size="16" />
              <span v-show="!collapsed">{{ it.label }}</span>
            </button>
          </a-tooltip>
        </div>
      </nav>

      <div class="sider-foot">
        <button class="nav-item" type="button" @click="toggle">
          <component :is="collapsed ? PanelLeftOpen : PanelLeftClose" :size="16" />
          <span v-show="!collapsed">收起侧栏</span>
        </button>
      </div>
    </aside>

    <!-- ── 顶栏 + 内容区 ──────────────────────────── -->
    <div class="main">
      <header class="topbar">
        <div class="row-gap">
          <h1 class="page-title">{{ pageTitle }}</h1>
          <a-tag v-if="simStore.current" :color="SIM_STATE_COLOR[simStore.current.state]">
            {{ simStore.current.stateLabel || simStore.current.state }}
          </a-tag>
        </div>

        <div class="row-gap topbar-right">
          <span class="topbar-label">当前演练场</span>
          <SimPicker :width="270" />
          <a-tooltip title="刷新演练场列表">
            <a-button size="small" type="text" :loading="simStore.loading" @click="simStore.load(true)">
              <RefreshCw :size="14" />
            </a-button>
          </a-tooltip>

          <span class="divider-v"></span>

          <span class="ws-badge" :class="wsOk ? 'ok' : 'bad'">
            <component :is="wsOk ? Wifi : WifiOff" :size="13" />
            {{ wsText }}
          </span>

          <span class="clock num">{{ clock(now) }}</span>

          <a-dropdown placement="bottomRight">
            <div class="admin-chip">
              <span class="avatar">{{ (auth.displayName || 'A').slice(0, 1) }}</span>
              <span class="nowrap">{{ auth.displayName }}</span>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item key="who" disabled>
                  账号：{{ auth.admin?.username || '—' }}
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="out" danger @click="logout">
                  <LogOut :size="13" style="vertical-align: -2px; margin-right: 6px" />退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="route.name" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ── 侧边栏 ──────────────────────────────────────── */
.sider {
  display: flex;
  flex-direction: column;
  width: var(--sider-w);
  flex: 0 0 var(--sider-w);
  background: #0f1319;
  border-right: 1px solid var(--border);
  transition: width 0.16s ease, flex-basis 0.16s ease;
}

.collapsed .sider {
  width: 60px;
  flex-basis: 60px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--header-h);
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
  flex: 0 0 auto;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 5px;
  background: linear-gradient(135deg, #2f81f7, #1f4fa8);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.brand-name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
}

.brand-sub {
  font-size: 10.5px;
  color: var(--text-muted);
  letter-spacing: 1.2px;
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 8px 12px;
}

.nav-group + .nav-group {
  margin-top: 10px;
}

.nav-group-title {
  padding: 6px 8px 4px;
  font-size: 10.5px;
  color: var(--text-muted);
  letter-spacing: 1.4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 34px;
  padding: 0 10px;
  margin-bottom: 2px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-sub);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
  white-space: nowrap;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text);
}

.nav-item.active {
  background: var(--brand-dim);
  color: #79b8ff;
  font-weight: 600;
}

.collapsed .nav-item {
  justify-content: center;
  padding: 0;
}

.sider-foot {
  padding: 8px;
  border-top: 1px solid var(--border);
}

/* ── 顶栏 ────────────────────────────────────────── */
.main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: var(--header-h);
  flex: 0 0 var(--header-h);
  padding: 0 16px;
  background: var(--bg-panel);
  border-bottom: 1px solid var(--border);
}

.page-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.topbar-right {
  gap: 10px;
}

.topbar-label {
  font-size: 12px;
  color: var(--text-muted);
}

.divider-v {
  width: 1px;
  height: 18px;
  background: var(--border-strong);
}

.ws-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11.5px;
  border: 1px solid transparent;
}

.ws-badge.ok {
  color: #00b578;
  background: rgba(0, 181, 120, 0.1);
  border-color: rgba(0, 181, 120, 0.3);
}

.ws-badge.bad {
  color: #e3b341;
  background: rgba(227, 179, 65, 0.1);
  border-color: rgba(227, 179, 65, 0.3);
}

.clock {
  font-size: 12.5px;
  color: var(--text-sub);
}

.admin-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 8px 3px 3px;
  border-radius: 16px;
  background: var(--bg-hover);
  cursor: pointer;
  font-size: 12.5px;
}

.admin-chip:hover {
  background: #2a323d;
}

.avatar {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}

/* ── 内容区 ──────────────────────────────────────── */
.content {
  flex: 1;
  overflow: auto;
  padding: 14px 16px 24px;
}
</style>
