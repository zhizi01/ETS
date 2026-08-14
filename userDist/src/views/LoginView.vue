<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Activity, Building2, KeyRound, Phone, User, UserRound, Zap } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref('login')
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
  nickname: '',
  orgName: '',
  phone: '',
})

const isLogin = computed(() => mode.value === 'login')

const rules = computed(() => ({
  username: [
    { required: true, message: '请输入用户名' },
    { min: 3, max: 32, message: '用户名长度需在 3~32 之间' },
  ],
  password: [
    { required: true, message: '请输入密码' },
    { min: 6, max: 64, message: '密码长度需在 6~64 之间' },
  ],
  ...(isLogin.value ? {} : { nickname: [{ required: true, message: '请输入昵称' }] }),
}))

const formRef = ref(null)

const DEMOS = [
  { username: 'demo1', password: 'demo123', tag: '演示账号 A' },
  { username: 'demo2', password: 'demo123', tag: '演示账号 B' },
]

function useDemo(d) {
  mode.value = 'login'
  form.username = d.username
  form.password = d.password
}

function toggle() {
  mode.value = isLogin.value ? 'register' : 'login'
  formRef.value?.clearValidate?.()
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (isLogin.value) {
      await auth.login({ username: form.username, password: form.password })
      message.success('登录成功，欢迎回到交易大厅')
    } else {
      await auth.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        orgName: form.orgName,
        phone: form.phone,
      })
      message.success('注册成功，已自动登录')
    }
    router.replace(route.query.redirect || '/lobby')
  } catch {
    // 失败提示由 axios 拦截器统一弹出
  } finally {
    submitting.value = false
  }
}

const FEATURES = [
  { t: '中长期 · 现货全链路', d: '年度、月度、月内多日与日前现货，四类交易方式一站演练' },
  { t: '真实撮合内核', d: '价格优先时间优先的滚动撮合、集中竞价统一出清、挂牌摘牌与双边协商' },
  { t: '机器人对手盘', d: '做市商、趋势、成本定价等策略机器人提供全天候流动性' },
]
</script>

<template>
  <div class="login-page">
    <!-- 左：品牌区 -->
    <aside class="brand-side">
      <div class="grid-bg" />
      <div class="glow" />

      <div class="brand-head">
        <div class="mark"><Zap :size="22" :stroke-width="2.4" /></div>
        <div>
          <h1>ETS 电力交易模拟器</h1>
          <p>Electricity Trading Simulator · 电力市场化交易仿真演练平台</p>
        </div>
      </div>

      <ul class="feats">
        <li v-for="f in FEATURES" :key="f.t">
          <Activity :size="14" class="fi" />
          <div>
            <strong>{{ f.t }}</strong>
            <span>{{ f.d }}</span>
          </div>
        </li>
      </ul>

      <div class="tower">
        <svg viewBox="0 0 460 150" preserveAspectRatio="none">
          <g stroke="#2b3a52" fill="none" stroke-width="1.4">
            <path d="M40 150 L40 30 M40 30 L10 40 M40 30 L70 40 M22 90 L58 90 M28 60 L52 60" />
            <path d="M230 150 L230 20 M230 20 L196 32 M230 20 L264 32 M210 86 L250 86 M216 54 L244 54" />
            <path d="M420 150 L420 34 M420 34 L392 44 M420 34 L448 44 M404 92 L436 92" />
          </g>
          <g stroke="#3a4d6b" fill="none" stroke-width="1">
            <path d="M10 40 Q135 78 196 32" />
            <path d="M70 40 Q150 82 264 32" />
            <path d="M264 32 Q340 76 392 44" />
            <path d="M196 32 Q320 84 448 44" />
          </g>
        </svg>
      </div>

      <div class="brand-foot">
        <span class="mono">v1.0</span>
        <span>后端 Spring Boot 4.1 · Redis 撮合内核 · WebSocket 实时行情</span>
      </div>
    </aside>

    <!-- 右：表单区 -->
    <main class="form-side">
      <div class="form-box">
        <div class="switcher">
          <button :class="{ on: isLogin }" @click="mode = 'login'">登录</button>
          <button :class="{ on: !isLogin }" @click="mode = 'register'">注册</button>
        </div>

        <h2>{{ isLogin ? '欢迎回来' : '创建交易账号' }}</h2>
        <p class="sub">
          {{ isLogin ? '登录后进入模拟盘大厅，选择演练场开始申报' : '注册后可在任意筹备中的模拟盘建立市场主体' }}
        </p>

        <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" @finish="submit">
          <a-form-item label="用户名" name="username">
            <a-input v-model:value="form.username" size="large" placeholder="登录用户名" autocomplete="username">
              <template #prefix><User :size="14" /></template>
            </a-input>
          </a-form-item>

          <a-form-item label="密码" name="password">
            <a-input-password
              v-model:value="form.password"
              size="large"
              placeholder="登录密码"
              :autocomplete="isLogin ? 'current-password' : 'new-password'"
              @press-enter="submit"
            >
              <template #prefix><KeyRound :size="14" /></template>
            </a-input-password>
          </a-form-item>

          <template v-if="!isLogin">
            <a-form-item label="昵称" name="nickname">
              <a-input v-model:value="form.nickname" size="large" placeholder="展示名称">
                <template #prefix><UserRound :size="14" /></template>
              </a-input>
            </a-form-item>
            <a-form-item label="所属单位" name="orgName">
              <a-input v-model:value="form.orgName" size="large" placeholder="如：华能江苏能源销售有限公司">
                <template #prefix><Building2 :size="14" /></template>
              </a-input>
            </a-form-item>
            <a-form-item label="联系电话" name="phone">
              <a-input v-model:value="form.phone" size="large" placeholder="选填">
                <template #prefix><Phone :size="14" /></template>
              </a-input>
            </a-form-item>
          </template>

          <a-button type="primary" size="large" block :loading="submitting" html-type="submit">
            {{ isLogin ? '登 录' : '注册并登录' }}
          </a-button>
        </a-form>

        <div class="alt">
          {{ isLogin ? '还没有账号？' : '已有账号？' }}
          <a @click="toggle">{{ isLogin ? '立即注册' : '返回登录' }}</a>
        </div>

        <div v-if="isLogin" class="demos">
          <span class="ets-label">演示账号</span>
          <button v-for="d in DEMOS" :key="d.username" class="demo" @click="useDemo(d)">
            <span class="mono">{{ d.username }}</span>
            <span class="dt">{{ d.tag }}</span>
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100%;
  background: var(--ets-bg);
}

/* ── 品牌区 ─────────────────────────────── */
.brand-side {
  position: relative;
  flex: 1.15;
  min-width: 0;
  padding: 54px 56px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: radial-gradient(120% 90% at 15% 0%, #16233a 0%, #0d1117 60%);
  border-right: 1px solid var(--ets-line);
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(#1a2432 1px, transparent 1px),
    linear-gradient(90deg, #1a2432 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(110% 80% at 20% 10%, #000 20%, transparent 75%);
  opacity: 0.55;
}
.glow {
  position: absolute;
  width: 460px;
  height: 460px;
  left: -140px;
  top: -160px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(47, 127, 255, 0.2), transparent 66%);
  filter: blur(10px);
}

.brand-head {
  position: relative;
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.mark {
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(140deg, #e2b33c, #f5a524);
  color: #1a1205;
  box-shadow: 0 6px 20px rgba(226, 179, 60, 0.22);
}
.brand-head h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 1px;
  color: var(--ets-text-strong);
}
.brand-head p {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--ets-text-faint);
  letter-spacing: 0.3px;
}

.feats {
  position: relative;
  list-style: none;
  margin: 46px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 430px;
}
.feats li {
  display: flex;
  gap: 11px;
  align-items: flex-start;
}
.fi {
  color: var(--ets-accent);
  margin-top: 3px;
  flex: none;
}
.feats strong {
  display: block;
  font-size: 13px;
  color: var(--ets-text-strong);
  font-weight: 600;
}
.feats span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ets-text-weak);
}

.tower {
  position: relative;
  margin-top: auto;
  opacity: 0.85;
}
.tower svg {
  width: 100%;
  height: 150px;
  display: block;
}

.brand-foot {
  position: relative;
  display: flex;
  gap: 12px;
  align-items: center;
  padding-top: 16px;
  border-top: 1px solid var(--ets-line);
  font-size: 11px;
  color: var(--ets-text-faint);
}

/* ── 表单区 ─────────────────────────────── */
.form-side {
  flex: 1;
  min-width: 430px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  overflow: auto;
}
.form-box {
  width: 100%;
  max-width: 370px;
}
.switcher {
  display: inline-flex;
  padding: 3px;
  border-radius: 8px;
  background: var(--ets-raise);
  margin-bottom: 26px;
}
.switcher button {
  border: none;
  background: transparent;
  color: var(--ets-text-weak);
  font-size: 13px;
  padding: 5px 22px;
  border-radius: 6px;
  cursor: pointer;
  transition: 0.15s;
}
.switcher button.on {
  background: var(--ets-accent);
  color: #fff;
  font-weight: 600;
}

.form-box h2 {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
  color: var(--ets-text-strong);
}
.sub {
  margin: 7px 0 24px;
  font-size: 12px;
  color: var(--ets-text-weak);
  line-height: 1.7;
}

.alt {
  margin-top: 16px;
  text-align: center;
  font-size: 12px;
  color: var(--ets-text-weak);
}
.alt a {
  color: var(--ets-accent);
  cursor: pointer;
}

.demos {
  margin-top: 26px;
  padding-top: 16px;
  border-top: 1px dashed var(--ets-line);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.demo {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 5px 11px;
  border: 1px solid var(--ets-line);
  border-radius: 6px;
  background: var(--ets-panel);
  color: var(--ets-text);
  cursor: pointer;
  transition: 0.15s;
}
.demo:hover {
  border-color: var(--ets-accent);
  background: var(--ets-accent-soft);
}
.dt {
  font-size: 10px;
  color: var(--ets-text-faint);
}

@media (max-width: 980px) {
  .brand-side {
    display: none;
  }
  .form-side {
    min-width: 0;
  }
}
</style>
