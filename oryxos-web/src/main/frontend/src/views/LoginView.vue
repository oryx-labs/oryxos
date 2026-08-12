<script setup>
import { ref, reactive, onMounted } from 'vue'
import logoUrl from '../assets/logo.svg'

const emit = defineEmits(['logined'])

const form = reactive({ username: '', password: '' })
const busy = ref(false)
const error = ref(null)
const showPassword = ref(false)
const userInput = ref(null)

const features = [
  {
    icon: 'M4 7h16M4 12h16M4 17h10',
    title: '多模型路由',
    desc: 'DeepSeek / Qwen / Kimi / Ollama 间切换，零代码修改，显式映射',
  },
  {
    icon: 'M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83',
    title: '自实现 ReAct 循环',
    desc: '手写推理–行动循环，工具调度完全可控，不依赖框架抽象',
  },
  {
    icon: 'M12 2l8 4v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-4z M9 12l2 2 4-4',
    title: '分层记忆系统',
    desc: '会话 + 长期记忆，自动注入 system prompt，跨会话一致',
  },
]

async function submit() {
  if (busy.value) return
  if (!form.username.trim() || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  error.value = null
  try {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: form.username.trim(), password: form.password }),
    })
    const body = await res.json()
    if (res.status === 401 || body.code === 401) {
      error.value = '用户名或密码错误'
      form.password = ''
      return
    }
    if (body.code !== 0) throw new Error(body.message || '登录失败')
    emit('logined', body.data?.username || form.username.trim())
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

// 输入即清错误（主流登录页行为）
function clearError() {
  if (error.value) error.value = null
}

// 首屏自动聚焦用户名输入框
onMounted(() => {
  userInput.value?.focus()
})
</script>

<template>
  <div class="login-split">
    <!-- 左栏：品牌 + 卖点 -->
    <aside class="brand-panel">
      <div class="glow glow-1" aria-hidden="true" />
      <div class="glow glow-2" aria-hidden="true" />
      <div class="brand-center">
        <header class="brand-head">
          <img :src="logoUrl" alt="OryxOS" class="logo" />
        </header>
        <div class="brand-body">
          <p class="brand-eyebrow"><span class="eyebrow-comment">// </span>开源 · 私有部署 · Apache 2.0</p>
          <h1 class="brand-title">DISTRIBUTED<br />AI AGENT OS</h1>
          <p class="brand-sub">基于 Java 21 构建的分布式 AI Agent OS——私有部署在你自己的 K8s 或服务器上，让一群业务 Agent 像进程跑在操作系统上一样，可靠地运行和协同。</p>
          <ul class="features">
            <li v-for="(f, i) in features" :key="f.title" class="feature">
              <span class="feature-num mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <div>
                <div class="feature-title">{{ f.title }}</div>
                <div class="feature-desc">{{ f.desc }}</div>
              </div>
            </li>
          </ul>
        </div>
      </div>
      <footer class="brand-foot">
        <a
          class="gh-link"
          href="https://github.com/oryx-labs/oryxos"
          target="_blank"
          rel="noopener noreferrer"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 .5A11.5 11.5 0 0 0 .5 12a11.5 11.5 0 0 0 7.87 10.92c.58.1.79-.25.79-.56v-2c-3.2.7-3.88-1.37-3.88-1.37-.53-1.34-1.3-1.7-1.3-1.7-1.06-.72.08-.71.08-.71 1.17.08 1.78 1.2 1.78 1.2 1.04 1.78 2.73 1.27 3.4.97.1-.75.4-1.27.73-1.56-2.56-.29-5.26-1.28-5.26-5.7 0-1.26.45-2.29 1.2-3.1-.12-.3-.52-1.46.1-3.05 0 0 .98-.31 3.2 1.18a11.1 11.1 0 0 1 5.82 0c2.22-1.49 3.2-1.18 3.2-1.18.62 1.59.22 2.75.1 3.05.75.81 1.2 1.84 1.2 3.1 0 4.43-2.7 5.4-5.27 5.69.41.36.78 1.05.78 2.12v3.14c0 .31.21.67.8.56A11.5 11.5 0 0 0 23.5 12 11.5 11.5 0 0 0 12 .5Z"
            />
          </svg>
          <span>GitHub</span>
        </a>
      </footer>
    </aside>

    <!-- 右栏：登录卡 -->
    <main class="form-panel">
      <div class="card">
        <h2 class="title">欢迎登录</h2>
        <p class="sub">管理台管理 Agent、会话、工具与定时任务</p>

        <form class="form" @submit.prevent="submit" novalidate>
          <div class="field">
            <label for="login-user" class="label">用户名</label>
            <div class="input-row">
              <span class="input-ico">
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"
                  />
                  <circle cx="12" cy="7" r="4" fill="none" stroke="currentColor" stroke-width="1.6" />
                </svg>
              </span>
              <input
                id="login-user"
                ref="userInput"
                v-model="form.username"
                class="input"
                type="text"
                autocomplete="username"
                :disabled="busy"
                @input="clearError"
              />
            </div>
          </div>

          <div class="field">
            <label for="login-pass" class="label">密码</label>
            <div class="input-row">
              <span class="input-ico">
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <rect
                    x="3"
                    y="11"
                    width="18"
                    height="10"
                    rx="2"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                  />
                  <path
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M7 11V7a5 5 0 0 1 10 0v4"
                  />
                </svg>
              </span>
              <input
                id="login-pass"
                v-model="form.password"
                class="input"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                :disabled="busy"
                @input="clearError"
              />
              <button
                type="button"
                class="toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <svg v-if="showPassword" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"
                  />
                  <circle cx="12" cy="12" r="3" fill="none" stroke="currentColor" stroke-width="1.6" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M9.9 4.24A9.1 9.1 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72 6.72A9.1 9.1 0 0 1 12 22c-7 0-11-8-11-8a18.5 18.5 0 0 1 4.06-4.95m3.1 3.1a3 3 0 1 0 4.68-3.68M1 1l22 22"
                  />
                </svg>
              </button>
            </div>
          </div>

          <p v-if="error" class="error" role="alert">{{ error }}</p>

          <button class="btn-primary" type="submit" :disabled="busy">
            <span v-if="busy" class="spinner" aria-hidden="true" />
            {{ busy ? '登录中…' : '登录' }}
          </button>
        </form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-split {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  background: var(--bg);
}

/* —— 左栏：品牌区 —— */
.brand-panel {
  position: relative;
  background:
    linear-gradient(160deg, #0a0a0a 0%, #141414 60%, #0d0d0d 100%);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 56px 72px;
  overflow: hidden;
}
/* 细网格背景（科技感，极淡，不抢内容） */
.brand-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px);
  background-size: 48px 48px;
  -webkit-mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  pointer-events: none;
  z-index: 0;
}
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
}
.glow-1 {
  width: 620px;
  height: 620px;
  background: rgba(249, 115, 22, 0.18);
  top: -200px;
  right: -160px;
}
.glow-2 {
  width: 460px;
  height: 460px;
  background: rgba(249, 115, 22, 0.1);
  bottom: -160px;
  left: -100px;
}
.brand-head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-bottom: 32px;
}
.logo {
  width: 250px;
  height: auto;
}
.brand-name {
  font-size: 19px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: 0.02em;
}
.brand-center {
  margin-top: auto;
  margin-bottom: auto;
  position: relative;
  z-index: 1;
  text-align: center;
}
.brand-body {
  position: relative;
  z-index: 1;
  text-align: center;
}
.brand-eyebrow {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--text-2);
  margin: 0 0 22px;
  letter-spacing: 0.02em;
}
.eyebrow-comment {
  color: var(--brand);
}
.brand-title {
  font-size: 48px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.01em;
  margin: 0 0 22px;
  color: var(--text-1);
}
.brand-sub {
  font-size: 16px;
  line-height: 1.7;
  color: var(--text-2);
  max-width: min(520px, 90%);
  margin: 0 auto 40px;
}
.features {
  list-style: none;
  padding: 0;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 540px;
}
.feature {
  display: flex;
  align-items: center;
  gap: 16px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 20px;
  text-align: left;
}
.feature-ico {
  flex: none;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.06);
  color: var(--brand);
  border-radius: 10px;
}
.feature-num {
  flex: none;
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
  color: var(--brand);
  letter-spacing: 0.1em;
  padding-top: 1px;
}
.feature-ico svg {
  width: 24px;
  height: 24px;
}
.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}
.feature-desc {
  font-size: 14px;
  color: var(--text-2);
  margin-top: 3px;
  line-height: 1.4;
}
.brand-foot {
  position: relative;
  z-index: 1;
  margin-top: 40px;
  display: flex;
  justify-content: center;
}
.gh-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: 14px;
  text-decoration: none;
  padding: 8px 16px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.03);
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}
.gh-link:hover {
  color: var(--text-1);
  border-color: var(--brand);
  background: var(--brand-soft);
}
.gh-link:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

/* —— 右栏：登录卡 —— */
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: radial-gradient(ellipse at top, #1f1f1f 0%, #161616 50%, #121212 100%);
  position: relative;
  overflow: hidden;
}
.form-panel::before {
  content: '';
  position: absolute;
  top: -120px;
  left: 50%;
  transform: translateX(-50%);
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: rgba(249, 115, 22, 0.07);
  filter: blur(90px);
  pointer-events: none;
}
.form-panel::after {
  content: '';
  position: absolute;
  bottom: -160px;
  right: -80px;
  width: 300px;
  height: 300px;
  border-radius: 50%;
  background: rgba(249, 115, 22, 0.03);
  filter: blur(70px);
  pointer-events: none;
}
.card {
  width: 100%;
  max-width: 460px;
  position: relative;
  z-index: 1;
  background: rgba(20, 20, 20, 0.6);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 44px 40px;
  backdrop-filter: blur(8px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
}
/* 顶部高光边（模拟光照，玻璃拟态卡质感） */
.card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 16px;
  right: 16px;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(249, 115, 22, 0.5),
    transparent
  );
  pointer-events: none;
}
.title {
  font-size: 30px;
  font-weight: 600;
  margin: 0 0 8px;
  letter-spacing: -0.01em;
}
.sub {
  color: var(--text-2);
  font-size: 15px;
  margin: 0 0 36px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.label {
  font-size: 14px;
  color: var(--text-2);
  font-weight: 500;
}
.input-row {
  position: relative;
  display: flex;
  align-items: center;
}
.input-ico {
  position: absolute;
  left: 16px;
  color: var(--text-3);
  display: flex;
  pointer-events: none;
}
.input-ico svg {
  width: 20px;
  height: 20px;
}
.input {
  width: 100%;
  background: var(--bg-mute);
  color: var(--text-1);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 16px 50px 16px 48px;
  font-size: 16px;
  font-family: var(--font-base);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}
.input:disabled {
  opacity: 0.5;
}
.toggle {
  position: absolute;
  right: 6px;
  background: none;
  border: none;
  color: var(--text-3);
  cursor: pointer;
  padding: 6px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.15s ease;
}
.toggle:hover {
  color: var(--text-1);
}
.toggle:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.error {
  color: var(--err);
  font-size: 13px;
  margin: 0;
  padding: 8px 12px;
  background: rgba(239, 68, 68, 0.08);
  border-radius: 6px;
}
.btn-primary {
  background: linear-gradient(180deg, #fb923c 0%, #f97316 50%, #ea6a00 100%);
  color: #000;
  border: none;
  border-radius: 8px;
  padding: 15px 0;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.35),
    0 1px 2px rgba(0, 0, 0, 0.3),
    0 4px 12px rgba(249, 115, 22, 0.25);
  transition: background 0.15s ease, transform 0.1s ease, box-shadow 0.15s ease;
}
.btn-primary:hover:not(:disabled) {
  background: linear-gradient(180deg, #fb923c 0%, #f97316 45%, #d85f00 100%);
  transform: translateY(-1px);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.4),
    0 2px 4px rgba(0, 0, 0, 0.3),
    0 6px 16px rgba(249, 115, 22, 0.35);
}
.btn-primary:active:not(:disabled) {
  transform: scale(0.99);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(0, 0, 0, 0.3);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* —— 响应式：窄屏隐藏左栏 —— */
@media (max-width: 960px) {
  .login-split {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .input,
  .toggle,
  .btn-primary,
  .spinner {
    transition: none;
    animation: none;
  }
}
</style>
