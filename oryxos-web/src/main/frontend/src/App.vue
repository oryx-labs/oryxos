<script setup>
import { ref, reactive, computed } from 'vue'
import logoUrl from './assets/logo.svg'

// 顶层：概览 / Agent 列表 / 定时任务。「OS 运行时」下收纳 Provider/Tool/Sandbox/长期记忆/会话——
// 这些都是底座本身的运行时状态，跟业务 Agent 管理分层展示（31 节：侧边栏重分组）。
const TOP_NAV = [
  { key: 'overview', label: '概览' },
  { key: 'agents', label: 'Agent 列表' },
  { key: 'schedules', label: '定时任务', path: '/api/v1/schedules' },
  // Skill 列表 / 知识库：占位页，暂无 path（不拉数据）、渲染空列表，后续接入端点
  { key: 'skills', label: 'Skill 列表' },
  { key: 'knowledge', label: '知识库' },
]

const RUNTIME_NAV = [
  { key: 'sessions', label: '会话列表', path: '/api/v1/sessions' },
  { key: 'providers', label: 'Provider 列表' },
  { key: 'tools', label: 'Tool 列表', path: '/api/v1/tools' },
  { key: 'notify-channels', label: 'Notify 渠道' },
  { key: 'whitelist', label: 'SandBox 列表' },
]

const NAV = [...TOP_NAV, ...RUNTIME_NAV]
const runtimeKeys = new Set(RUNTIME_NAV.map((n) => n.key))
const runtimeOpen = ref(false) // OS 运行时分组展开状态

const active = ref('overview')
const state = reactive({}) // key -> {loading, error, data}
// 当前激活页（只渲染这一页，避免 v-show + v-for 的块补丁陷阱导致切不动）
const current = computed(() => NAV.find((n) => n.key === active.value) ?? NAV[0])

// 运行状态（原「运行状态」独立页，31 节并入概览展示）：应用名 + 已配置 Provider
const runtimeInfo = ref({ loading: true, error: null, data: null })
async function loadRuntimeInfo() {
  runtimeInfo.value = { loading: true, error: null, data: null }
  try {
    const res = await fetch('/api/v1/info')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    runtimeInfo.value = { loading: false, error: null, data: body.data }
  } catch (e) {
    runtimeInfo.value = { loading: false, error: e.message, data: null }
  }
}
loadRuntimeInfo()

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
    const res = await fetch(nav.path)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '请求失败')
    const data = nav.transform ? nav.transform(body.data) : body.data
    state[key] = { loading: false, error: null, data }
  } catch (e) {
    state[key] = { loading: false, error: e.message, data: null }
  }
}

function select(key) {
  active.value = key
  sessionDetail.value = null // 切页时收起会话详情
  execDetail.value = null // 切页时收起执行记录
  if (runtimeKeys.has(key)) runtimeOpen.value = true // 选中的是运行时子页 → 展开分组
  if (NAV.find((n) => n.key === key)?.path && !state[key]) load(key)
  if (key === 'agents') { agentDetail.value = null; fileView.value = null; loadAgents() }
  if (key === 'notify-channels') { cancelNc(); loadNotifyChannels() }
  if (key === 'providers') { cancelPv(); loadProviders() }
  if (key === 'whitelist') { cancelWl(); loadWhitelist() }
}

// 刷新当前页的列表：各页复用各自的加载函数（agents / notify-channels / 概览 / 其余按 path 的通用列表）
function refresh() {
  const key = active.value
  if (key === 'agents') { loadAgents(); return }
  if (key === 'notify-channels') { loadNotifyChannels(); return }
  if (key === 'providers') { loadProviders(); return }
  if (key === 'whitelist') { loadWhitelist(); return }
  if (key === 'overview') { loadRuntimeInfo(); return }
  if (NAV.find((n) => n.key === key)?.path) load(key)
}

// —— 会话详情：点一行会话，拉 GET /sessions/{id} 看完整对话内容 ——
const sessionDetail = ref(null) // {loading, error, id, data:{sessionId, profileName, messages[]}}

async function openSession(id) {
  sessionDetail.value = { loading: true, error: null, id, data: null }
  try {
    const res = await fetch(`/api/v1/sessions/${encodeURIComponent(id)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    sessionDetail.value = { loading: false, error: null, id, data: body.data }
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
    const res = await fetch(`/api/v1/schedules/${id}/run`, { method: 'POST' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '执行失败')
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
    const res = await fetch(`/api/v1/schedules/${encodeURIComponent(taskId)}/executions`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    execDetail.value = { loading: false, error: null, taskId, data: body.data }
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
    const res = await fetch(`/api/v1/schedules/${row.taskId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !row.enabled }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '切换失败')
    await load('schedules')
  } catch (e) {
    state.schedules = { ...state.schedules, error: e.message }
  } finally {
    busy.value = null
  }
}

// —— 30 节：Agent 管理（动态增删改 + 一句话生成）——
const agents = ref({ loading: false, error: null, data: [] })
const triggering = ref(null) // 正在“立即触发”的 agent 名，防重复点击
async function loadAgents() {
  agents.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/agents')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    agents.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) {
    agents.value = { loading: false, error: e.message, data: [] }
  }
}

// 新建 Agent：只填 name + description，后台按模板脚手架出完整目录
const gen = reactive({ open: false, name: '', description: '', busy: false, error: null })

async function createAgent() {
  gen.busy = true; gen.error = null
  try {
    const res = await fetch('/api/v1/agents', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: gen.name, description: gen.description }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '创建失败')
    cancelCreate(); await loadAgents()
  } catch (e) { gen.error = e.message } finally { gen.busy = false }
}

// 立即触发一次：内容用它定时任务的 message（没有就用通用触发语）。走这个 Agent 的固定会话（admin:console）——
// 与「详情 → 会话」tab 同一条 session，触发后在会话里就能看到这轮对话；跟到点自跑同一条 ReAct 链路。
async function triggerAgent(a) {
  const msg = a.schedules?.[0]?.message || '请立即执行一次你的任务。'
  triggering.value = a.name
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(a.name)}/session/messages`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: msg }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '触发失败')
    alert(`【${a.name}】已触发：\n\n${body.data?.reply || '(无回复)'}\n\n（对话已进入该 Agent 的会话，可在「详情 → 会话」查看）`)
  } catch (e) {
    alert(`【${a.name}】触发失败：${e.message}`)
  } finally {
    triggering.value = null
  }
}

async function deleteAgent(name) {
  if (!confirm(`删除 Agent「${name}」？（整个目录归档到 archive/，不物理删）`)) return
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    if (agentDetail.value?.name === name) closeAgent()
    await loadAgents()
  } catch (e) { agents.value = { ...agents.value, error: e.message } }
}

function cancelCreate() { gen.open = false; gen.name = ''; gen.description = ''; gen.error = null }

// —— Notify 渠道管理（CRUD /api/v1/notify-channels）：命名的通知出口，type ∈ feishu/wecom/dingtalk/webhook ——
const notifyChannels = ref({ loading: false, error: null, data: [] })
async function loadNotifyChannels() {
  notifyChannels.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/notify-channels')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    notifyChannels.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) {
    notifyChannels.value = { loading: false, error: e.message, data: [] }
  }
}

// 新建/编辑表单：editing 存被编辑渠道的 name（此时 name 只读），null 表示新建
const nc = reactive({ open: false, editing: null, name: '', type: 'feishu', url: '', description: '', busy: false, error: null })

async function saveNotifyChannel() {
  nc.busy = true; nc.error = null
  try {
    const url = nc.editing
      ? `/api/v1/notify-channels/${encodeURIComponent(nc.editing)}`
      : '/api/v1/notify-channels'
    const payload = nc.editing
      ? { type: nc.type, url: nc.url, description: nc.description }
      : { name: nc.name, type: nc.type, url: nc.url, description: nc.description }
    const res = await fetch(url, {
      method: nc.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    cancelNc(); await loadNotifyChannels()
  } catch (e) { nc.error = e.message } finally { nc.busy = false }
}

function editNotifyChannel(row) {
  nc.editing = row.name
  nc.name = row.name
  nc.type = row.type || 'feishu'
  nc.url = row.url || ''
  nc.description = row.description || ''
  nc.error = null
  nc.open = true
}

function cancelNc() {
  nc.open = false; nc.editing = null; nc.name = ''; nc.type = 'feishu'; nc.url = ''; nc.description = ''; nc.error = null
}

async function deleteNotifyChannel(name) {
  if (!confirm(`删除 Notify 渠道「${name}」？`)) return
  try {
    const res = await fetch(`/api/v1/notify-channels/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadNotifyChannels()
  } catch (e) { notifyChannels.value = { ...notifyChannels.value, error: e.message } }
}

// —— Provider 管理（CRUD /api/v1/providers）：命名的模型 Provider，apiKey 明文返回 ——
const providers = ref({ loading: false, error: null, data: [] })
async function loadProviders() {
  providers.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/providers')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    providers.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) {
    providers.value = { loading: false, error: e.message, data: [] }
  }
}

// 新建/编辑表单：editing 存被编辑 Provider 的 name（此时 name 只读），null 表示新建
const pv = reactive({ open: false, editing: null, name: '', apiKey: '', baseUrl: '', description: '', busy: false, error: null })

async function saveProvider() {
  pv.busy = true; pv.error = null
  try {
    const url = pv.editing
      ? `/api/v1/providers/${encodeURIComponent(pv.editing)}`
      : '/api/v1/providers'
    const payload = pv.editing
      ? { apiKey: pv.apiKey, baseUrl: pv.baseUrl, description: pv.description }
      : { name: pv.name, apiKey: pv.apiKey, baseUrl: pv.baseUrl, description: pv.description }
    const res = await fetch(url, {
      method: pv.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    cancelPv(); await loadProviders()
  } catch (e) { pv.error = e.message } finally { pv.busy = false }
}

function editProvider(row) {
  pv.editing = row.name
  pv.name = row.name
  pv.apiKey = row.apiKey || ''
  pv.baseUrl = row.baseUrl || ''
  pv.description = row.description || ''
  pv.error = null
  pv.open = true
}

function cancelPv() {
  pv.open = false; pv.editing = null; pv.name = ''; pv.apiKey = ''; pv.baseUrl = ''; pv.description = ''; pv.error = null
}

async function deleteProvider(name) {
  if (!confirm(`删除 Provider「${name}」？`)) return
  try {
    const res = await fetch(`/api/v1/providers/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadProviders()
  } catch (e) { providers.value = { ...providers.value, error: e.message } }
}

// —— Sandbox 白名单管理（CRUD /api/v1/sandbox/whitelist）：三类 file/shell/http 的白名单条目 ——
const WL_CATS = [
  { key: 'file', label: '文件路径', ph: '允许访问的路径，如 /data 或 /tmp/*' },
  { key: 'shell', label: 'Shell 命令', ph: '允许执行的命令首 token，如 ls' },
  { key: 'http', label: 'HTTP 域名', ph: '允许访问的域名，如 *.example.com' },
]
const wl = ref({ loading: false, error: null, file: [], shell: [], http: [] })
async function loadWhitelist() {
  wl.value = { loading: true, error: null, file: [], shell: [], http: [] }
  try {
    const res = await fetch('/api/v1/sandbox/whitelist')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    const d = body.data || {}
    wl.value = { loading: false, error: null, file: d.file || [], shell: d.shell || [], http: d.http || [] }
  } catch (e) {
    wl.value = { loading: false, error: e.message, file: [], shell: [], http: [] }
  }
}

// 新增白名单表单：category ∈ file/shell/http，value 为一条白名单条目
const wlForm = reactive({ open: false, category: 'file', value: '', busy: false, error: null })
const wlPlaceholder = computed(() => WL_CATS.find((c) => c.key === wlForm.category)?.ph || '')

async function addWhitelist() {
  wlForm.busy = true; wlForm.error = null
  try {
    const res = await fetch(`/api/v1/sandbox/whitelist/${encodeURIComponent(wlForm.category)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value: wlForm.value }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '新增失败')
    cancelWl(); await loadWhitelist()
  } catch (e) { wlForm.error = e.message } finally { wlForm.busy = false }
}

function cancelWl() { wlForm.open = false; wlForm.category = 'file'; wlForm.value = ''; wlForm.error = null }

async function deleteWhitelist(category, value) {
  if (!confirm(`删除白名单条目「${value}」？`)) return
  try {
    const res = await fetch(`/api/v1/sandbox/whitelist/${encodeURIComponent(category)}?value=${encodeURIComponent(value)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadWhitelist()
  } catch (e) { wl.value = { ...wl.value, error: e.message } }
}

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
    const res = await fetch('/api/v1/workspace/tree')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    const agentsNode = (body.data.children || []).find((c) => c.name === 'agents')
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
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}`)
    const body = await res.json()
    if (body.code === 0 && body.data) {
      agentDetail.value = { ...agentDetail.value, agent: body.data }
    }
  } catch (e) {
    /* 元数据刷新失败不阻断，忽略 */
  }
  try {
    const res = await fetch('/api/v1/workspace/tree')
    const body = await res.json()
    if (body.code === 0) {
      const agentsNode = (body.data.children || []).find((c) => c.name === 'agents')
      const node = (agentsNode?.children || []).find((c) => c.name === name) || null
      agentDetail.value = { ...agentDetail.value, node }
    }
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
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/generate-files`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: genEdit.description }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '生成失败')
    genEdit.files = body.data.files || {}
  } catch (e) { genEdit.error = e.message } finally { genEdit.busy = false }
}

async function saveFiles() {
  genEdit.busy = true; genEdit.error = null
  try {
    const name = agentDetail.value.name
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/files`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ files: genEdit.files }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
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
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/session`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    chat.sessionId = body.data.sessionId
    chat.messages = body.data.messages || []
  } catch (e) { chat.error = e.message } finally { chat.loading = false }
}

async function sendChat() {
  if (!chat.input.trim()) return
  chat.sending = true; chat.error = null
  try {
    const name = agentDetail.value.name
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/session/messages`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: chat.input }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '发送失败')
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
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/memory`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    agentMemory.text = body.data || ''
  } catch (e) { agentMemory.error = e.message } finally { agentMemory.loading = false }
}

// 把 MEMORY.md 文本解析成核心/归档两组行：每行 "- [时间] 内容" → {time, content}
const memoryTables = computed(() => {
  const core = [], archival = []
  let bucket = null
  for (const raw of (agentMemory.text || '').split('\n')) {
    const line = raw.trim()
    if (line.startsWith('## 核心记忆')) { bucket = core; continue }
    if (line.startsWith('## 归档记忆')) { bucket = archival; continue }
    if (!bucket || !line.startsWith('- ')) continue
    const body = line.slice(2)
    const m = body.match(/^\[([^\]]+)\]\s*(.*)$/)
    bucket.push(m ? { time: m[1], content: m[2] } : { time: '', content: body })
  }
  return { core, archival }
})

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
    const res = await fetch(`/api/v1/workspace/file?path=${encodeURIComponent(node.path)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    fileView.value = { path: node.path, loading: false, error: null, content: body.data, saving: false, saved: false }
  } catch (e) {
    fileView.value = { path: node.path, loading: false, error: e.message, content: '', saving: false, saved: false }
  }
}

// Tab 3：保存当前文件（编辑后写回工作区）
async function saveFile() {
  if (!fileView.value) return
  fileView.value = { ...fileView.value, saving: true, error: null, saved: false }
  try {
    const res = await fetch('/api/v1/workspace/file', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: fileView.value.path, content: fileView.value.content }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
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
  <div class="layout">
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
          <div class="page-head">
            <h2>{{ current.label }}</h2>
            <button class="btn" @click="refresh()">刷新</button>
          </div>

          <!-- Skill 列表：占位空列表（待接入 Skill 端点） -->
          <table v-if="active === 'skills'">
            <thead><tr><th>名称</th><th>描述</th></tr></thead>
            <tbody><tr><td colspan="2" class="empty">（暂无 Skill · 待接入 Skill 端点）</td></tr></tbody>
          </table>

          <!-- 知识库：占位空列表（待接入知识库端点） -->
          <table v-else-if="active === 'knowledge'">
            <thead><tr><th>名称</th><th>描述</th></tr></thead>
            <tbody><tr><td colspan="2" class="empty">（暂无知识库条目 · 待接入知识库端点）</td></tr></tbody>
          </table>

          <!-- Sandbox 白名单：三类 file/shell/http 的 CRUD（新增走弹框 / 逐行删除） -->
          <div v-else-if="active === 'whitelist'">
            <div class="toolbar">
              <button class="btn" @click="wlForm.open = true">+ 新增白名单</button>
            </div>
            <!-- 新增白名单 弹出框 -->
            <div v-if="wlForm.open" class="modal-overlay" @click.self="cancelWl()">
              <div class="modal-card">
                <div class="modal-head"><h3>新增白名单</h3><button class="modal-x" @click="cancelWl()">✕</button></div>
                <div class="modal-body">
                  <select v-model="wlForm.category" class="gen-input">
                    <option value="file">文件路径</option>
                    <option value="shell">Shell 命令</option>
                    <option value="http">HTTP 域名</option>
                  </select>
                  <input v-model="wlForm.value" class="gen-input" :placeholder="wlPlaceholder" />
                  <p class="empty">选择类别并填写一条白名单条目：文件路径 / Shell 命令首 token / HTTP 域名（支持通配，如 *.example.com）。</p>
                  <p v-if="wlForm.error" class="error">{{ wlForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelWl">取消</button>
                  <button class="btn" :disabled="wlForm.busy || !wlForm.value.trim()" @click="addWhitelist">新增</button>
                </div>
              </div>
            </div>
            <p v-if="wl.loading" class="empty">加载中…</p>
            <p v-else-if="wl.error" class="error">出错：{{ wl.error }}</p>
            <template v-else>
              <div v-for="cat in WL_CATS" :key="cat.key">
                <h3 class="sec" style="margin-top:20px">{{ cat.label }}</h3>
                <table>
                  <thead><tr><th>规则</th><th style="width:90px">操作</th></tr></thead>
                  <tbody>
                    <tr v-if="!wl[cat.key].length"><td colspan="2" class="empty">（暂无{{ cat.label }}白名单）</td></tr>
                    <tr v-for="(entry, i) in wl[cat.key]" :key="i">
                      <td class="mono">{{ entry }}</td>
                      <td class="ops"><button class="btn" @click="deleteWhitelist(cat.key, entry)">删除</button></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>

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
                  <h3 class="sec">核心记忆</h3>
                  <table>
                    <thead><tr><th style="width:170px">时间</th><th>内容</th></tr></thead>
                    <tbody>
                      <tr v-if="!memoryTables.core.length"><td colspan="2" class="empty">（暂无核心记忆）</td></tr>
                      <tr v-for="(m, i) in memoryTables.core" :key="'c'+i">
                        <td class="mono">{{ m.time || '—' }}</td><td>{{ m.content }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <h3 class="sec" style="margin-top:20px">归档记忆</h3>
                  <table>
                    <thead><tr><th style="width:170px">时间</th><th>内容</th></tr></thead>
                    <tbody>
                      <tr v-if="!memoryTables.archival.length"><td colspan="2" class="empty">（暂无归档记忆）</td></tr>
                      <tr v-for="(m, i) in memoryTables.archival" :key="'a'+i">
                        <td class="mono">{{ m.time || '—' }}</td><td>{{ m.content }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p class="empty" style="margin-top:8px">由 save_memory 工具与每次触发写入，此处只读。</p>
                </template>
              </div>
            </div>

            <!-- 列表视图：所有 Agent + 新建（只填 name + description，后台脚手架完整目录） -->
            <template v-else>
              <div class="toolbar">
                <button class="btn" @click="gen.open = true">+ 新建 Agent</button>
              </div>
              <!-- 新建 Agent 弹出框 -->
              <div v-if="gen.open" class="modal-overlay" @click.self="cancelCreate()">
                <div class="modal-card">
                  <div class="modal-head"><h3>新建 Agent</h3><button class="modal-x" @click="cancelCreate()">✕</button></div>
                  <div class="modal-body">
                    <input v-model="gen.name" class="gen-input" placeholder="Agent 名（字母/数字/下划线/连字符）" />
                    <input v-model="gen.description" class="gen-input" placeholder="描述这个 Agent 做什么" />
                    <p class="empty">创建后后台自动生成完整目录：AGENT.md + scripts/ + skills/ + REFERENCE.md（模板内容，可在文件浏览器查看）。</p>
                    <p v-if="gen.error" class="error">{{ gen.error }}</p>
                  </div>
                  <div class="modal-foot">
                    <button class="btn" @click="cancelCreate">取消</button>
                    <button class="btn" :disabled="gen.busy || !gen.name" @click="createAgent">创建</button>
                  </div>
                </div>
              </div>
              <p v-if="agents.loading" class="empty">加载中…</p>
              <p v-else-if="agents.error" class="error">出错：{{ agents.error }}</p>
              <table v-else>
                <thead><tr><th>name</th><th>description</th><th>provider</th><th>tools</th><th>定时</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-if="!agents.data.length"><td colspan="6" class="empty">（暂无 Agent · 点上面「新建 Agent」，或往 .oryxos/agents/ 丢一个目录）</td></tr>
                  <tr v-for="a in agents.data" :key="a.name">
                    <td class="mono">{{ a.name }}</td>
                    <td>{{ a.description || '—' }}</td>
                    <td>{{ a.provider }}</td>
                    <td>{{ (a.tools || []).join(', ') }}</td>
                    <td class="mono">{{ (a.schedules || []).map((s) => s.cron).join('; ') || '—' }}</td>
                    <td class="ops">
                      <button class="btn" :disabled="triggering === a.name" @click="triggerAgent(a)">{{ triggering === a.name ? '触发中…' : '立即触发' }}</button>
                      <button class="btn" @click="openAgent(a)">详情</button>
                      <button class="btn" @click="deleteAgent(a.name)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
          </div>

          <!-- Notify 渠道：命名通知出口的 CRUD（新建/编辑/删除） -->
          <div v-else-if="active === 'notify-channels'">
            <div class="toolbar">
              <button class="btn" @click="nc.open = true">+ 新建渠道</button>
            </div>
            <!-- 新建/编辑通知渠道 弹出框 -->
            <div v-if="nc.open" class="modal-overlay" @click.self="cancelNc()">
              <div class="modal-card">
                <div class="modal-head"><h3>{{ nc.editing ? '编辑通知渠道' : '新建通知渠道' }}</h3><button class="modal-x" @click="cancelNc()">✕</button></div>
                <div class="modal-body">
                  <input v-model="nc.name" class="gen-input" :disabled="!!nc.editing" placeholder="渠道名（唯一标识）" />
                  <select v-model="nc.type" class="gen-input">
                    <option value="feishu">feishu</option>
                    <option value="wecom">wecom</option>
                    <option value="dingtalk">dingtalk</option>
                    <option value="webhook">webhook</option>
                  </select>
                  <input v-model="nc.url" class="gen-input" placeholder="Webhook URL" />
                  <input v-model="nc.description" class="gen-input" placeholder="描述（可选）" />
                  <p class="empty">{{ nc.editing ? '编辑现有渠道，渠道名不可改。' : 'type 支持 feishu / wecom / dingtalk / webhook；URL 为对应的 Webhook 地址。' }}</p>
                  <p v-if="nc.error" class="error">{{ nc.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelNc">取消</button>
                  <button class="btn" :disabled="nc.busy || !nc.name || !nc.url" @click="saveNotifyChannel">{{ nc.editing ? '保存修改' : '创建' }}</button>
                </div>
              </div>
            </div>
            <p v-if="notifyChannels.loading" class="empty">加载中…</p>
            <p v-else-if="notifyChannels.error" class="error">出错：{{ notifyChannels.error }}</p>
            <table v-else>
              <thead><tr><th>name</th><th>type</th><th>url</th><th>description</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!notifyChannels.data.length"><td colspan="5" class="empty">（暂无 Notify 渠道 · 点上面「新建渠道」）</td></tr>
                <tr v-for="c in notifyChannels.data" :key="c.name">
                  <td class="mono">{{ c.name }}</td>
                  <td>{{ c.type }}</td>
                  <td class="mono">{{ c.url }}</td>
                  <td>{{ c.description || '—' }}</td>
                  <td class="ops">
                    <button class="btn" @click="editNotifyChannel(c)">编辑</button>
                    <button class="btn" @click="deleteNotifyChannel(c.name)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Provider：命名模型 Provider 的 CRUD（新建/编辑/删除），apiKey 明文展示 -->
          <div v-else-if="active === 'providers'">
            <div class="toolbar">
              <button class="btn" @click="pv.open = true">+ 新建 Provider</button>
            </div>
            <!-- 新建/编辑 Provider 弹出框 -->
            <div v-if="pv.open" class="modal-overlay" @click.self="cancelPv()">
              <div class="modal-card">
                <div class="modal-head"><h3>{{ pv.editing ? '编辑 Provider' : '新建 Provider' }}</h3><button class="modal-x" @click="cancelPv()">✕</button></div>
                <div class="modal-body">
                  <input v-model="pv.name" class="gen-input" :disabled="!!pv.editing" placeholder="Provider 名（唯一标识）" />
                  <input v-model="pv.apiKey" class="gen-input" placeholder="api-key；mock provider 可留空" />
                  <input v-model="pv.baseUrl" class="gen-input" placeholder="https://api.deepseek.com；mock 可留空" />
                  <input v-model="pv.description" class="gen-input" placeholder="描述（可选）" />
                  <p class="empty">{{ pv.editing ? '编辑现有 Provider，Provider 名不可改。' : 'name 为 ProviderService 显式映射的 key；apiKey / baseUrl 对 mock provider 可留空。' }}</p>
                  <p v-if="pv.error" class="error">{{ pv.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelPv">取消</button>
                  <button class="btn" :disabled="pv.busy || !pv.name" @click="saveProvider">{{ pv.editing ? '保存修改' : '创建' }}</button>
                </div>
              </div>
            </div>
            <p v-if="providers.loading" class="empty">加载中…</p>
            <p v-else-if="providers.error" class="error">出错：{{ providers.error }}</p>
            <table v-else>
              <thead><tr><th>name</th><th>apiKey</th><th>baseUrl</th><th>description</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!providers.data.length"><td colspan="5" class="empty">（暂无 Provider · 点上面「新建 Provider」）</td></tr>
                <tr v-for="p in providers.data" :key="p.name">
                  <td class="mono">{{ p.name }}</td>
                  <td class="mono">{{ p.apiKey || '—' }}</td>
                  <td class="mono">{{ p.baseUrl || '—' }}</td>
                  <td>{{ p.description || '—' }}</td>
                  <td class="ops">
                    <button class="btn" @click="editProvider(p)">编辑</button>
                    <button class="btn" @click="deleteProvider(p.name)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
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
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
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
.chat { display: flex; flex-direction: column; gap: 12px; max-height: 60vh; overflow-y: auto; padding: 4px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-soft); }
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
/* 列表页新建按钮工具栏 */
.toolbar { margin-bottom: 16px; }
/* 弹出框（新建/编辑表单） */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 100; padding: 20px; }
.modal-card { background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius); width: 100%; max-width: 520px; max-height: 85vh; overflow-y: auto; box-shadow: 0 10px 40px rgba(0,0,0,0.3); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--border); }
.modal-head h3 { margin: 0; font-size: 15px; }
.modal-x { background: none; border: none; color: var(--text-3, var(--text-2)); font-size: 16px; cursor: pointer; }
.modal-body { padding: 18px; display: flex; flex-direction: column; gap: 10px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 18px; border-top: 1px solid var(--border); }
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

@media (max-width: 640px) { .layout { flex-direction: column; } .nav { width: auto; flex-direction: row; flex-wrap: wrap; } .readonly { display: none; } .ws { flex-direction: column; } .ws-tree { width: auto; } }
</style>
