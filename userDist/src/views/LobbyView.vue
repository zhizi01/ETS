<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CalendarClock,
  CircleDollarSign,
  LogOut,
  RefreshCw,
  Users,
  Wallet,
  Zap,
} from 'lucide-vue-next'
import { Modal, message } from 'ant-design-vue'
import StateTag from '@/components/common/StateTag.vue'
import EmptyHint from '@/components/common/EmptyHint.vue'
import { useAuthStore } from '@/stores/auth'
import { useSimStore } from '@/stores/sim'
import { useAccountStore } from '@/stores/account'
import { accountApi } from '@/api/user'
import { date, money } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const simStore = useSimStore()
const acctStore = useAccountStore()

/** simId -> 我在该盘下的账户数 */
const mine = ref({})
const loading = computed(() => simStore.simsLoading)

const sims = computed(() =>
  [...simStore.sims].sort((a, b) => {
    const order = { RUNNING: 0, SETUP: 1, PAUSED: 2, ENDED: 3 }
    const d = (order[a.state] ?? 9) - (order[b.state] ?? 9)
    return d !== 0 ? d : (b.createdAt || 0) - (a.createdAt || 0)
  }),
)

async function load() {
  const list = await simStore.loadSims()
  // 每个盘的"我的账户数"只能逐盘查，数量很少，并发拉取即可
  const results = await Promise.allSettled(list.map((s) => accountApi.list(s.id, true)))
  const next = {}
  list.forEach((s, i) => {
    const r = results[i]
    next[s.id] = r.status === 'fulfilled' ? (r.value || []).length : 0
  })
  mine.value = next
}

onMounted(load)

function enter(sim) {
  if (sim.state === 'ENDED') {
    message.info('该模拟盘已结束，仅可查看历史数据')
  }
  simStore.setCurrentSim(sim.id)
  acctStore.reset()
  // 筹备中先去建档，其余情况直奔交易终端
  router.push(`/sim/${sim.id}/${sim.state === 'SETUP' ? 'accounts' : 'terminal'}`)
}

function logout() {
  Modal.confirm({
    title: '退出登录',
    content: '确认退出当前账号？',
    okText: '退出',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: async () => {
      await auth.logout()
      acctStore.reset()
      simStore.reset()
      router.replace({ name: 'login' })
    },
  })
}

const stateHint = {
  SETUP: '筹备中 · 可自由建立与调整市场主体参数',
  RUNNING: '交易中 · 各场次按计划开闭市，可实时申报',
  PAUSED: '已暂停 · 暂不接受新申报，机器人停摆',
  ENDED: '已结束 · 终盘结算完成，仅供复盘查看',
}
</script>

<template>
  <div class="lobby ets-scroll">
    <header class="head">
      <div class="hl">
        <div class="mark"><Zap :size="18" :stroke-width="2.4" /></div>
        <div>
          <h1>模拟盘大厅</h1>
          <p>选择一个演练场进入交易终端，{{ auth.displayName }}{{ auth.orgName ? ` · ${auth.orgName}` : '' }}</p>
        </div>
      </div>
      <div class="hr">
        <a-button size="small" :loading="loading" @click="load">
          <template #icon><RefreshCw :size="13" /></template>
          刷新
        </a-button>
        <a-button size="small" danger ghost @click="logout">
          <template #icon><LogOut :size="13" /></template>
          退出
        </a-button>
      </div>
    </header>

    <a-spin :spinning="loading">
      <div v-if="!sims.length && !loading" class="empty-wrap ets-panel">
        <EmptyHint
          text="当前没有可参与的模拟盘"
          hint="模拟盘由管理端创建。若后端刚启动，稍等片刻后点击刷新重试。"
        >
          <template #action>
            <a-button size="small" type="primary" @click="load">重新加载</a-button>
          </template>
        </EmptyHint>
      </div>

      <div v-else class="grid">
        <article
          v-for="s in sims"
          :key="s.id"
          class="card ets-panel"
          :class="s.state"
          @click="enter(s)"
        >
          <div class="c-head">
            <div class="c-title">
              <span class="nm">{{ s.name }}</span>
              <StateTag kind="sim" :value="s.state" :label="s.stateLabel" />
            </div>
            <span class="mono id">{{ s.id }}</span>
          </div>

          <p class="desc">{{ s.description || stateHint[s.state] || '—' }}</p>

          <div class="stats">
            <div class="st">
              <Users :size="13" />
              <div>
                <b class="mono">{{ s.userCount || 0 }}</b>
                <span>参与人数</span>
              </div>
            </div>
            <div class="st">
              <Wallet :size="13" />
              <div>
                <b class="mono">{{ s.accountCount || 0 }}</b>
                <span>市场主体</span>
              </div>
            </div>
            <div class="st">
              <CalendarClock :size="13" />
              <div>
                <b class="mono">{{ s.openSessionCount || 0 }}/{{ s.sessionCount || 0 }}</b>
                <span>在市场次</span>
              </div>
            </div>
            <div class="st">
              <CircleDollarSign :size="13" />
              <div>
                <b class="mono">{{ money(s.cfg?.benchmarkPrice) }}</b>
                <span>基准电价</span>
              </div>
            </div>
          </div>

          <footer class="c-foot">
            <div class="meta">
              <span>交割基准日 <b class="mono">{{ s.baseDate || date(s.createdAt) }}</b></span>
              <span class="dot">·</span>
              <span>机器人 <b class="mono">{{ s.robotAccountCount || 0 }}</b></span>
            </div>
            <div class="mine">
              <a-tag v-if="mine[s.id]" color="blue" :bordered="false">
                我的账户 {{ mine[s.id] }} 个
              </a-tag>
              <a-tag v-else :bordered="false">尚未建档</a-tag>
              <span class="go">进入<ArrowRight :size="13" /></span>
            </div>
          </footer>
        </article>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.lobby {
  height: 100%;
  padding: 26px 30px 40px;
  background: radial-gradient(90% 60% at 50% -10%, #141c28 0%, var(--ets-bg) 55%);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  max-width: 1360px;
  margin: 0 auto 22px;
}
.hl {
  display: flex;
  gap: 12px;
}
.mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(140deg, #e2b33c, #f5a524);
  color: #1a1205;
  flex: none;
}
.head h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.head p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--ets-text-weak);
}
.hr {
  display: flex;
  gap: 8px;
  flex: none;
}

.empty-wrap {
  max-width: 1360px;
  margin: 0 auto;
  padding: 30px;
}

.grid {
  max-width: 1360px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(370px, 1fr));
  gap: 14px;
}

.card {
  padding: 16px 16px 13px;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.15s, background 0.15s;
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.card:hover {
  border-color: #35507d;
  background: #1a212c;
  transform: translateY(-2px);
}
.card.RUNNING {
  border-left: 2px solid var(--ets-down);
}
.card.SETUP {
  border-left: 2px solid var(--ets-accent);
}
.card.PAUSED {
  border-left: 2px solid var(--ets-warn);
}
.card.ENDED {
  opacity: 0.72;
}

.c-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.c-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  flex-wrap: wrap;
}
.nm {
  font-size: 15px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.id {
  font-size: 11px;
  color: var(--ets-text-faint);
  flex: none;
}
.desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ets-text-weak);
  min-height: 34px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding: 10px;
  border-radius: 6px;
  background: var(--ets-raise);
}
.st {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  color: var(--ets-text-faint);
  min-width: 0;
}
.st b {
  display: block;
  font-size: 13px;
  color: var(--ets-text-strong);
  font-weight: 600;
  line-height: 1.25;
}
.st span {
  font-size: 10px;
  color: var(--ets-text-faint);
  white-space: nowrap;
}

.c-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--ets-line);
  font-size: 11px;
  color: var(--ets-text-faint);
}
.meta b {
  color: var(--ets-text-weak);
  font-weight: 500;
}
.meta .dot {
  margin: 0 6px;
}
.mine {
  display: flex;
  align-items: center;
  gap: 4px;
}
.go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--ets-accent);
  font-weight: 600;
}
</style>
