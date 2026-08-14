<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, ChevronDown, LayoutGrid, LogOut, Radio, Zap } from 'lucide-vue-next'
import { Modal, message } from 'ant-design-vue'
import NumFlash from '@/components/common/NumFlash.vue'
import StateTag from '@/components/common/StateTag.vue'
import { useAuthStore } from '@/stores/auth'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { useNoticeStore } from '@/stores/notice'
import { socketState } from '@/composables/useMarketSocket'
import { MEMBER_TYPE } from '@/utils/enums'
import { datetime } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const simStore = useSimStore()
const acctStore = useAccountStore()
const noticeStore = useNoticeStore()

const bellOpen = ref(false)

const wsTone = computed(() => {
  switch (socketState.status) {
    case 'open':
      return { cls: 'ok', text: '行情已连接' }
    case 'connecting':
      return { cls: 'wait', text: '正在连接行情…' }
    case 'closed':
      return { cls: 'bad', text: `行情断开，第 ${socketState.retries} 次重连中` }
    default:
      return { cls: 'idle', text: '行情未连接' }
  }
})

const accountOptions = computed(() =>
  acctStore.accounts.map((a) => ({
    value: a.id,
    label: `${a.code} · ${a.name}`,
    type: MEMBER_TYPE[a.memberType] || a.memberType,
  })),
)

function onSwitchAccount(id) {
  acctStore.setCurrent(id)
  acctStore.refreshCurrent()
}

function openBell(open) {
  bellOpen.value = open
  if (open) noticeStore.markAllRead()
}

function gotoNotices() {
  bellOpen.value = false
  if (simStore.currentSimId) router.push(`/sim/${simStore.currentSimId}/notices`)
}

function confirmLogout() {
  Modal.confirm({
    title: '退出登录',
    content: '退出后需要重新登录才能继续申报交易，确认退出？',
    okText: '退出',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: async () => {
      await auth.logout()
      acctStore.reset()
      simStore.reset()
      noticeStore.clear()
      message.success('已退出登录')
      router.replace({ name: 'login' })
    },
  })
}
</script>

<template>
  <header class="topbar">
    <div class="brand" @click="router.push('/lobby')">
      <Zap :size="17" :stroke-width="2.2" class="logo" />
      <span class="name">ETS</span>
      <span class="slot">电力交易模拟器</span>
    </div>

    <div class="sim">
      <span class="sim-name" :title="simStore.simName">{{ simStore.simName }}</span>
      <StateTag kind="sim" :value="simStore.simState" :label="simStore.sim?.stateLabel" />
      <a-tooltip title="返回模拟盘大厅">
        <button class="ghost" @click="router.push('/lobby')"><LayoutGrid :size="14" /></button>
      </a-tooltip>
    </div>

    <div class="acct">
      <template v-if="accountOptions.length">
        <span class="ets-label">交易账户</span>
        <a-select
          :value="acctStore.currentId || undefined"
          size="small"
          style="width: 210px"
          placeholder="选择交易账户"
          :options="accountOptions"
          @change="onSwitchAccount"
        >
          <template #option="{ label, type }">
            <div class="opt">
              <span>{{ label }}</span>
              <span class="opt-type">{{ type }}</span>
            </div>
          </template>
        </a-select>
      </template>
      <a-button
        v-else
        size="small"
        type="primary"
        ghost
        @click="router.push(`/sim/${simStore.currentSimId}/accounts`)"
      >
        去创建交易账户
      </a-button>
    </div>

    <div class="metrics">
      <div class="metric">
        <span class="k">总权益</span>
        <NumFlash :value="acctStore.equity" type="money" strong suffix=" 元" />
      </div>
      <div class="metric">
        <span class="k">今日盈亏</span>
        <NumFlash :value="acctStore.todayPnl" type="signedMoney" color-by="self" strong />
      </div>
      <div class="metric">
        <span class="k">累计盈亏</span>
        <NumFlash :value="acctStore.totalPnl" type="signedMoney" color-by="self" />
      </div>
    </div>

    <div class="right">
      <a-tooltip :title="wsTone.text">
        <span class="ws" :class="wsTone.cls"><Radio :size="13" /><i class="led" /></span>
      </a-tooltip>

      <a-popover
        :open="bellOpen"
        trigger="click"
        placement="bottomRight"
        overlay-class-name="bell-pop"
        @open-change="openBell"
      >
        <template #content>
          <div class="bell-box">
            <div class="bell-head">
              <span>市场消息</span>
              <a-button type="link" size="small" @click="gotoNotices">查看全部</a-button>
            </div>
            <div v-if="!noticeStore.latest.length" class="bell-empty">
              暂无消息，公告与市场事件会通过行情通道实时推送
            </div>
            <ul v-else class="bell-list ets-scroll">
              <li v-for="n in noticeStore.latest" :key="n.id" :class="n.level">
                <div class="bt">
                  <StateTag kind="notice" :value="n.level" />
                  <span class="tt">{{ n.title }}</span>
                </div>
                <div v-if="n.content" class="bc">{{ n.content }}</div>
                <div class="bs mono">{{ datetime(n.ts) }}</div>
              </li>
            </ul>
          </div>
        </template>
        <button class="ghost bell">
          <a-badge :count="noticeStore.unread" :offset="[2, -2]" size="small">
            <Bell :size="15" />
          </a-badge>
        </button>
      </a-popover>

      <a-dropdown placement="bottomRight">
        <button class="user">
          <span class="avatar">{{ (auth.displayName || 'U').slice(0, 1) }}</span>
          <span class="uname">{{ auth.displayName }}</span>
          <ChevronDown :size="13" />
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item key="org" disabled>
              <span class="ets-label">{{ auth.orgName || '未填写单位' }}</span>
            </a-menu-item>
            <a-menu-divider />
            <a-menu-item key="lobby" @click="router.push('/lobby')">切换模拟盘</a-menu-item>
            <a-menu-item key="out" danger @click="confirmLogout">
              <LogOut :size="12" style="margin-right: 6px; vertical-align: -1px" />退出登录
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: var(--ets-topbar-h);
  flex: none;
  padding: 0 14px;
  background: linear-gradient(180deg, #171d26 0%, var(--ets-panel) 100%);
  border-bottom: 1px solid var(--ets-line);
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
  cursor: pointer;
  flex: none;
}
.logo {
  color: var(--ets-gold);
  align-self: center;
}
.name {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 1px;
  color: var(--ets-text-strong);
}
.slot {
  font-size: 11px;
  color: var(--ets-text-faint);
}

.sim {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-left: 14px;
  border-left: 1px solid var(--ets-line);
  min-width: 0;
}
.sim-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ets-text-strong);
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acct {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: none;
}
.opt {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.opt-type {
  color: var(--ets-text-faint);
  font-size: 11px;
}

.metrics {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-left: auto;
}
.metric {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.metric .k {
  font-size: 10px;
  color: var(--ets-text-faint);
  letter-spacing: 0.5px;
}

.right {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 14px;
  border-left: 1px solid var(--ets-line);
  flex: none;
}

.ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--ets-text-weak);
  cursor: pointer;
}
.ghost:hover {
  background: var(--ets-hover);
  color: var(--ets-text-strong);
}
.bell {
  width: 30px;
  height: 28px;
}

.ws {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 7px;
  border-radius: 20px;
  background: var(--ets-raise);
  color: var(--ets-text-weak);
}
.led {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ets-flat);
}
.ws.ok .led {
  background: var(--ets-down);
  box-shadow: 0 0 6px var(--ets-down);
}
.ws.ok {
  color: var(--ets-down);
}
.ws.wait .led {
  background: var(--ets-warn);
  animation: blink 1s infinite;
}
.ws.wait {
  color: var(--ets-warn);
}
.ws.bad .led {
  background: var(--ets-up);
  animation: blink 0.8s infinite;
}
.ws.bad {
  color: var(--ets-up);
}
@keyframes blink {
  50% {
    opacity: 0.25;
  }
}

.user {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 8px 0 4px;
  border: none;
  border-radius: 16px;
  background: var(--ets-raise);
  color: var(--ets-text);
  cursor: pointer;
}
.user:hover {
  background: var(--ets-hover);
}
.avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2f7fff, #7f4bff);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.uname {
  font-size: 12px;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bell-box {
  width: 330px;
}
.bell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ets-line);
}
.bell-empty {
  padding: 22px 8px;
  text-align: center;
  color: var(--ets-text-faint);
  font-size: 11px;
  line-height: 1.7;
}
.bell-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 330px;
}
.bell-list li {
  padding: 8px 2px;
  border-bottom: 1px dashed var(--ets-line-soft);
}
.bell-list li.URGENT .tt {
  color: var(--ets-up);
}
.bt {
  display: flex;
  align-items: center;
  gap: 4px;
}
.tt {
  font-size: 12px;
  font-weight: 600;
  color: var(--ets-text-strong);
}
.bc {
  font-size: 11px;
  color: var(--ets-text-weak);
  margin: 3px 0 2px;
  line-height: 1.6;
}
.bs {
  font-size: 10px;
  color: var(--ets-text-faint);
}

@media (max-width: 1400px) {
  .metric:nth-child(3) {
    display: none;
  }
}
@media (max-width: 1180px) {
  .slot,
  .uname {
    display: none;
  }
  .metrics {
    gap: 12px;
  }
}
</style>
