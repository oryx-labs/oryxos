<script setup>
import { ref, reactive, computed } from 'vue'
import logoUrl from './assets/logo.svg'
import { apiGet, apiSend, authStatus, login, logout, setUnauthorizedHandler } from './api'

// 顶层：概览 / Agent 列表 / 定时任务。「OS 运行时」下收纳 Provider/Tool/Sandbox/长期记忆/会话——
// 这些都是底座本身的运行时状态，跟业务 Agent 管理分层展示（31 节：侧边栏重分组）。
const TOP_NAV = [
  { key: 'overview', label: '概览' },
  { key: 'agents', label: 'Agent 列表' },
  { key: 'schedules', label: '定时任务', path: '/api/v1/schedules' },
]

const RUNTIME_NAV = [
  { key: 'sessions', label: '会话列表', path: '/api/v1/sessions' },
  {
    key: 'providers',
    label: 'Provider 列表',
    path: '/api/v1/info',
    // /info 返回 {application, providers:[名字…]}，投影成列表行
    transform: (d) => (d?.providers ?? []).map((name) => ({ name, status: '已配置' })),
  },
  { key: 'tools', label: 'Tool 列表', path: '/api/v1/tools' },
  { key: 'whitelist', label: 'SandBox 列表' },
]

const NAV = [...TOP_NAV, ...RUNTIME_NAV]
const runtimeKeys = new Set(RUNTIME_NAV.map((n) => n.key))
const runtimeOpen = ref(false) // OS 运行时分组展开状态

const active = ref('overview')
const state = reactive({}) // key -> {loading, error, data}
// 当前激活页（只渲染这一页，避免 v-show + v-for 的块补丁陷阱导致切不动）
const current = computed(() => NAV.find((n) => n.key === active.value) ?? NAV[0])

const auth = reactive({
  loading: true,
  configured: false,
  authenticated: false,
  username: null,
  error: null,
  expired: false,
})
const loginForm = reactive({ username: '', password: '' })
const loginBusy = ref(false)

setUnauthorizedHandler(() => {
  clearProtectedState()
  auth.authenticated = false
  auth.username = null
  auth.expired = true
})

// 运行状态（原「运行状态」独立页，31 节并入概览展示）：应用名 + 已配置 Provider
const runtimeInfo = ref({ loading: true, error: null, data: null })
async function loadRuntimeInfo() {
  runtimeInfo.value = { loading: true, error: null, data: null }
  try {
    const data = await apiGet('/api/v1/info')
    runtimeInfo.value = { loading: false, error: null, data }
  } catch (e) {
    runtimeInfo.value = { loading: false, error: e.message, data: null }
  }
}

async function bootstrapAuth() {
  auth.loading = true
  auth.error = null
  try {
    const status = await authStatus()
    auth.configured = !!status.configured
    auth.authenticated = !!status.authenticated
    auth.username = status.username || null
    auth.expired = false
    if (auth.authenticated) await loadRuntimeInfo()
  } catch (e) {
    auth.error = e.message
  } finally {
    auth.loading = false
  }
}

async function handleLogin() {
  if (!loginForm.username.trim() || !loginForm.password) return
  loginBusy.value = true
  auth.error = null
  try {
    const data = await login({ username: loginForm.username, password: loginForm.password })
    auth.authenticated = true
    auth.username = data.username
    auth.expired = false
    loginForm.password = ''
    await loadRuntimeInfo()
  } catch (e) {
    auth.error = e.message
  } finally {
    loginBusy.value = false
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    /* 后端会话可能已经过期；前端仍清理本地状态。 */
  }
  clearProtectedState()
  auth.authenticated = false
  auth.username = null
  auth.expired = false
  active.value = 'overview'
}

function clearProtectedState() {
  Object.keys(state).forEach((key) => delete state[key])
  runtimeInfo.value = { loading: false, error: null, data: null }
  agents.value = { loading: false, error: null, data: [] }
  sessionDetail.value = null
  execDetail.value = null
  agentDetail.value = null
  fileView.value = null
}

bootstrapAuth()

// 概览页数据：当前为静态预览，后续逐步接入实时端点（TODO 标注了各自的动态来源）
const overview = {
  tagline: '装在你自己基础设施上的分布式 AI Agent 操作系统 —— 统一底座运行多个业务 Agent',
  status: '运行中',
  version: 'v0.1.0 · 开发预览',
  // TODO 动态化：agents←GET /profiles，tools←GET /tools，sessions←会话统计端点，providers←GET /info
  stats: [
    { label: 'Agent', value: '3', hint: '已配置的 Profile' },
    { label: '内置 Tool', value: '14', hint: '文件 / Shell / HTTP / 记忆 …' },
    { label: '活跃会话', value: '—', hint: '待接入实时统计' },
    { label: 'Provider', value: '1', hint: 'deepseek 已连通' },
  ],
  capabilities: [
    { name: '对接 LLM', desc: '显式 Provider 映射，多家协议统一' },
    { name: 'ReAct 循环', desc: '自实现推理–行动循环，完全可控' },
    { name: 'Memory', desc: '跨对话长期记忆，成长可积累' },
    { name: 'Plugin Tool', desc: '内置 Tool + MCP，强制沙箱白名单' },
    { name: 'Web Service', desc: 'REST API + 管理台对外门面' },
  ],
  stack: ['Java 21', 'Spring Boot 3.x', 'Spring AI Alibaba', 'SQLite', 'Picocli'],
}

// 表格列定义（tools / providers / schedules / sessions）。放在 setup 里，模板直接可用。
function cols(key) {
  if (key === 'tools') return ['name', 'description']
  if (key === 'providers') return ['name', 'status']
  if (key === 'schedules')
    return ['taskId', 'profileName', 'cron', 'zone', 'enabled', 'runCount', 'lastStatus', 'lastRunAt']
  if (key === 'sessions')
    return ['sessionId', 'profileName', 'channel', 'status', 'messageCount', 'lastActiveAt']
  return []
}

async function load(key) {
  const nav = NAV.find((n) => n.key === key)
  if (!nav || !nav.path) return
  state[key] = { loading: true, error: null, data: null }
  try {
    const body = await apiGet(nav.path)
    const data = nav.transform ? nav.transform(body) : body
    state[key] = { loading: false, error: null, data }
  } catch (e) {
    state[key] = { loading: false, error: e.message, data: null }
  }
}

function select(key) {
  if (!auth.authenticated) return
  active.value = key
  sessionDetail.value = null // 切页时收起会话详情
  execDetail.value = null // 切页时收起执行记录
  if (runtimeKeys.has(key)) runtimeOpen.value = true // 选中的是运行时子页 → 展开分组
  if (NAV.find((n) => n.key === key)?.path && !state[key]) load(key)
  if (key === 'agents') { agentDetail.value = null; fileView.value = null; loadAgents() }
}

// —— 会话详情：点一行会话，拉 GET /sessions/{id} 看完整对话内容 ——
const sessionDetail = ref(null) // {loading, error, id, data:{sessionId, profileName, messages[]}}

async function openSession(id) {
  sessionDetail.value = { loading: true, error: null, id, data: null }
  try {
    const data = await apiGet(`/api/v1/sessions/${encodeURIComponent(id)}`)
    sessionDetail.value = { loading: false, error: null, id, data }
  } catch (e) {
    sessionDetail.value = { loading: false, error: e.message, id, data: null }
  }
}

function closeSession() {
  sessionDetail.value = null
}

// 对话角色的中文标签
function roleLabel(role) {
  return { user: '用户', assistant: '助手', tool: '工具' }[role] ?? role
}

// —— 定时任务管理动作（28 节：管理台可管，不再只读）——
const busy = ref(null) // 正在操作的 taskId，防重复点击

// 立即执行一次（POST /schedules/{id}/run），跑完刷新列表
async function runTask(id) {
  busy.value = id
  try {
    await apiSend(`/api/v1/schedules/${id}/run`)
    await load('schedules')
    // 若正打开着这个任务的执行记录，跑完顺手刷新
    if (execDetail.value?.taskId === id) await openExecutions(id)
  } catch (e) {
    state.schedules = { ...state.schedules, error: e.message }
  } finally {
    busy.value = null
  }
}

// 执行记录历史：点"执行记录"拉 GET /schedules/{id}/executions
const execDetail = ref(null) // {loading, error, taskId, data:[{startedAt,success,durationMs,errorMessage,sessionId}]}

async function openExecutions(taskId) {
  execDetail.value = { loading: true, error: null, taskId, data: null }
  try {
    const data = await apiGet(`/api/v1/schedules/${encodeURIComponent(taskId)}/executions`)
    execDetail.value = { loading: false, error: null, taskId, data }
  } catch (e) {
    execDetail.value = { loading: false, error: e.message, taskId, data: null }
  }
}

function closeExecutions() {
  execDetail.value = null
}

// 启用/停用（PUT /schedules/{id}），切换后刷新列表
async function toggleTask(row) {
  busy.value = row.taskId
  try {
    await apiSend(`/api/v1/schedules/${row.taskId}`, {
      method: 'PUT',
      body: { enabled: !row.enabled },
    })
    await load('schedules')
  } catch (e) {
    state.schedules = { ...state.schedules, error: e.message }
  } finally {
    busy.value = null
  }
}

// —— 30 节：Agent 管理（动态增删改 + 一句话生成）——
const agents = ref({ loading: false, error: null, data: [] })
async function loadAgents() {
  agents.value = { loading: true, error: null, data: [] }
  try {
    const data = await apiGet('/api/v1/agents')
    agents.value = { loading: false, error: null, data: data || [] }
  } catch (e) {
    agents.value = { loading: false, error: e.message, data: [] }
  }
}

// 新建 Agent：只填 name + description，后台按模板脚手架出完整目录
const gen = reactive({ open: false, name: '', description: '', busy: false, error: null })

async function createAgent() {
  gen.busy = true; gen.error = null
  try {
    await apiSend('/api/v1/agents', { body: { name: gen.name, description: gen.description } })
    cancelCreate(); await loadAgents()
  } catch (e) { gen.error = e.message } finally { gen.busy = false }
}

async function deleteAgent(name) {
  if (!confirm(`删除 Agent「${name}」？（整个目录归档到 archive/，不物理删）`)) return
  try {
    await apiSend(`/api/v1/agents/${encodeURIComponent(name)}`, { method: 'DELETE' })
    if (agentDetail.value?.name === name) closeAgent()
    await loadAgents()
  } catch (e) { agents.value = { ...agents.value, error: e.message } }
}

function cancelCreate() { gen.open = false; gen.name = ''; gen.description = ''; gen.error = null }

// —— Agent 详情：Tab 切换（基本信息 / 生成 / 文件 / 会话 / 记忆）——
const agentDetail = ref(null) // { name, agent, tab, loading, error, node }
const fileView = ref(null) // { path, loading, error, content, saving, saved }
// 生成：描述 → 大模型生成各文件内容，可编辑后保存生效
const genEdit = reactive({ description: '', files: null, busy: false, error: null, saved: false })
// 会话：每个 Agent 一个固定 session，直接作为对话展示（不再是会话列表）
const chat = reactive({ sessionId: null, messages: [], loading: false, error: null, input: '', sending: false })
// 记忆：这个 Agent 自己的长期记忆（只读）
const agentMemory = reactive({ text: '', loading: false, error: null })

async function openAgent(agent) {
  agentDetail.value = { name: agent.name, agent, tab: 'info', loading: true, error: null, node: null }
  fileView.value = null
  resetChat()
  resetGenEdit()
  resetAgentMemory()
  try {
    const data = await apiGet('/api/v1/workspace/tree')
    const agentsNode = (data.children || []).find((c) => c.name === 'agents')
    const node = (agentsNode?.children || []).find((c) => c.name === agent.name) || null
    agentDetail.value = { ...agentDetail.value, loading: false, node }
  } catch (e) {
    agentDetail.value = { ...agentDetail.value, loading: false, error: e.message }
  }
}

// 重新拉取当前 Agent 的元数据 + 文件树（保存文件后刷新基本信息）
async function reloadAgent() {
  if (!agentDetail.value) return
  const name = agentDetail.value.name
  try {
    const data = await apiGet(`/api/v1/agents/${encodeURIComponent(name)}`)
    if (data) agentDetail.value = { ...agentDetail.value, agent: data }
  } catch (e) {
    /* 元数据刷新失败不阻断，忽略 */
  }
  try {
    const data = await apiGet('/api/v1/workspace/tree')
    const agentsNode = (data.children || []).find((c) => c.name === 'agents')
    const node = (agentsNode?.children || []).find((c) => c.name === name) || null
    agentDetail.value = { ...agentDetail.value, node }
  } catch (e) {
    /* 文件树刷新失败不阻断，忽略 */
  }
}

function detailTab(tab) {
  if (!agentDetail.value) return
  agentDetail.value = { ...agentDetail.value, tab }
  if (tab === 'generate') {
    genEdit.description = agentDetail.value.agent.description || ''
    genEdit.files = null
    genEdit.error = null
    genEdit.saved = false
  } else if (tab === 'chat') {
    loadChat()
  } else if (tab === 'memory') {
    loadMemory()
  }
}

// —— Tab 2：生成 —— 描述 → 大模型生成各文件内容
function resetGenEdit() {
  genEdit.description = ''
  genEdit.files = null
  genEdit.busy = false
  genEdit.error = null
  genEdit.saved = false
}

async function generateFiles() {
  genEdit.busy = true; genEdit.error = null; genEdit.saved = false
  try {
    const name = agentDetail.value.name
    const data = await apiSend(`/api/v1/agents/${encodeURIComponent(name)}/generate-files`, {
      body: { description: genEdit.description },
    })
    genEdit.files = data.files || {}
  } catch (e) { genEdit.error = e.message } finally { genEdit.busy = false }
}

async function saveFiles() {
  genEdit.busy = true; genEdit.error = null
  try {
    const name = agentDetail.value.name
    await apiSend(`/api/v1/agents/${encodeURIComponent(name)}/files`, {
      body: { files: genEdit.files },
    })
    genEdit.saved = true
    await reloadAgent()
  } catch (e) { genEdit.error = e.message } finally { genEdit.busy = false }
}

// —— Tab 4：会话 —— 每个 Agent 一个固定 session，直接作为对话展示
function resetChat() {
  chat.sessionId = null
  chat.messages = []
  chat.loading = false
  chat.error = null
  chat.input = ''
  chat.sending = false
}

async function loadChat() {
  chat.loading = true; chat.error = null
  try {
    const name = agentDetail.value.name
    const data = await apiGet(`/api/v1/agents/${encodeURIComponent(name)}/session`)
    chat.sessionId = data.sessionId
    chat.messages = data.messages || []
  } catch (e) { chat.error = e.message } finally { chat.loading = false }
}

async function sendChat() {
  if (!chat.input.trim()) return
  chat.sending = true; chat.error = null
  try {
    const name = agentDetail.value.name
    await apiSend(`/api/v1/agents/${encodeURIComponent(name)}/session/messages`, {
      body: { content: chat.input },
    })
    chat.input = ''
    await loadChat()
  } catch (e) { chat.error = e.message } finally { chat.sending = false }
}

// —— Tab 5：记忆 —— 这个 Agent 自己的长期记忆（只读）
function resetAgentMemory() {
  agentMemory.text = ''
  agentMemory.loading = false
  agentMemory.error = null
}

async function loadMemory() {
  agentMemory.loading = true; agentMemory.error = null
  try {
    const name = agentDetail.value.name
    const data = await apiGet(`/api/v1/agents/${encodeURIComponent(name)}/memory`)
    agentMemory.text = data || ''
  } catch (e) { agentMemory.error = e.message } finally { agentMemory.loading = false }
}

function closeAgent() {
  agentDetail.value = null
  fileView.value = null
  resetChat()
  resetGenEdit()
  resetAgentMemory()
}

async function openFile(node) {
  if (node.type !== 'file') return
  fileView.value = { path: node.path, loading: true, error: null, content: '', saving: false, saved: false }
  try {
    const data = await apiGet(`/api/v1/workspace/file?path=${encodeURIComponent(node.path)}`)
    fileView.value = { path: node.path, loading: false, error: null, content: data, saving: false, saved: false }
  } catch (e) {
    fileView.value = { path: node.path, loading: false, error: e.message, content: '', saving: false, saved: false }
  }
}

// Tab 3：保存当前文件（编辑后写回工作区）
async function saveFile() {
  if (!fileView.value) return
  fileView.value = { ...fileView.value, saving: true, error: null, saved: false }
  try {
    await apiSend('/api/v1/workspace/file', {
      body: { path: fileView.value.path, content: fileView.value.content },
    })
    fileView.value = { ...fileView.value, saving: false, saved: true }
    if (fileView.value.path.endsWith('/AGENT.md')) await reloadAgent()
  } catch (e) {
    fileView.value = { ...fileView.value, saving: false, error: e.message }
  }
}

// 把一个 Agent 目录扁平成带缩进层级的行
function flatten(node, depth, acc) {
  if (!node) return acc
  if (depth > 0) acc.push({ ...node, depth })
  ;(node.children || []).forEach((c) => flatten(c, depth + 1, acc))
  return acc
}
const detailRows = computed(() => (agentDetail.value?.node ? flatten(agentDetail.value.node, 0, []) : []))
</script>

<template>
  <div v-if="auth.loading" class="auth-screen">
    <section class="login-card">
      <img :src="logoUrl" alt="OryxOS" class="login-logo" />
      <p class="empty">正在检查管理台登录状态…</p>
    </section>
  </div>

  <div v-else-if="!auth.authenticated" class="auth-screen">
    <section class="login-card">
      <div class="login-mark">
        <img :src="logoUrl" alt="OryxOS" class="login-logo" />
        <span class="tag">Admin Console</span>
      </div>
      <h1>进入 OryxOS 管理后台</h1>
      <p class="login-sub">
        这里管理 Agent、会话、调度和运行时能力。先登录，再加载任何受保护数据。
      </p>
      <p v-if="auth.expired" class="error">会话已过期，请重新登录。</p>
      <p v-if="auth.error" class="error">{{ auth.error }}</p>

      <div v-if="!auth.configured" class="unconfigured">
        <div class="cap-name">管理员凭证尚未配置</div>
        <p class="empty">
          请在部署环境中设置 ORYXOS_ADMIN_USERNAME 和 ORYXOS_ADMIN_PASSWORD_HASH。
          密码散列需使用 Spring Security 的编码前缀，例如 {bcrypt} 或 {noop}。
        </p>
      </div>

      <form v-else class="login-form" @submit.prevent="handleLogin">
        <label>
          用户名
          <input v-model="loginForm.username" class="gen-input" autocomplete="username" />
        </label>
        <label>
          密码
          <input
            v-model="loginForm.password"
            class="gen-input"
            type="password"
            autocomplete="current-password"
          />
        </label>
        <button class="btn login-btn" :disabled="loginBusy" type="submit">
          {{ loginBusy ? '登录中…' : '登录' }}
        </button>
      </form>
    </section>
  </div>

  <div v-else class="layout">
    <aside class="nav">
      <div class="brand">
        <img :src="logoUrl" alt="OryxOS" class="logo" />
      </div>
      <button
        v-for="n in TOP_NAV"
        :key="n.key"
        :class="['nav-item', { on: active === n.key }]"
        @click="select(n.key)"
      >
        {{ n.label }}
      </button>

      <button
        :class="['nav-item', 'nav-group', { open: runtimeOpen }]"
        @click="runtimeOpen = !runtimeOpen"
      >
        OS 运行时
        <span class="chevron">{{ runtimeOpen ? '▾' : '▸' }}</span>
      </button>
      <template v-if="runtimeOpen">
        <button
          v-for="n in RUNTIME_NAV"
          :key="n.key"
          :class="['nav-item', 'nav-sub', { on: active === n.key }]"
          @click="select(n.key)"
        >
          {{ n.label }}
        </button>
      </template>

      <div class="readonly">管理台</div>
    </aside>

    <main class="content">
      <div class="topbar">
        <span class="empty">当前管理员：<span class="mono">{{ auth.username }}</span></span>
        <button class="btn" @click="handleLogout">退出登录</button>
      </div>
      <!-- 只渲染当前激活页；active 变 → current/整块重算并重渲染 -->
      <div :key="active">
        <!-- 概览：静态预览数据（后续逐步动态化） -->
        <template v-if="active === 'overview'">
          <div class="hero">
            <div class="hero-top">
              <h2 class="hero-title">OryxOS</h2>
              <span class="badge"><span class="pulse" />{{ overview.status }}</span>
              <span class="ver mono">{{ overview.version }}</span>
            </div>
            <p class="hero-sub">{{ overview.tagline }}</p>
          </div>

          <div class="cards">
            <div v-for="s in overview.stats" :key="s.label" class="card">
              <div class="card-val">{{ s.value }}</div>
              <div class="card-label">{{ s.label }}</div>
              <div class="card-hint">{{ s.hint }}</div>
            </div>
          </div>

          <h3 class="sec">五大核心能力</h3>
          <div class="caps">
            <div v-for="(c, i) in overview.capabilities" :key="c.name" class="cap">
              <span class="cap-idx mono">{{ i + 1 }}</span>
              <div>
                <div class="cap-name">{{ c.name }}</div>
                <div class="cap-desc">{{ c.desc }}</div>
              </div>
            </div>
          </div>

          <h3 class="sec">技术栈</h3>
          <div class="stack">
            <span v-for="t in overview.stack" :key="t" class="tag">{{ t }}</span>
          </div>

          <h3 class="sec">运行状态</h3>
          <p v-if="runtimeInfo.loading" class="empty">加载中…</p>
          <p v-else-if="runtimeInfo.error" class="error">出错：{{ runtimeInfo.error }}</p>
          <div v-else-if="runtimeInfo.data">
            <p>应用：<b>{{ runtimeInfo.data.application }}</b></p>
            <p>Provider：
              <span v-for="p in runtimeInfo.data.providers" :key="p" class="tag">{{ p }}</span>
              <span v-if="!runtimeInfo.data.providers?.length" class="empty">（无）</span>
            </p>
          </div>

          <p class="note mono">当前为静态预览数据，将逐步接入实时端点（Agent/Tool/会话/Provider）。</p>
        </template>

        <template v-else>
          <h2>{{ current.label }}</h2>

          <!-- Sandbox 白名单：列表占位，内容暂空（待接入端点） -->
          <table v-if="active === 'whitelist'">
            <thead><tr><th>类别</th><th>规则</th></tr></thead>
            <tbody><tr><td colspan="2" class="empty">（暂无数据 · 待接入沙箱白名单端点）</td></tr></tbody>
          </table>

          <!-- 30 节：Agent —— 一个列表（含一句话新建/删除）；点"详情"进这个 Agent 的文件浏览器 -->
          <div v-else-if="active === 'agents'">
            <!-- 详情视图：Tab（基本信息 / 文件 / 会话） -->
            <div v-if="agentDetail">
              <button class="btn back" @click="closeAgent">← 返回 Agent 列表</button>
              <div class="sess-meta"><span>Agent</span><span class="mono">{{ agentDetail.name }}</span></div>
              <div class="tabs">
                <button :class="['tab', { on: agentDetail.tab === 'info' }]" @click="detailTab('info')">基本信息</button>
                <button :class="['tab', { on: agentDetail.tab === 'generate' }]" @click="detailTab('generate')">生成</button>
                <button :class="['tab', { on: agentDetail.tab === 'files' }]" @click="detailTab('files')">文件</button>
                <button :class="['tab', { on: agentDetail.tab === 'chat' }]" @click="detailTab('chat')">会话</button>
                <button :class="['tab', { on: agentDetail.tab === 'memory' }]" @click="detailTab('memory')">记忆</button>
              </div>

              <!-- Tab 1：基本信息 -->
              <div v-if="agentDetail.tab === 'info'" class="info-grid">
                <div class="info-row"><span class="k">name</span><span class="mono">{{ agentDetail.agent.name }}</span></div>
                <div class="info-row"><span class="k">description</span><span>{{ agentDetail.agent.description || '—' }}</span></div>
                <div class="info-row"><span class="k">provider</span><span>{{ agentDetail.agent.provider || '—' }}</span></div>
                <div class="info-row"><span class="k">model</span><span>{{ agentDetail.agent.model || '—' }}</span></div>
                <div class="info-row"><span class="k">tools</span><span>{{ (agentDetail.agent.tools || []).join(', ') || '—' }}</span></div>
                <div class="info-row"><span class="k">定时</span><span class="mono">{{ (agentDetail.agent.schedules || []).map((s) => s.cron + ' (' + s.zone + ')').join('；') || '—' }}</span></div>
              </div>

              <!-- Tab 2：生成 —— 描述 → 大模型生成各文件内容，可编辑后保存生效 -->
              <div v-else-if="agentDetail.tab === 'generate'">
                <div class="gen-box">
                  <div class="empty" style="margin-bottom:6px">描述这个 Agent 要做什么</div>
                  <textarea v-model="genEdit.description" class="gen-draft" rows="4" placeholder="例如：每天早上抓取团队仓库的 PR，汇总成一份摘要推送到群里"></textarea>
                  <div class="ops">
                    <button class="btn" :disabled="genEdit.busy" @click="generateFiles">用大模型生成</button>
                    <button v-if="genEdit.files" class="btn" :disabled="genEdit.busy" @click="saveFiles">保存并生效</button>
                  </div>
                  <p v-if="genEdit.busy" class="empty">{{ genEdit.files ? '保存中…' : '生成中…' }}</p>
                  <p v-if="genEdit.error" class="error">{{ genEdit.error }}</p>
                  <p v-if="genEdit.saved" class="ok">已保存并生效</p>
                </div>
                <template v-if="genEdit.files">
                  <div v-for="(content, path) in genEdit.files" :key="path" class="gen-file">
                    <div class="sess-meta"><span class="mono">{{ path }}</span></div>
                    <textarea class="mono filetext" v-model="genEdit.files[path]"></textarea>
                  </div>
                </template>
              </div>

              <!-- Tab 3：文件浏览器（可编辑） -->
              <div v-else-if="agentDetail.tab === 'files'">
                <p v-if="agentDetail.loading" class="empty">加载中…</p>
                <p v-else-if="agentDetail.error" class="error">出错：{{ agentDetail.error }}</p>
                <div v-else class="ws">
                  <div class="ws-tree">
                    <p v-if="!detailRows.length" class="empty">（该 Agent 目录为空）</p>
                    <div v-for="(node, i) in detailRows" :key="i"
                         :class="['ws-node', { file: node.type === 'file', on: fileView && fileView.path === node.path }]"
                         :style="{ paddingLeft: (node.depth * 14) + 'px' }"
                         @click="openFile(node)">
                      <span class="mono">{{ node.type === 'dir' ? '📁' : '📄' }} {{ node.name }}</span>
                    </div>
                  </div>
                  <div class="ws-file">
                    <p v-if="!fileView" class="empty">点左侧一个文件查看/编辑内容</p>
                    <template v-else>
                      <div class="sess-meta"><span class="mono">{{ fileView.path }}</span></div>
                      <p v-if="fileView.loading" class="empty">加载中…</p>
                      <template v-else>
                        <textarea class="mono filetext" v-model="fileView.content"></textarea>
                        <div class="ops" style="margin-top:10px">
                          <button class="btn" :disabled="fileView.saving" @click="saveFile">保存</button>
                          <span v-if="fileView.saving" class="empty">保存中…</span>
                          <span v-else-if="fileView.saved" class="ok">已保存</span>
                        </div>
                        <p v-if="fileView.error" class="error">{{ fileView.error }}</p>
                      </template>
                    </template>
                  </div>
                </div>
              </div>

              <!-- Tab 4：会话 —— 每个 Agent 一个固定 session，直接作为对话展示 -->
              <div v-else-if="agentDetail.tab === 'chat'">
                <div class="sess-meta"><span class="mono">{{ chat.sessionId || '（会话尚未创建）' }}</span></div>
                <p v-if="chat.loading" class="empty">加载中…</p>
                <p v-else-if="chat.error" class="error">出错：{{ chat.error }}</p>
                <template v-else>
                  <p v-if="!chat.messages.length" class="empty">（还没有对话，在下面发一条消息开始）</p>
                  <div v-else class="chat">
                    <div v-for="(m, i) in chat.messages" :key="i" :class="['msg', m.role]">
                      <div class="msg-role">{{ roleLabel(m.role) }}<span v-if="m.toolName" class="mono tool-name"> · {{ m.toolName }}</span></div>
                      <pre class="msg-body">{{ m.content || '（空）' }}</pre>
                    </div>
                  </div>
                </template>
                <div class="chat-input">
                  <textarea v-model="chat.input" class="gen-draft" rows="3" placeholder="给这个 Agent 发条消息…"></textarea>
                  <div class="ops">
                    <button class="btn" :disabled="chat.sending || !chat.input.trim()" @click="sendChat">发送</button>
                    <span v-if="chat.sending" class="empty">Agent 思考中…（ReAct 可能需要几秒）</span>
                  </div>
                </div>
              </div>

              <!-- Tab 5：记忆 —— 这个 Agent 自己的长期记忆（只读） -->
              <div v-else-if="agentDetail.tab === 'memory'">
                <p v-if="agentMemory.loading" class="empty">加载中…</p>
                <p v-else-if="agentMemory.error" class="error">出错：{{ agentMemory.error }}</p>
                <template v-else>
                  <pre class="mono memtext">{{ agentMemory.text || '（这个 Agent 还没有记忆）' }}</pre>
                  <p class="empty" style="margin-top:8px">由 save_memory 工具写入，此处只读。</p>
                </template>
              </div>
            </div>

            <!-- 列表视图：所有 Agent + 新建（只填 name + description，后台脚手架完整目录） -->
            <template v-else>
              <div class="gen-box">
                <button v-if="!gen.open" class="btn" @click="gen.open = true">+ 新建 Agent</button>
                <template v-else>
                  <div class="gen-row">
                    <input v-model="gen.name" class="gen-input" placeholder="Agent 名（字母/数字/下划线/连字符）" />
                    <input v-model="gen.description" class="gen-input" placeholder="描述这个 Agent 做什么" />
                  </div>
                  <div class="ops">
                    <button class="btn" :disabled="gen.busy || !gen.name" @click="createAgent">创建</button>
                    <button class="btn" @click="cancelCreate">取消</button>
                  </div>
                  <p class="empty">创建后后台自动生成完整目录：AGENT.md + scripts/ + skills/ + REFERENCE.md（模板内容，可在文件浏览器查看）。</p>
                </template>
                <p v-if="gen.error" class="error">{{ gen.error }}</p>
              </div>
              <p v-if="agents.loading" class="empty">加载中…</p>
              <p v-else-if="agents.error" class="error">出错：{{ agents.error }}</p>
              <table v-else>
                <thead><tr><th>name</th><th>provider</th><th>model</th><th>tools</th><th>定时</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-if="!agents.data.length"><td colspan="6" class="empty">（暂无 Agent · 点上面「新建 Agent」，或往 .oryxos/agents/ 丢一个目录）</td></tr>
                  <tr v-for="a in agents.data" :key="a.name">
                    <td class="mono">{{ a.name }}</td>
                    <td>{{ a.provider }}</td>
                    <td>{{ a.model }}</td>
                    <td>{{ (a.tools || []).join(', ') }}</td>
                    <td class="mono">{{ (a.schedules || []).map((s) => s.cron).join('; ') || '—' }}</td>
                    <td class="ops">
                      <button class="btn" @click="openAgent(a)">详情</button>
                      <button class="btn" @click="deleteAgent(a.name)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
          </div>

          <p v-else-if="!current.path" class="empty">{{ current.note }}</p>
          <template v-else>
            <p v-if="state[active]?.loading" class="empty">加载中…</p>
          <p v-else-if="state[active]?.error" class="error">出错：{{ state[active].error }}</p>
          <template v-else-if="state[active]?.data != null">
            <!-- schedules：定时任务，带管理动作（立即执行 / 启用停用 / 执行记录）——28 节，管理台可管 -->
            <template v-if="active === 'schedules'">
              <!-- 执行记录详情视图 -->
              <div v-if="execDetail">
                <button class="btn back" @click="closeExecutions">← 返回定时任务</button>
                <div class="sess-meta"><span class="mono">{{ execDetail.taskId }}</span><span class="empty">执行记录（最近 100 条）</span></div>
                <p v-if="execDetail.loading" class="empty">加载中…</p>
                <p v-else-if="execDetail.error" class="error">出错：{{ execDetail.error }}</p>
                <template v-else-if="execDetail.data">
                  <p v-if="!execDetail.data.length" class="empty">（还没有执行记录 · 点"立即执行"或等 cron 到点）</p>
                  <table v-else>
                    <thead><tr><th>开始时间</th><th>结果</th><th>耗时(ms)</th><th>会话</th><th>错误</th></tr></thead>
                    <tbody>
                      <tr v-for="(e, i) in execDetail.data" :key="i">
                        <td class="mono">{{ e.startedAt }}</td>
                        <td><span :class="e.success ? 'ok' : 'off'">{{ e.success ? '成功' : '失败' }}</span></td>
                        <td>{{ e.durationMs }}</td>
                        <td class="mono">{{ e.sessionId ?? '—' }}</td>
                        <td class="error">{{ e.errorMessage ?? '' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </template>
              </div>
              <!-- 列表视图 -->
              <table v-else>
                <thead>
                  <tr><th v-for="c in cols('schedules')" :key="c">{{ c }}</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-if="!state.schedules.data.length"><td :colspan="cols('schedules').length + 1" class="empty">（暂无定时任务 · 在 Profile 的 schedules 里定义）</td></tr>
                  <tr v-for="row in state.schedules.data" :key="row.taskId">
                    <td v-for="c in cols('schedules')" :key="c" :class="{ mono: c === 'taskId' || c === 'cron' }">
                      <span v-if="c === 'enabled'" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span>
                      <template v-else>{{ row[c] ?? '—' }}</template>
                    </td>
                    <td class="ops">
                      <button class="btn" :disabled="busy === row.taskId" @click="runTask(row.taskId)">立即执行</button>
                      <button class="btn" :disabled="busy === row.taskId" @click="toggleTask(row)">{{ row.enabled ? '停用' : '启用' }}</button>
                      <button class="btn" @click="openExecutions(row.taskId)">执行记录</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
            <!-- sessions：列表可点开看完整对话 -->
            <template v-else-if="active === 'sessions'">
              <!-- 详情视图：一条会话的完整对话 -->
              <div v-if="sessionDetail">
                <button class="btn back" @click="closeSession">← 返回会话列表</button>
                <p v-if="sessionDetail.loading" class="empty">加载中…</p>
                <p v-else-if="sessionDetail.error" class="error">出错：{{ sessionDetail.error }}</p>
                <template v-else-if="sessionDetail.data">
                  <div class="sess-meta">
                    <span class="mono">{{ sessionDetail.data.sessionId }}</span>
                    <span class="tag">{{ sessionDetail.data.profileName }}</span>
                    <span class="empty">{{ sessionDetail.data.messages.length }} 条消息</span>
                  </div>
                  <p v-if="!sessionDetail.data.messages.length" class="empty">（该会话暂无对话内容）</p>
                  <div v-else class="chat">
                    <div v-for="(m, i) in sessionDetail.data.messages" :key="i" :class="['msg', m.role]">
                      <div class="msg-role">
                        {{ roleLabel(m.role) }}<span v-if="m.toolName" class="mono tool-name"> · {{ m.toolName }}</span>
                      </div>
                      <pre class="msg-body">{{ m.content || '（空）' }}</pre>
                    </div>
                  </div>
                </template>
              </div>
              <!-- 列表视图：每行一个"查看"按钮 -->
              <table v-else>
                <thead>
                  <tr><th v-for="c in cols('sessions')" :key="c">{{ c }}</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-if="!state.sessions.data.length"><td :colspan="cols('sessions').length + 1" class="empty">（暂无会话）</td></tr>
                  <tr v-for="(row, i) in state.sessions.data" :key="i">
                    <td v-for="c in cols('sessions')" :key="c" :class="{ mono: c === 'sessionId' }">{{ row[c] }}</td>
                    <td class="ops"><button class="btn" @click="openSession(row.sessionId)">查看对话</button></td>
                  </tr>
                </tbody>
              </table>
            </template>
            <!-- profiles / tools：表格 -->
            <table v-else-if="Array.isArray(state[active].data)">
              <thead>
                <tr><th v-for="c in cols(active)" :key="c">{{ c }}</th></tr>
              </thead>
              <tbody>
                <tr v-if="!state[active].data.length"><td :colspan="cols(active).length" class="empty">（暂无数据）</td></tr>
                <tr v-for="(row, i) in state[active].data" :key="i">
                  <td v-for="c in cols(active)" :key="c" :class="{ mono: c === 'name' || c === 'sessionId' }">
                    {{ Array.isArray(row[c]) ? row[c].join(', ') : row[c] }}
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
        </template>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-screen {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 18px;
  background:
    radial-gradient(circle at 20% 10%, var(--brand-soft), transparent 34%),
    var(--bg);
}
.login-card {
  width: min(440px, 100%);
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 28px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.45);
}
.login-mark { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.login-logo { width: 138px; height: auto; display: block; }
.login-card h1 { margin: 28px 0 10px; font-size: 25px; line-height: 1.2; letter-spacing: -0.02em; }
.login-sub { color: var(--text-2); line-height: 1.7; margin: 0 0 22px; }
.login-form { display: flex; flex-direction: column; gap: 12px; }
.login-form label { color: var(--text-2); font-size: 12px; }
.login-form .gen-input { margin-top: 6px; margin-bottom: 0; }
.login-btn { width: 100%; margin-top: 4px; padding: 10px 12px; font-size: 14px; }
.unconfigured {
  background: var(--bg-mute);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 18px;
}
.layout { display: flex; min-height: 100vh; }
.nav {
  width: 200px; background: var(--bg-soft); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; padding: 16px 10px; gap: 4px;
}
.brand { padding: 6px 8px 16px; }
.logo { width: 128px; height: auto; display: block; }
.nav-item {
  text-align: left; background: none; border: none; color: var(--text-2);
  padding: 9px 10px; border-radius: var(--radius); cursor: pointer; font-size: 14px;
}
.nav-item:hover { background: var(--bg-mute); color: var(--text-1); }
.nav-item.on { background: var(--brand-soft); color: var(--brand); }
.nav-group { display: flex; align-items: center; justify-content: space-between; }
.chevron { color: var(--text-3); font-size: 11px; }
.nav-sub { padding-left: 22px; font-size: 13px; }
.readonly { margin-top: auto; color: var(--text-3); font-size: 12px; padding: 8px; }
.content { flex: 1; padding: 24px 32px; overflow-x: auto; }
h2 { font-weight: 600; margin: 0 0 16px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 9px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--text-2); font-weight: 500; }
.empty { color: var(--text-3); }
.error { color: var(--err); }
.tag { display: inline-block; background: var(--bg-mute); color: var(--brand); border-radius: var(--radius); padding: 2px 8px; margin-right: 6px; }
.memtext { background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; white-space: pre-wrap; }

/* 定时任务：状态标记 + 操作按钮 */
.ok { color: var(--ok); }
.off { color: var(--text-3); }
.ops { white-space: nowrap; }
.btn { background: var(--bg-mute); color: var(--text-1); border: 1px solid var(--border); border-radius: 6px; padding: 4px 10px; margin-right: 6px; font-size: 12px; cursor: pointer; }
.btn:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 会话详情：对话气泡 */
.btn.back { margin-bottom: 16px; }
.sess-meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.chat { display: flex; flex-direction: column; gap: 12px; }
.msg { border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 14px; background: var(--bg-soft); max-width: 80%; }
.msg.user { align-self: flex-end; background: var(--brand-soft); border-color: transparent; }
.msg.assistant { align-self: flex-start; }
.msg.tool { align-self: flex-start; background: var(--bg-mute); }
.msg-role { font-size: 12px; color: var(--text-2); margin-bottom: 4px; }
.tool-name { color: var(--brand); }
.msg-body { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; }
.msg.tool .msg-body { font-family: var(--font-mono); font-size: 13px; color: var(--text-2); }

/* 概览页 */
.hero { border-bottom: 1px solid var(--border); padding-bottom: 20px; margin-bottom: 24px; }
.hero-top { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.hero-title { font-size: 28px; font-weight: 700; margin: 0; letter-spacing: -0.02em; }
.badge { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--ok); background: rgba(34, 197, 94, 0.12); padding: 3px 10px; border-radius: 999px; }
.pulse { width: 7px; height: 7px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 0 0 rgba(34,197,94,0.6); animation: pulse 1.8s infinite; }
@keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(34,197,94,0.5); } 70% { box-shadow: 0 0 0 6px rgba(34,197,94,0); } 100% { box-shadow: 0 0 0 0 rgba(34,197,94,0); } }
.ver { color: var(--text-3); font-size: 12px; }
.hero-sub { color: var(--text-2); margin: 12px 0 0; max-width: 640px; line-height: 1.6; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 14px; margin-bottom: 28px; }
.card { background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px 18px; }
.card-val { font-size: 30px; font-weight: 700; color: var(--brand); font-family: var(--font-mono); line-height: 1; }
.card-label { margin-top: 8px; font-weight: 500; }
.card-hint { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.sec { font-size: 15px; font-weight: 600; margin: 0 0 14px; }
.caps { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-bottom: 28px; }
.cap { display: flex; gap: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); padding: 14px 16px; }
.cap-idx { flex: none; width: 26px; height: 26px; display: flex; align-items: center; justify-content: center; background: var(--brand-soft); color: var(--brand); border-radius: 6px; font-size: 13px; font-weight: 600; }
.cap-name { font-weight: 500; }
.cap-desc { margin-top: 3px; font-size: 12px; color: var(--text-2); line-height: 1.5; }
.stack { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 28px; }
.note { color: var(--text-3); font-size: 12px; border-top: 1px dashed var(--border); padding-top: 16px; }
/* 30 节：Agent 管理 / 工作区 */
.gen-box { background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; margin-bottom: 20px; }
.gen-row { display: flex; gap: 10px; align-items: center; }
.gen-input { flex: 1; background: var(--bg-mute); color: var(--text-1); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; font-size: 13px; margin-bottom: 10px; width: 100%; }
.gen-draft { width: 100%; background: var(--bg-mute); color: var(--text-1); border: 1px solid var(--border); border-radius: 6px; padding: 10px; font-size: 12px; margin-bottom: 10px; resize: vertical; }
.ws { display: flex; gap: 16px; align-items: flex-start; }
.ws-tree { width: 300px; flex-shrink: 0; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); padding: 8px; max-height: 70vh; overflow: auto; }
.ws-node { padding: 3px 6px; border-radius: 4px; cursor: default; font-size: 12px; }
.ws-node.file { cursor: pointer; }
.ws-node.file:hover { background: var(--bg-mute); }
.ws-node.on { background: var(--brand-soft); color: var(--brand); }
.ws-file { flex: 1; min-width: 0; }
/* 详情 Tab */
.tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--border); margin: 4px 0 16px; }
.tab { background: none; border: none; border-bottom: 2px solid transparent; color: var(--text-2); padding: 8px 14px; font-size: 13px; cursor: pointer; }
.tab:hover { color: var(--text-1); }
.tab.on { color: var(--brand); border-bottom-color: var(--brand); }
.info-grid { display: flex; flex-direction: column; gap: 1px; background: var(--border); border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; max-width: 720px; }
.info-row { display: flex; gap: 12px; background: var(--bg-soft); padding: 10px 14px; }
.info-row .k { width: 110px; flex-shrink: 0; color: var(--text-2); font-size: 12px; }
/* 可编辑文件 / 生成文件文本域 */
.filetext { width:100%; min-height:360px; background:var(--bg-mute); color:var(--text-1); border:1px solid var(--border); border-radius:6px; padding:12px; font-family:var(--font-mono); font-size:12px; line-height:1.5; resize:vertical; white-space:pre; }
.gen-file { margin-bottom: 16px; }
.chat-input { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--border); }

@media (max-width: 640px) {
  .login-card { padding: 22px; }
  .login-card h1 { font-size: 22px; }
  .topbar { justify-content: space-between; }
  .layout { flex-direction: column; }
  .nav { width: auto; flex-direction: row; flex-wrap: wrap; }
  .readonly { display: none; }
  .ws { flex-direction: column; }
  .ws-tree { width: auto; }
}
</style>
