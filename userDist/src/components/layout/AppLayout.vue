<script setup>
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from './TopBar.vue'
import SideNav from './SideNav.vue'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { useNoticeStore } from '@/stores/notice'
import { useMarketSocket } from '@/composables/useMarketSocket'
import { usePolling } from '@/composables/usePolling'

const props = defineProps({ simId: { type: String, required: true } })

const router = useRouter()
const simStore = useSimStore()
const acctStore = useAccountStore()
const noticeStore = useNoticeStore()

async function bootstrap(simId) {
  if (!simId) return
  // 换盘时清掉上一个盘的消息流，避免两个演练场的公告混在一起
  noticeStore.clear()
  simStore.setCurrentSim(simId)
  simStore.loadEnums()
  try {
    await simStore.loadSim(simId)
  } catch {
    // 模拟盘可能已被管理员删除，退回大厅比停在死页面上好
    router.replace('/lobby')
    return
  }
  await Promise.allSettled([simStore.loadSessions(simId), acctStore.load(simId)])
  acctStore.refreshCurrent()
}

watch(() => props.simId, bootstrap, { immediate: true })

// 切换交易账户后立刻重算资金与持仓
watch(
  () => acctStore.currentId,
  (id) => id && acctStore.refreshCurrent(),
)

/**
 * 全局常驻订阅：模拟盘/场次状态与公告走 sim 主题；
 * 我的委托与协商走账户私有主题（后端 order./nego. 有推送，account. 主题目前无生产者，
 * 订阅上不会报错，资金仍以轮询为准）。
 */
const topics = computed(() => {
  const list = []
  if (props.simId) list.push(`sim.${props.simId}`)
  if (acctStore.currentId) {
    list.push(`order.${acctStore.currentId}`, `account.${acctStore.currentId}`, `nego.${acctStore.currentId}`)
  }
  return list
})

useMarketSocket({
  topics: () => topics.value,
  sim: (data, topic, type) => {
    noticeStore.ingest(data, type)
    simStore.applySimEvent(data)
  },
  account: () => acctStore.refreshCurrent(),
})

// 资金、持仓、排名没有专属推送主题，靠轮询保持新鲜
usePolling(() => acctStore.refreshCurrent(), 6000, { immediate: false })
</script>

<template>
  <div class="app-shell">
    <TopBar />
    <div class="app-body">
      <SideNav :sim-id="simId" />
      <main class="app-main">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--ets-bg);
}
.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.app-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
