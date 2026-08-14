<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { KeyRound, ShieldAlert, User, Zap } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { pubApi } from '../api'
import { socket } from '../composables/useWs'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: 'admin', password: 'admin123' })
const loading = ref(false)
const backendOk = ref(null)

async function probe() {
  try {
    const h = await pubApi.health()
    backendOk.value = !!h?.redis
  } catch {
    backendOk.value = false
  }
}

async function submit() {
  if (!form.username || !form.password) {
    message.warning('请填写管理员账号与口令')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    socket.reconnect()
    message.success('登录成功，欢迎回到调度台')
    router.replace(route.query.redirect || '/dashboard')
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

onMounted(probe)
</script>

<template>
  <div class="login-page">
    <div class="grid-bg"></div>

    <div class="login-wrap">
      <!-- 左侧品牌区 -->
      <section class="hero">
        <div class="hero-badge">
          <Zap :size="14" />
          ELECTRICITY TRADING SIMULATOR
        </div>
        <h1 class="hero-title">电力交易<br />模拟器管理控制台</h1>
        <p class="hero-desc">
          组织中长期与现货演练、编排交易场次时间轴、投放策略机器人、注入市场事件，
          全流程掌控一场电力市场化交易的推演。
        </p>
        <ul class="hero-list">
          <li><span></span>年度 / 月度 / 月内多日 / 日前现货 四大品种</li>
          <li><span></span>双边协商 · 集中竞价 · 挂牌摘牌 · 滚动撮合 · 现货出清</li>
          <li><span></span>六种策略机器人与九类市场事件实时干预</li>
        </ul>
      </section>

      <!-- 右侧登录卡片 -->
      <section class="card">
        <div class="card-head">
          <h2>管理员登录</h2>
          <p>仅限交易中心运营人员访问</p>
        </div>

        <a-form layout="vertical" @submit.prevent="submit">
          <a-form-item label="管理员账号">
            <a-input
              v-model:value="form.username"
              size="large"
              placeholder="请输入管理员账号"
              autocomplete="username"
              @press-enter="submit"
            >
              <template #prefix><User :size="15" class="ipt-icon" /></template>
            </a-input>
          </a-form-item>

          <a-form-item label="登录口令">
            <a-input-password
              v-model:value="form.password"
              size="large"
              placeholder="请输入登录口令"
              autocomplete="current-password"
              @press-enter="submit"
            >
              <template #prefix><KeyRound :size="15" class="ipt-icon" /></template>
            </a-input-password>
          </a-form-item>

          <a-button type="primary" size="large" block :loading="loading" @click="submit">
            进入控制台
          </a-button>
        </a-form>

        <div class="card-foot">
          <span class="status">
            <i class="dot" :class="backendOk === null ? 'dot-wait' : backendOk ? 'dot-ok' : 'dot-bad'"></i>
            {{ backendOk === null ? '正在探测后端…' : backendOk ? '后端服务正常（:8080）' : '后端不可达，请先启动 ets-service' }}
          </span>
          <span class="tip">
            <ShieldAlert :size="12" />
            管理端操作会写入审计日志
          </span>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background: radial-gradient(1200px 600px at 20% 0%, #16233a 0%, #0d1117 55%);
  overflow: hidden;
}

.grid-bg {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(47, 129, 247, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 129, 247, 0.06) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(circle at 30% 20%, #000 0%, transparent 70%);
  pointer-events: none;
}

.login-wrap {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 56px;
  align-items: center;
  width: 100%;
  max-width: 980px;
}

/* ── 品牌区 ─────────────────────────────────────── */
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid rgba(47, 129, 247, 0.35);
  border-radius: 12px;
  background: rgba(47, 129, 247, 0.1);
  color: #79b8ff;
  font-size: 10.5px;
  letter-spacing: 1.4px;
}

.hero-title {
  margin: 18px 0 14px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.28;
  letter-spacing: 1px;
  background: linear-gradient(120deg, #ffffff 20%, #79b8ff 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero-desc {
  max-width: 480px;
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--text-sub);
}

.hero-list {
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
}

.hero-list li {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 9px;
  font-size: 12.5px;
  color: var(--text-sub);
}

.hero-list span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #2f81f7;
  box-shadow: 0 0 8px rgba(47, 129, 247, 0.9);
  flex: 0 0 5px;
}

/* ── 登录卡片 ───────────────────────────────────── */
.card {
  padding: 28px 26px 20px;
  background: rgba(22, 27, 34, 0.92);
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(8px);
}

.card-head h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.card-head p {
  margin: 5px 0 20px;
  font-size: 12px;
  color: var(--text-muted);
}

.ipt-icon {
  color: var(--text-muted);
}

.card-foot {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-muted);
}

.status {
  display: flex;
  align-items: center;
  gap: 7px;
}

.tip {
  display: flex;
  align-items: center;
  gap: 5px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.dot-ok {
  background: #00b578;
  box-shadow: 0 0 6px #00b578;
}
.dot-bad {
  background: #f5222d;
  box-shadow: 0 0 6px #f5222d;
}
.dot-wait {
  background: #e3b341;
}

@media (max-width: 880px) {
  .login-wrap {
    grid-template-columns: 1fr;
    gap: 28px;
    max-width: 400px;
  }
  .hero {
    display: none;
  }
}
</style>
