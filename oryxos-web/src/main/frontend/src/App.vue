<script setup>
import { ref, reactive, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import logoUrl from './assets/logo.svg'
import LoginView from './views/LoginView.vue'

// —— 012-web-auth US3：登录守卫 —— 未登录先查 /api/v1/auth/me；登录页 LoginView 调 /auth/login
const auth = reactive({ checking: true, enabled: true, username: null })
async function checkAuth() {
  auth.checking = true
  try {
    const res = await fetch('/api/v1/auth/me')
    const body = await res.json()
    if (res.status === 200 && body.code === 0) {
      auth.enabled = body.data?.authenticationEnabled !== false
      auth.username = body.data?.username || null
    } else {
      auth.enabled = true
      auth.username = null
    }
  } catch (e) {
    auth.enabled = true
    auth.username = null
  } finally {
    auth.checking = false
  }
}
checkAuth()

function onLogined(username) {
  auth.username = username
}

async function logout() {
  try {
    await fetch('/api/v1/auth/logout', { method: 'POST' })
  } catch (e) {
    /* 忽略，仍跳登录页 */
  }
  auth.username = null
  active.value = 'overview'
}

// 顶层：概览 / Agent 列表 / 定时任务。「OS 运行时」下收纳 Provider/Tool/Sandbox/长期记忆/会话——
// 这些都是底座本身的运行时状态，跟业务 Agent 管理分层展示（31 节：侧边栏重分组）。
const TOP_NAV = [
  { key: 'overview', label: '概览' },
  { key: 'agents', label: 'Agent 列表' },
  { key: 'schedules', label: '定时任务', path: '/api/v1/schedules' },
  // Skill 列表（第 32 节）：全局 Skill 库，自定义加载器（loadSkills）不走通用 path。知识库仍为占位页
  { key: 'skills', label: 'Skill 列表' },
  { key: 'knowledge', label: '知识库' },
]

const RUNTIME_NAV = [
  { key: 'sessions', label: '会话列表', path: '/api/v1/sessions' },
  { key: 'providers', label: 'Provider 列表' },
  { key: 'mcp', label: 'MCP 管理' },
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
  if (key === 'mcp') { cancelMcp(); loadMcp(); loadMcpCatalog() }
  if (key === 'skills') { cancelSkill(); closeSkillDetail(); loadSkills() }
}

// 刷新当前页的列表：各页复用各自的加载函数（agents / notify-channels / 概览 / 其余按 path 的通用列表）
function refresh() {
  const key = active.value
  if (key === 'agents') { loadAgents(); return }
  if (key === 'notify-channels') { loadNotifyChannels(); return }
  if (key === 'providers') { loadProviders(); return }
  if (key === 'whitelist') { loadWhitelist(); return }
  if (key === 'mcp') { loadMcp(); return }
  if (key === 'skills') { loadSkills(); return }
  if (key === 'overview') { loadRuntimeInfo(); return }
  if (NAV.find((n) => n.key === key)?.path) load(key)
}

// —— Skill 列表（第 32 节）：全局 Skill 库 CRUD。Agent 通过 AGENT.md 的 skills:[名] 引用，运行时注入正文约束产出 ——
const skills = ref({ loading: false, error: null, data: [] })
async function loadSkills() {
  skills.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/skills')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    skills.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { skills.value = { loading: false, error: e.message, data: [] } }
}
const skillForm = reactive({ open: false, editing: null, name: '', description: '', body: '', busy: false, error: null })
function newSkill() {
  skillForm.editing = null; skillForm.name = ''; skillForm.description = ''; skillForm.body = ''
  skillForm.error = null; skillForm.open = true
}
function editSkill(row) {
  skillForm.editing = row.name; skillForm.name = row.name
  skillForm.description = row.description || ''; skillForm.body = row.body || ''
  skillForm.error = null; skillForm.open = true
}
function cancelSkill() {
  skillForm.open = false; skillForm.editing = null; skillForm.name = ''
  skillForm.description = ''; skillForm.body = ''; skillForm.error = null
}
async function saveSkill() {
  skillForm.busy = true; skillForm.error = null
  try {
    const url = skillForm.editing ? `/api/v1/skills/${encodeURIComponent(skillForm.editing)}` : '/api/v1/skills'
    const payload = skillForm.editing
      ? { description: skillForm.description, body: skillForm.body }
      : { name: skillForm.name, description: skillForm.description, body: skillForm.body }
    const res = await fetch(url, {
      method: skillForm.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    cancelSkill(); await loadSkills()
  } catch (e) { skillForm.error = e.message } finally { skillForm.busy = false }
}
async function deleteSkill(name) {
  if (!confirm(`删除 Skill「${name}」？引用它的 Agent 下次触发将跳过该约束。`)) return
  try {
    const res = await fetch(`/api/v1/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadSkills()
  } catch (e) { skills.value = { ...skills.value, error: e.message } }
}
// 从 URL 导入 Skill：后端 GET 拉取该地址的 SKILL.md 文本并建库
const skillImport = reactive({ open: false, url: '', name: '', busy: false, error: null })
function newImport() { skillImport.open = true; skillImport.url = ''; skillImport.name = ''; skillImport.error = null }
function cancelImport() { skillImport.open = false; skillImport.url = ''; skillImport.name = ''; skillImport.error = null }
async function importSkill() {
  skillImport.busy = true; skillImport.error = null
  try {
    const res = await fetch('/api/v1/skills/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: skillImport.url, name: skillImport.name || null }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '导入失败')
    cancelImport(); await loadSkills()
  } catch (e) { skillImport.error = e.message } finally { skillImport.busy = false }
}
// —— Skill 详情：点「详情」→ 拉工作区树里的 skills/<name> 子树，复用同一套文件浏览器（openFile/fileView + md 预览）——
const skillDetail = ref(null) // { name, description, body, loading, error, node }
async function openSkillDetail(row) {
  skillDetail.value = { name: row.name, description: row.description || '', body: row.body || '', loading: true, error: null, node: null }
  fileView.value = null // 从「未选中」开始，避免跨视图串台预览
  try {
    const res = await fetch('/api/v1/workspace/tree')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    const skillsNode = (body.data.children || []).find((c) => c.name === 'skills')
    const node = (skillsNode?.children || []).find((c) => c.name === row.name) || null
    skillDetail.value = { ...skillDetail.value, loading: false, node }
  } catch (e) {
    skillDetail.value = { ...skillDetail.value, loading: false, error: e.message }
  }
}
function closeSkillDetail() { skillDetail.value = null; fileView.value = null }
// 该 Skill 目录的文件行（扁平带缩进，复用 Agent 工作区同一个 flatten）
const skillDetailRows = computed(() => (skillDetail.value?.node ? flatten(skillDetail.value.node, 0, []) : []))
// 回退：旧后端 tree 无 skills 节点时，直接渲染 SKILL.md 正文（body）
const skillDetailBodyMd = computed(() =>
  skillDetail.value?.body ? DOMPurify.sanitize(marked.parse(skillDetail.value.body)) : ''
)

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

// 新建 Agent：独立成页（不再是弹框），把「大模型生成」折叠进来。
// 只填 name + description 可直接按模板脚手架；也可先「用大模型生成」各文件、编辑后再创建。
const agentCreate = reactive({ open: false, name: '', description: '', notifyChannel: '', skills: [], files: null, busy: false, error: '' })

// 打开新建页：重置字段 + 拉通知渠道下拉数据
function openCreate() {
  agentCreate.open = true
  agentCreate.name = ''
  agentCreate.description = ''
  agentCreate.notifyChannel = ''
  agentCreate.skills = []
  agentCreate.files = null
  agentCreate.busy = false
  agentCreate.error = ''
  loadNotifyChannels()
  loadSkills() // Skill 选择器的数据源（可手动指定必启用的 Skill；不选则由作者模型自动选）
}

function cancelCreate() { agentCreate.open = false }

// 用大模型按描述生成各文件内容（需先填 name），生成后可逐个编辑
async function generateFiles() {
  if (!agentCreate.name.trim()) { agentCreate.error = '请先填写 Agent 名'; return }
  agentCreate.busy = true; agentCreate.error = ''
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentCreate.name)}/generate-files`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: agentCreate.description, notifyChannel: agentCreate.notifyChannel, skills: agentCreate.skills }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '生成失败')
    agentCreate.files = body.data.files || {}
  } catch (e) { agentCreate.error = e.message } finally { agentCreate.busy = false }
}

// 创建：已生成文件→写盘并注册（POST /files）；未生成→按模板脚手架（POST /agents）
async function submitCreate() {
  if (!agentCreate.name.trim()) { agentCreate.error = '请先填写 Agent 名'; return }
  agentCreate.busy = true; agentCreate.error = ''
  try {
    const res = agentCreate.files
      ? await fetch(`/api/v1/agents/${encodeURIComponent(agentCreate.name)}/files`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ files: agentCreate.files }),
        })
      : await fetch('/api/v1/agents', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: agentCreate.name, description: agentCreate.description }),
        })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '创建失败')
    agentCreate.open = false
    await loadAgents()
  } catch (e) { agentCreate.error = e.message } finally { agentCreate.busy = false }
}

// 立即触发一次（异步）：后端立即返回执行记录 id，ReAct 在后台跑——不再干等整轮（消除 Failed to fetch）。
// 内容用它定时任务的 message（没有就用通用触发语）。结果进该 Agent 固定会话，状态见「执行历史」tab。
async function triggerAgent(a) {
  const msg = a.schedules?.[0]?.message || '请立即执行一次你的任务。'
  triggering.value = a.name
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(a.name)}/trigger`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: msg }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '触发失败')
    alert(`【${a.name}】已触发，正在后台执行（执行 #${body.data?.executionId}）。\n\n进度看「详情 → 执行历史」，结果看「详情 → 会话」。`)
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

// —— MCP 管理（CRUD /api/v1/mcp-servers + 内置目录一键启用）：31 节 ——
// 增/改/删都是「落盘 + 立即生效」，不用重启 OryxOS；env/headers 在表单里用每行 KEY=VALUE 简化编辑。
const mcp = ref({ loading: false, error: null, data: [] })
const mcpStatusByName = ref({}) // name -> {connected, error, toolNames}
const mcpCatalog = ref({ loading: false, error: null, data: [] })

async function loadMcp() {
  mcp.value = { loading: true, error: null, data: [] }
  try {
    const [listRes, statusRes] = await Promise.all([
      fetch('/api/v1/mcp-servers'),
      fetch('/api/v1/mcp-servers/status'),
    ])
    const listBody = await listRes.json()
    const statusBody = await statusRes.json()
    if (listBody.code !== 0) throw new Error(listBody.message || '加载失败')
    const byName = {}
    for (const s of (statusBody.data || [])) byName[s.name] = s
    mcpStatusByName.value = byName
    mcp.value = { loading: false, error: null, data: listBody.data || [] }
  } catch (e) {
    mcp.value = { loading: false, error: e.message, data: [] }
  }
}

async function loadMcpCatalog() {
  mcpCatalog.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/mcp-servers/catalog')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    mcpCatalog.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) {
    mcpCatalog.value = { loading: false, error: e.message, data: [] }
  }
}

// 把「KEY=VALUE」每行文本 <-> Map 互转，env/headers 表单编辑用
function mapToText(map) {
  return Object.entries(map || {}).map(([k, v]) => `${k}=${v}`).join('\n')
}
function textToMap(text) {
  const map = {}
  for (const line of (text || '').split('\n')) {
    const s = line.trim()
    if (!s || !s.includes('=')) continue
    const i = s.indexOf('=')
    map[s.slice(0, i).trim()] = s.slice(i + 1).trim()
  }
  return map
}

// 新建/编辑表单：editing 存被编辑 server 的 name（此时 name 只读），null 表示新建
const mcpForm = reactive({
  open: false, editing: null, name: '', transport: 'stdio',
  command: '', url: '', envText: '', headersText: '', busy: false, error: null,
})

function editMcp(row) {
  mcpForm.editing = row.name
  mcpForm.name = row.name
  mcpForm.transport = row.transport || 'stdio'
  mcpForm.command = row.command || ''
  mcpForm.url = row.url || ''
  mcpForm.envText = mapToText(row.env)
  mcpForm.headersText = mapToText(row.headers)
  mcpForm.error = null
  mcpForm.open = true
}

function cancelMcp() {
  mcpForm.open = false; mcpForm.editing = null; mcpForm.name = ''; mcpForm.transport = 'stdio'
  mcpForm.command = ''; mcpForm.url = ''; mcpForm.envText = ''; mcpForm.headersText = ''; mcpForm.error = null
}

async function saveMcp() {
  mcpForm.busy = true; mcpForm.error = null
  try {
    const url = mcpForm.editing
      ? `/api/v1/mcp-servers/${encodeURIComponent(mcpForm.editing)}`
      : '/api/v1/mcp-servers'
    const payload = {
      name: mcpForm.name, transport: mcpForm.transport,
      command: mcpForm.transport === 'stdio' ? mcpForm.command : null,
      url: mcpForm.transport === 'http' ? mcpForm.url : null,
      env: textToMap(mcpForm.envText), headers: textToMap(mcpForm.headersText),
    }
    const res = await fetch(url, {
      method: mcpForm.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    cancelMcp(); await loadMcp()
  } catch (e) { mcpForm.error = e.message } finally { mcpForm.busy = false }
}

async function deleteMcp(name) {
  if (!confirm(`删除 MCP server「${name}」？（会立即断开连接、注销它的工具）`)) return
  try {
    const res = await fetch(`/api/v1/mcp-servers/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadMcp()
  } catch (e) { mcp.value = { ...mcp.value, error: e.message } }
}

// 内置目录「一键启用」：选一条目录条目 → 填它要求的凭证 → 直接 add
const mcpEnable = reactive({ open: false, entry: null, name: '', credentials: {}, busy: false, error: null })

function openEnable(entry) {
  mcpEnable.entry = entry
  mcpEnable.name = entry.id
  mcpEnable.credentials = Object.fromEntries((entry.requiredEnv || []).map((k) => [k, '']))
  mcpEnable.error = null
  mcpEnable.open = true
}

function cancelEnable() {
  mcpEnable.open = false; mcpEnable.entry = null; mcpEnable.name = ''; mcpEnable.credentials = {}; mcpEnable.error = null
}

async function submitEnable() {
  mcpEnable.busy = true; mcpEnable.error = null
  try {
    const res = await fetch(`/api/v1/mcp-servers/catalog/${encodeURIComponent(mcpEnable.entry.id)}/enable`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: mcpEnable.name, credentials: mcpEnable.credentials }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '启用失败')
    cancelEnable(); await loadMcp()
  } catch (e) { mcpEnable.error = e.message } finally { mcpEnable.busy = false }
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

// —— Agent 详情：Tab 切换（基本信息 / 文件 / 会话 / 记忆）——
const agentDetail = ref(null) // { name, agent, tab, loading, error, node }
const fileView = ref(null) // { path, loading, error, content, saving, saved }
// .md 文件视图切换：'preview'（渲染，默认）/ 'source'（原文，可编辑）。共享一个 ref——
// 工作区/输出两个浏览器同时只显示一个，且都复用同一个 fileView。
const mdView = ref('preview')
const fileIsMarkdown = computed(() => /\.md$/i.test(fileView.value?.path || ''))
// v-html 注入前必须过 DOMPurify：文件内容可能来自「从 GitHub 拉取 Skill」等外部导入，不是纯本地可信内容
const renderedMd = computed(() =>
  fileView.value && fileIsMarkdown.value
    ? DOMPurify.sanitize(marked.parse(fileView.value.content || ''))
    : ''
)
// 会话：每个 Agent 一个固定 session，直接作为对话展示（不再是会话列表）
const chat = reactive({ sessionId: null, messages: [], loading: false, error: null, input: '', sending: false })
// 把扁平消息按「一轮对话」分组：user 起一轮，中间的助手思考 + 工具调用收进 steps（默认折叠），最后一条助手作为最终答案
const chatTurns = computed(() => {
  const turns = []
  let cur = null
  for (const m of chat.messages) {
    if (m.role === 'user') {
      cur = { user: m, mids: [] }
      turns.push(cur)
    } else {
      if (!cur) { cur = { user: null, mids: [] }; turns.push(cur) }
      cur.mids.push(m)
    }
  }
  return turns.map((t) => {
    const steps = t.mids.slice()
    // 末尾若是助手消息 → 作为最终答案单独拎出；其余（思考 + 工具往返）为过程
    const answer = steps.length && steps[steps.length - 1].role === 'assistant' ? steps.pop() : null
    return { user: t.user, steps, answer }
  })
})
// 每轮「过程」的展开状态（按轮次下标；重新加载会重置，可接受）
const expandedTurns = reactive(new Set())
function toggleTurn(i) {
  if (expandedTurns.has(i)) expandedTurns.delete(i)
  else expandedTurns.add(i)
}
// 记忆：这个 Agent 自己的长期记忆（只读）
const agentMemory = reactive({ text: '', loading: false, error: null })

async function openAgent(agent) {
  agentDetail.value = { name: agent.name, agent, tab: 'info', loading: true, error: null, node: null }
  fileView.value = null
  resetChat()
  resetAgentMemory()
  try {
    const res = await fetch('/api/v1/workspace/tree')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    const agentsNode = (body.data.children || []).find((c) => c.name === 'agents')
    const node = (agentsNode?.children || []).find((c) => c.name === agent.name) || null
    const outputTree = (body.data.children || []).find((c) => c.name === 'output') || null
    agentDetail.value = { ...agentDetail.value, loading: false, node, outputTree }
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
      const outputTree = (body.data.children || []).find((c) => c.name === 'output') || null
      agentDetail.value = { ...agentDetail.value, node, outputTree }
    }
  } catch (e) {
    /* 文件树刷新失败不阻断，忽略 */
  }
}

function detailTab(tab) {
  if (!agentDetail.value) return
  agentDetail.value = { ...agentDetail.value, tab }
  if (tab === 'files' || tab === 'output') {
    fileView.value = null // 工作区/输出各自从"未选中"开始，避免跨 tab 串台预览
  }
  if (tab === 'chat') {
    loadChat()
  } else if (tab === 'memory') {
    loadMemory()
  } else if (tab === 'executions') {
    loadExecutions()
  }
}

// —— 执行历史 tab：该 Agent 每次触发的起止时间 / 状态 / 时长（手动 + 定时）——
const execHistory = reactive({ loading: false, error: null, data: [] })
async function loadExecutions() {
  execHistory.loading = true; execHistory.error = null
  try {
    const name = agentDetail.value.name
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/executions`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    execHistory.data = body.data || []
  } catch (e) { execHistory.error = e.message } finally { execHistory.loading = false }
}
function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}
function fmtDuration(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return ms + ' ms'
  const s = ms / 1000
  return s < 60 ? s.toFixed(1) + ' s' : Math.floor(s / 60) + ' 分 ' + Math.round(s % 60) + ' 秒'
}
function execStatusLabel(s) {
  return { RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败' }[s] || s
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
  resetAgentMemory()
}

// 工作区文件下载地址（浏览器直连后端附件流，可下任意类型；研报等产出走这里）
function downloadUrl(path) {
  return `/api/v1/workspace/download?path=${encodeURIComponent(path)}`
}

async function openFile(node) {
  if (node.type !== 'file') return
  mdView.value = 'preview' // 每次打开新文件都回到预览（对非 .md 无影响）
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
// 「输出」tab：读共享产出目录 .oryxos/output/（Agent 落盘研报/汇总/导出的地方），扁平成文件行
const outputNode = computed(() => agentDetail.value?.outputTree || null)
const outputRows = computed(() =>
  outputNode.value ? flatten(outputNode.value, 0, []).filter((n) => n.type === 'file') : [],
)
</script>

<template>
  <!-- 012-web-auth US3：未登录先显登录页；检查中显骨架屏（避免突兀的"加载中"文字） -->
  <div v-if="auth.checking" class="boot-splash" aria-busy="true" aria-live="polite">
    <div class="boot-spinner" aria-hidden="true"></div>
  </div>
  <LoginView v-else-if="auth.enabled && !auth.username" @logined="onLogined" />
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

      <div class="auth-foot">
        <span class="mono">{{ auth.username || '认证已关闭' }}</span>
        <button v-if="auth.enabled" class="btn" @click="logout">登出</button>
      </div>
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

          <!-- Skill 列表（第 32 节）：全局 Skill 库 CRUD。Agent 按名引用、运行时注入正文约束产出 -->
          <div v-if="active === 'skills'">
            <template v-if="!skillDetail">
            <div class="toolbar">
              <button class="btn btn-primary" @click="newImport()">从 GitHub 拉取</button>
              <button class="btn btn-primary" @click="newSkill()">+ 新建 Skill</button>
            </div>
            <!-- 从 GitHub 拉取 Skill 弹出框：导入整个目录（SKILL.md + 附带文件），不是抓网页正文 -->
            <div v-if="skillImport.open" class="modal-overlay" @click.self="cancelImport()">
              <div class="modal-card">
                <div class="modal-head">
                  <h3>从 GitHub 拉取 Skill</h3>
                  <button class="modal-x" @click="cancelImport()">✕</button>
                </div>
                <div class="modal-body">
                  <input v-model="skillImport.url" class="gen-input"
                         placeholder="GitHub 目录 URL，如 https://github.com/obra/superpowers/tree/main/skills/brainstorming" />
                  <input v-model="skillImport.name" class="gen-input" placeholder="Skill 名（可选；留空用 SKILL.md 里的 name 或目录名）" />
                  <p class="empty">只支持 GitHub 目录 URL：会递归拉取该目录下全部文件（SKILL.md + 脚本/参考资料等）原样导入，不是抓网页正文。</p>
                  <p v-if="skillImport.error" class="error">{{ skillImport.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelImport">取消</button>
                  <button class="btn btn-primary" :disabled="skillImport.busy || !skillImport.url.trim()" @click="importSkill">拉取</button>
                </div>
              </div>
            </div>
            <!-- 新建 / 编辑 Skill 弹出框 -->
            <div v-if="skillForm.open" class="modal-overlay" @click.self="cancelSkill()">
              <div class="modal-card">
                <div class="modal-head">
                  <h3>{{ skillForm.editing ? '编辑 Skill' : '新建 Skill' }}</h3>
                  <button class="modal-x" @click="cancelSkill()">✕</button>
                </div>
                <div class="modal-body">
                  <input v-model="skillForm.name" class="gen-input" :disabled="!!skillForm.editing"
                         placeholder="Skill 名（字母/数字/下划线/连字符，如 report-format）" />
                  <input v-model="skillForm.description" class="gen-input" placeholder="一句话描述：这个 Skill 约束什么" />
                  <label class="empty" style="display:block;margin:6px 0 2px">正文（约束指令，会注入引用它的 Agent 的 system prompt）</label>
                  <textarea v-model="skillForm.body" class="gen-draft" rows="10"
                            placeholder="例如：产出报告时严格遵守——开头一句总览；正文按重要性排序，每条含标题+点评+来源；事实与推断分开；不编造。"></textarea>
                  <p v-if="skillForm.error" class="error">{{ skillForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelSkill">取消</button>
                  <button class="btn btn-primary" :disabled="skillForm.busy || !skillForm.name" @click="saveSkill">保存</button>
                </div>
              </div>
            </div>
            <p v-if="skills.loading" class="empty">加载中…</p>
            <p v-else-if="skills.error" class="error">出错：{{ skills.error }}</p>
            <table v-else>
              <thead><tr><th>名称</th><th>描述</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!skills.data.length"><td colspan="3" class="empty">（暂无 Skill · 点上面「新建 Skill」）</td></tr>
                <tr v-for="s in skills.data" :key="s.name">
                  <td class="mono">{{ s.name }}</td>
                  <td>{{ s.description || '—' }}</td>
                  <td>
                    <button class="btn" @click="openSkillDetail(s)">详情</button>
                    <button class="btn" @click="editSkill(s)">编辑</button>
                    <button class="btn" @click="deleteSkill(s.name)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
            </template>

            <!-- Skill 详情：文件列表 + 复用同一套文件浏览器（openFile/fileView + md 预览/源码） -->
            <div v-else>
              <button class="btn back" @click="closeSkillDetail">← 返回 Skill 列表</button>
              <div class="sess-meta"><span>Skill</span><span class="mono">{{ skillDetail.name }}</span></div>
              <p class="empty">{{ skillDetail.description || '—' }}</p>
              <p v-if="skillDetail.loading" class="empty">加载中…</p>
              <p v-else-if="skillDetail.error" class="error">出错：{{ skillDetail.error }}</p>
              <!-- 有真实目录子树 → 文件浏览器 -->
              <div v-else-if="skillDetailRows.length" class="ws">
                <div class="ws-tree">
                  <div v-for="(node, i) in skillDetailRows" :key="i"
                       :class="['ws-node', { file: node.type === 'file', on: fileView && fileView.path === node.path }]"
                       :style="{ paddingLeft: (node.depth * 14) + 'px' }"
                       @click="openFile(node)">
                    <span class="mono">{{ node.type === 'dir' ? '📁' : '📄' }} {{ node.name }}</span>
                    <a v-if="node.type === 'file'" class="dl" :href="downloadUrl(node.path)"
                       :download="node.name" @click.stop title="下载">⬇</a>
                  </div>
                </div>
                <div class="ws-file">
                  <p v-if="!fileView" class="empty">点左侧一个文件查看内容</p>
                  <template v-else>
                    <div class="sess-meta"><span class="mono">{{ fileView.path }}</span></div>
                    <p v-if="fileView.loading" class="empty">加载中…</p>
                    <template v-else>
                      <div v-if="fileIsMarkdown" class="md-toggle">
                        <button :class="['md-seg', { on: mdView === 'preview' }]" @click="mdView = 'preview'">预览</button>
                        <button :class="['md-seg', { on: mdView === 'source' }]" @click="mdView = 'source'">源码</button>
                      </div>
                      <div v-if="fileIsMarkdown && mdView === 'preview'" class="md-preview" v-html="renderedMd"></div>
                      <textarea v-else class="mono filetext" v-model="fileView.content"></textarea>
                      <div class="ops" style="margin-top:10px">
                        <a class="btn" :href="downloadUrl(fileView.path)" :download="fileView.path.split('/').pop()">下载</a>
                      </div>
                      <p v-if="fileView.error" class="error">{{ fileView.error }}</p>
                    </template>
                  </template>
                </div>
              </div>
              <!-- 回退：旧后端 tree 无 skills 节点 → 直接渲染 SKILL.md 正文 -->
              <div v-else class="md-preview" v-html="skillDetailBodyMd"></div>
            </div>
          </div>

          <!-- 知识库：占位空列表（待接入知识库端点） -->
          <table v-else-if="active === 'knowledge'">
            <thead><tr><th>名称</th><th>描述</th></tr></thead>
            <tbody><tr><td colspan="2" class="empty">（暂无知识库条目 · 待接入知识库端点）</td></tr></tbody>
          </table>

          <!-- Sandbox 白名单：三类 file/shell/http 的 CRUD（新增走弹框 / 逐行删除） -->
          <div v-else-if="active === 'whitelist'">
            <div class="toolbar">
              <button class="btn btn-primary" @click="wlForm.open = true">+ 新增白名单</button>
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
                  <button class="btn btn-primary" :disabled="wlForm.busy || !wlForm.value.trim()" @click="addWhitelist">新增</button>
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

          <!-- 30 节：Agent —— 一个列表（含新建/删除）；点"详情"进这个 Agent 的文件浏览器 -->
          <div v-else-if="active === 'agents'">
            <!-- 新建视图：独立整页（不是弹框），把「大模型生成」折叠进来 -->
            <div v-if="agentCreate.open">
              <button class="btn back" @click="cancelCreate">← 返回</button>
              <div class="sess-meta"><span>新建 Agent</span></div>
              <div class="gen-box">
                <label class="empty" style="display:block;margin-bottom:2px">Agent 名（字母/数字/下划线/连字符，必填）</label>
                <input v-model="agentCreate.name" class="gen-input" placeholder="例如 pr-digest" />
                <label class="empty" style="display:block;margin:6px 0 2px">描述这个 Agent 要做什么</label>
                <textarea v-model="agentCreate.description" class="gen-draft" rows="4" placeholder="例如：每天早上抓取团队仓库的 PR，汇总成一份摘要推送到群里"></textarea>
                <label class="empty" style="display:block;margin:6px 0 2px">通知渠道（投递目标，由你手动选；不选=本 Agent 不发通知）</label>
                <select v-model="agentCreate.notifyChannel" class="gen-input">
                  <option value="">不通知</option>
                  <option v-for="c in (notifyChannels.data || [])" :key="c.name" :value="c.name">{{ c.name }}（{{ c.type }}）</option>
                </select>
                <label class="empty" style="display:block;margin:6px 0 2px">Skill（约束产出，勾选=生成时必启用；不勾则由大模型按需自动选）</label>
                <div class="skill-picker">
                  <span v-if="!(skills.data || []).length" class="empty">（暂无 Skill · 去「Skill 列表」新建）</span>
                  <label v-for="s in (skills.data || [])" :key="s.name" class="skill-opt" :title="s.description">
                    <input type="checkbox" :value="s.name" v-model="agentCreate.skills" />
                    <span class="mono">{{ s.name }}</span>
                  </label>
                </div>
                <p class="empty">可先「用大模型生成」各文件、编辑后再创建；也可直接「创建」，后台按模板脚手架出完整目录（AGENT.md + scripts/ + skills/ + REFERENCE.md）。</p>
                <div class="ops">
                  <button class="btn" :disabled="agentCreate.busy || !agentCreate.name.trim()" @click="generateFiles">用大模型生成</button>
                  <button class="btn btn-primary" :disabled="agentCreate.busy || !agentCreate.name.trim()" @click="submitCreate">创建</button>
                </div>
                <p v-if="agentCreate.busy" class="empty">处理中…</p>
                <p v-if="agentCreate.error" class="error">{{ agentCreate.error }}</p>
              </div>
              <template v-if="agentCreate.files">
                <div v-for="(content, path) in agentCreate.files" :key="path" class="gen-file">
                  <div class="sess-meta"><span class="mono">{{ path }}</span></div>
                  <textarea class="mono filetext" v-model="agentCreate.files[path]"></textarea>
                </div>
              </template>
            </div>

            <!-- 详情视图：Tab（基本信息 / 文件 / 会话） -->
            <div v-else-if="agentDetail">
              <button class="btn back" @click="closeAgent">← 返回 Agent 列表</button>
              <div class="sess-meta"><span>Agent</span><span class="mono">{{ agentDetail.name }}</span></div>
              <div class="tabs">
                <button :class="['tab', { on: agentDetail.tab === 'info' }]" @click="detailTab('info')">基本信息</button>
                <button :class="['tab', { on: agentDetail.tab === 'files' }]" @click="detailTab('files')">工作区</button>
                <button :class="['tab', { on: agentDetail.tab === 'output' }]" @click="detailTab('output')">输出</button>
                <button :class="['tab', { on: agentDetail.tab === 'chat' }]" @click="detailTab('chat')">会话</button>
                <button :class="['tab', { on: agentDetail.tab === 'executions' }]" @click="detailTab('executions')">执行历史</button>
                <button :class="['tab', { on: agentDetail.tab === 'memory' }]" @click="detailTab('memory')">记忆</button>
              </div>

              <!-- Tab 1：基本信息 -->
              <div v-if="agentDetail.tab === 'info'" class="info-grid">
                <div class="info-row"><span class="k">name</span><span class="mono">{{ agentDetail.agent.name }}</span></div>
                <div class="info-row"><span class="k">description</span><span>{{ agentDetail.agent.description || '—' }}</span></div>
                <div class="info-row"><span class="k">provider</span><span>{{ agentDetail.agent.provider || '—' }}</span></div>
                <div class="info-row"><span class="k">model</span><span>{{ agentDetail.agent.model || '—' }}</span></div>
                <div class="info-row"><span class="k">tools</span><span>{{ (agentDetail.agent.tools || []).join(', ') || '—' }}</span></div>
                <div class="info-row"><span class="k">skills</span><span>{{ (agentDetail.agent.skills || []).join(', ') || '—' }}</span></div>
                <div class="info-row"><span class="k">定时</span><span class="mono">{{ (agentDetail.agent.schedules || []).map((s) => s.cron + ' (' + s.zone + ')').join('；') || '—' }}</span></div>
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
                      <a v-if="node.type === 'file'" class="dl" :href="downloadUrl(node.path)"
                         :download="node.name" @click.stop title="下载">⬇</a>
                    </div>
                  </div>
                  <div class="ws-file">
                    <p v-if="!fileView" class="empty">点左侧一个文件查看/编辑内容</p>
                    <template v-else>
                      <div class="sess-meta"><span class="mono">{{ fileView.path }}</span></div>
                      <p v-if="fileView.loading" class="empty">加载中…</p>
                      <template v-else>
                        <div v-if="fileIsMarkdown" class="md-toggle">
                          <button :class="['md-seg', { on: mdView === 'preview' }]" @click="mdView = 'preview'">预览</button>
                          <button :class="['md-seg', { on: mdView === 'source' }]" @click="mdView = 'source'">源码</button>
                        </div>
                        <div v-if="fileIsMarkdown && mdView === 'preview'" class="md-preview" v-html="renderedMd"></div>
                        <textarea v-else class="mono filetext" v-model="fileView.content"></textarea>
                        <div class="ops" style="margin-top:10px">
                          <button class="btn" :disabled="fileView.saving" @click="saveFile">保存</button>
                          <a class="btn" :href="downloadUrl(fileView.path)" :download="fileView.path.split('/').pop()">下载</a>
                          <span v-if="fileView.saving" class="empty">保存中…</span>
                          <span v-else-if="fileView.saved" class="ok">已保存</span>
                        </div>
                        <p v-if="fileView.error" class="error">{{ fileView.error }}</p>
                      </template>
                    </template>
                  </div>
                </div>
              </div>

              <!-- Tab 3.5：输出 —— 只列该 Agent output/ 目录的产出文件，可预览/下载 -->
              <div v-else-if="agentDetail.tab === 'output'">
                <p v-if="agentDetail.loading" class="empty">加载中…</p>
                <p v-else-if="agentDetail.error" class="error">出错：{{ agentDetail.error }}</p>
                <div v-else class="ws">
                  <div class="ws-tree">
                    <p v-if="!outputRows.length" class="empty">（还没有产出文件。这个 Agent 执行任务后，产出写到 output/ 目录，会出现在这里）</p>
                    <div v-for="(node, i) in outputRows" :key="i"
                         :class="['ws-node', 'file', { on: fileView && fileView.path === node.path }]"
                         @click="openFile(node)">
                      <span class="mono">📄 {{ node.name }}</span>
                      <a class="dl" :href="downloadUrl(node.path)" :download="node.name" @click.stop title="下载">⬇</a>
                    </div>
                  </div>
                  <div class="ws-file">
                    <p v-if="!fileView" class="empty">点左侧一个产出文件预览，或点 ⬇ 直接下载</p>
                    <template v-else>
                      <div class="sess-meta"><span class="mono">{{ fileView.path }}</span></div>
                      <p v-if="fileView.loading" class="empty">加载中…</p>
                      <template v-else>
                        <div v-if="fileIsMarkdown" class="md-toggle">
                          <button :class="['md-seg', { on: mdView === 'preview' }]" @click="mdView = 'preview'">预览</button>
                          <button :class="['md-seg', { on: mdView === 'source' }]" @click="mdView = 'source'">源码</button>
                        </div>
                        <div v-if="fileIsMarkdown && mdView === 'preview'" class="md-preview" v-html="renderedMd"></div>
                        <textarea v-else class="mono filetext" v-model="fileView.content"></textarea>
                        <div class="ops" style="margin-top:10px">
                          <button class="btn" :disabled="fileView.saving" @click="saveFile">保存</button>
                          <a class="btn" :href="downloadUrl(fileView.path)" :download="fileView.path.split('/').pop()">下载</a>
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
                    <div v-for="(t, i) in chatTurns" :key="i" class="turn">
                      <!-- 用户提问 -->
                      <div v-if="t.user" class="msg user">
                        <div class="msg-role">{{ roleLabel('user') }}</div>
                        <pre class="msg-body">{{ t.user.content || '（空）' }}</pre>
                      </div>
                      <!-- 思考 + 工具调用：整轮收拢、默认折叠 -->
                      <div v-if="t.steps.length" class="turn-steps">
                        <button class="steps-toggle" @click="toggleTurn(i)">
                          {{ expandedTurns.has(i) ? '▾' : '▸' }} 思考与工具调用（{{ t.steps.length }} 步）
                        </button>
                        <div v-if="expandedTurns.has(i)" class="steps-body">
                          <div v-for="(m, j) in t.steps" :key="j" :class="['msg', m.role]">
                            <div class="msg-role">{{ roleLabel(m.role) }}<span v-if="m.toolName" class="mono tool-name"> · {{ m.toolName }}</span></div>
                            <pre class="msg-body">{{ m.content || '（空）' }}</pre>
                          </div>
                        </div>
                      </div>
                      <!-- 最终答案：突出显示 -->
                      <div v-if="t.answer" class="msg assistant answer">
                        <div class="msg-role">{{ roleLabel('assistant') }}</div>
                        <pre class="msg-body">{{ t.answer.content || '（空）' }}</pre>
                      </div>
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

              <!-- 执行历史 —— 每次触发的起止时间 / 状态 / 时长（手动 + 定时） -->
              <div v-else-if="agentDetail.tab === 'executions'">
                <div class="toolbar">
                  <button class="btn" @click="loadExecutions()">刷新</button>
                </div>
                <p v-if="execHistory.loading" class="empty">加载中…</p>
                <p v-else-if="execHistory.error" class="error">出错：{{ execHistory.error }}</p>
                <table v-else>
                  <thead><tr><th>状态</th><th>来源</th><th>开始时间</th><th>结束时间</th><th>时长</th><th>错误</th></tr></thead>
                  <tbody>
                    <tr v-if="!execHistory.data.length"><td colspan="6" class="empty">（还没有执行记录 · 点「立即触发」跑一次）</td></tr>
                    <tr v-for="e in execHistory.data" :key="e.id">
                      <td><span :class="['exec-badge', e.status.toLowerCase()]">{{ execStatusLabel(e.status) }}</span></td>
                      <td>{{ e.source === 'schedule' ? '定时' : '手动' }}</td>
                      <td class="mono">{{ fmtTime(e.startedAt) }}</td>
                      <td class="mono">{{ fmtTime(e.endedAt) }}</td>
                      <td class="mono">{{ fmtDuration(e.durationMs) }}</td>
                      <td class="error">{{ e.errorMessage || '' }}</td>
                    </tr>
                  </tbody>
                </table>
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

            <!-- 列表视图：所有 Agent + 新建（点「新建 Agent」进独立整页，含大模型生成） -->
            <template v-else>
              <div class="toolbar">
                <button class="btn btn-primary" @click="openCreate">+ 新建 Agent</button>
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
              <button class="btn btn-primary" @click="nc.open = true">+ 新建渠道</button>
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
                  <button class="btn btn-primary" :disabled="nc.busy || !nc.name || !nc.url" @click="saveNotifyChannel">{{ nc.editing ? '保存修改' : '创建' }}</button>
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
              <button class="btn btn-primary" @click="pv.open = true">+ 新建 Provider</button>
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
                  <button class="btn btn-primary" :disabled="pv.busy || !pv.name" @click="saveProvider">{{ pv.editing ? '保存修改' : '创建' }}</button>
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

          <!-- MCP 管理（31 节）：内置目录一键启用 + 手动增删改，落盘即生效，不用重启 -->
          <div v-else-if="active === 'mcp'">
            <h3 class="sec">内置目录</h3>
            <p v-if="mcpCatalog.loading" class="empty">加载中…</p>
            <p v-else-if="mcpCatalog.error" class="error">出错：{{ mcpCatalog.error }}</p>
            <div v-else class="caps">
              <div v-for="e in mcpCatalog.data" :key="e.id" class="cap">
                <div style="flex:1">
                  <div class="cap-name">{{ e.displayName }}<span class="tag" style="margin-left:8px">{{ e.transport }}</span></div>
                  <div class="cap-desc">{{ e.description }}</div>
                  <div class="ops" style="margin-top:10px">
                    <button class="btn btn-primary" @click="openEnable(e)">一键启用</button>
                    <a v-if="e.docsHint" class="btn" :href="e.docsHint" target="_blank" rel="noopener">文档</a>
                  </div>
                </div>
              </div>
            </div>

            <!-- 一键启用 弹出框：套目录模板 + 填凭证 -->
            <div v-if="mcpEnable.open" class="modal-overlay" @click.self="cancelEnable()">
              <div class="modal-card">
                <div class="modal-head"><h3>启用「{{ mcpEnable.entry?.displayName }}」</h3><button class="modal-x" @click="cancelEnable()">✕</button></div>
                <div class="modal-body">
                  <input v-model="mcpEnable.name" class="gen-input" placeholder="server 名（唯一标识，默认用目录 id）" />
                  <template v-if="Object.keys(mcpEnable.credentials).length">
                    <input v-for="(v, k) in mcpEnable.credentials" :key="k" v-model="mcpEnable.credentials[k]"
                           class="gen-input" :placeholder="k" />
                  </template>
                  <p class="empty">凭证只会写进 .oryxos/mcp_servers.yaml，不会回显明文；留空该项则不设置。</p>
                  <p v-if="mcpEnable.error" class="error">{{ mcpEnable.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelEnable">取消</button>
                  <button class="btn btn-primary" :disabled="mcpEnable.busy || !mcpEnable.name" @click="submitEnable">启用</button>
                </div>
              </div>
            </div>

            <h3 class="sec" style="margin-top:28px">已配置</h3>
            <div class="toolbar">
              <button class="btn btn-primary" @click="mcpForm.open = true">+ 手动添加</button>
            </div>
            <!-- 新建/编辑 MCP server 弹出框 -->
            <div v-if="mcpForm.open" class="modal-overlay" @click.self="cancelMcp()">
              <div class="modal-card">
                <div class="modal-head"><h3>{{ mcpForm.editing ? '编辑 MCP server' : '新建 MCP server' }}</h3><button class="modal-x" @click="cancelMcp()">✕</button></div>
                <div class="modal-body">
                  <input v-model="mcpForm.name" class="gen-input" :disabled="!!mcpForm.editing" placeholder="server 名（唯一标识）" />
                  <select v-model="mcpForm.transport" class="gen-input">
                    <option value="stdio">stdio（本地子进程）</option>
                    <option value="http">http（远程 server）</option>
                  </select>
                  <input v-if="mcpForm.transport === 'stdio'" v-model="mcpForm.command" class="gen-input" placeholder="command，如 npx -y @modelcontextprotocol/server-github" />
                  <input v-else v-model="mcpForm.url" class="gen-input" placeholder="url，如 https://api.githubcopilot.com/mcp/" />
                  <label class="empty" style="display:block">env（每行一条 KEY=VALUE，支持 ${ENV_VAR} 占位）</label>
                  <textarea v-model="mcpForm.envText" class="gen-draft mono" rows="3" placeholder="GITHUB_PERSONAL_ACCESS_TOKEN=${GITHUB_TOKEN}"></textarea>
                  <label class="empty" style="display:block">headers（每行一条 KEY=VALUE；当前 http 传输暂不支持自定义请求头，仅作记录）</label>
                  <textarea v-model="mcpForm.headersText" class="gen-draft mono" rows="2" placeholder="Authorization=Bearer ${TOKEN}"></textarea>
                  <p v-if="mcpForm.error" class="error">{{ mcpForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelMcp">取消</button>
                  <button class="btn btn-primary" :disabled="mcpForm.busy || !mcpForm.name" @click="saveMcp">{{ mcpForm.editing ? '保存修改' : '创建' }}</button>
                </div>
              </div>
            </div>
            <p v-if="mcp.loading" class="empty">加载中…</p>
            <p v-else-if="mcp.error" class="error">出错：{{ mcp.error }}</p>
            <table v-else>
              <thead><tr><th>name</th><th>transport</th><th>command / url</th><th>状态</th><th>工具</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!mcp.data.length"><td colspan="6" class="empty">（暂无 MCP server · 上面选个内置目录一键启用，或点「手动添加」）</td></tr>
                <tr v-for="m in mcp.data" :key="m.name">
                  <td class="mono">{{ m.name }}</td>
                  <td>{{ m.transport }}</td>
                  <td class="mono">{{ m.transport === 'stdio' ? m.command : m.url }}</td>
                  <td>
                    <span v-if="mcpStatusByName[m.name]?.connected" class="ok">已连接</span>
                    <span v-else class="off" :title="mcpStatusByName[m.name]?.error || ''">未连接</span>
                  </td>
                  <td class="mono">{{ (mcpStatusByName[m.name]?.toolNames || []).join(', ') || '—' }}</td>
                  <td class="ops">
                    <button class="btn" @click="editMcp(m)">编辑</button>
                    <button class="btn" @click="deleteMcp(m.name)">删除</button>
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
.auth-foot { margin-top: auto; display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--text-3); font-size: 12px; padding: 8px; }
.auth-foot .mono { color: var(--text-2); }
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
.exec-badge { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; border: 1px solid var(--border); }
.exec-badge.running { color: var(--brand); border-color: var(--brand); }
.exec-badge.success { color: #16a34a; border-color: #16a34a; }
.exec-badge.failed { color: var(--err); border-color: var(--err); }

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
/* 一轮对话分组：用户 + 折叠的过程 + 最终答案，整轮留白 */
.turn { display: flex; flex-direction: column; gap: 8px; }
.turn + .turn { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border); }
.turn-steps { align-self: flex-start; max-width: 90%; }
.steps-toggle { background: none; border: none; color: var(--text-3); font-size: 12px; cursor: pointer; padding: 2px 4px; }
.steps-toggle:hover { color: var(--brand); }
.steps-body { display: flex; flex-direction: column; gap: 8px; margin: 6px 0 0 10px; padding-left: 10px; border-left: 2px solid var(--border); }
.steps-body .msg { max-width: 100%; }
.msg.answer { border-color: var(--brand); }

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
/* 列表页新建按钮工具栏：新建类按钮靠右 */
.toolbar { display: flex; justify-content: flex-end; margin-bottom: 16px; }
.skill-picker { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.skill-opt { display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px; border: 1px solid var(--border); border-radius: 6px; font-size: 12px; cursor: pointer; }
.skill-opt:hover { border-color: var(--brand); }
/* 新增/创建/启用类主操作：橙色高亮，跟其余次要操作（编辑/删除/取消）区分开 */
.btn-primary { background: var(--brand); border-color: var(--brand); color: #fff; font-weight: 500; }
.btn-primary:hover:not(:disabled) { background: var(--brand-2); border-color: var(--brand-2); color: #fff; }
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
.ws-node { padding: 3px 6px; border-radius: 4px; cursor: default; font-size: 12px; display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.ws-node.file { cursor: pointer; }
.ws-node.file:hover { background: var(--bg-mute); }
.ws-node.on { background: var(--brand-soft); color: var(--brand); }
.ws-node .dl { opacity: 0; text-decoration: none; color: var(--text-3); flex-shrink: 0; padding: 0 2px; }
.ws-node.file:hover .dl, .ws-node.on .dl { opacity: 1; }
.ws-node .dl:hover { color: var(--brand); }
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
/* .md 预览：源码/预览切换 + 渲染容器（深色主题友好） */
.md-toggle { display: inline-flex; gap: 0; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; margin-bottom: 10px; }
.md-seg { background: var(--bg-mute); border: none; color: var(--text-2); padding: 4px 14px; font-size: 12px; cursor: pointer; }
.md-seg + .md-seg { border-left: 1px solid var(--border); }
.md-seg:hover { color: var(--text-1); }
.md-seg.on { background: var(--brand-soft); color: var(--brand); }
/* v-html 注入的节点没有 scope 属性，后代选择器一律用 :deep() 命中 */
.md-preview { background: var(--bg-soft); border: 1px solid var(--border); border-radius: 6px; padding: 16px 20px; min-height: 360px; max-height: 70vh; overflow: auto; color: var(--text-1); line-height: 1.7; font-size: 14px; }
.md-preview :deep(> :first-child) { margin-top: 0; }
.md-preview :deep(h1), .md-preview :deep(h2), .md-preview :deep(h3), .md-preview :deep(h4) { color: var(--text-1); font-weight: 600; line-height: 1.3; margin: 1.4em 0 0.6em; }
.md-preview :deep(h1) { font-size: 1.6em; border-bottom: 1px solid var(--border); padding-bottom: 0.3em; }
.md-preview :deep(h2) { font-size: 1.3em; border-bottom: 1px solid var(--border); padding-bottom: 0.25em; }
.md-preview :deep(h3) { font-size: 1.12em; }
.md-preview :deep(p) { margin: 0.7em 0; }
.md-preview :deep(ul), .md-preview :deep(ol) { margin: 0.7em 0; padding-left: 1.6em; }
.md-preview :deep(li) { margin: 0.3em 0; }
.md-preview :deep(a) { color: var(--brand); text-decoration: none; }
.md-preview :deep(a:hover) { text-decoration: underline; }
.md-preview :deep(code) { font-family: var(--font-mono); font-size: 0.88em; background: var(--bg-mute); padding: 1px 5px; border-radius: 4px; color: var(--text-1); }
.md-preview :deep(pre) { background: var(--bg-mute); border: 1px solid var(--border); border-radius: 6px; padding: 12px 14px; overflow-x: auto; margin: 0.9em 0; }
.md-preview :deep(pre code) { background: none; padding: 0; font-size: 12px; line-height: 1.5; }
.md-preview :deep(blockquote) { margin: 0.9em 0; padding: 2px 14px; border-left: 3px solid var(--brand); color: var(--text-2); background: var(--bg-mute); border-radius: 0 6px 6px 0; }
.md-preview :deep(table) { border-collapse: collapse; margin: 0.9em 0; display: block; overflow-x: auto; }
.md-preview :deep(th), .md-preview :deep(td) { border: 1px solid var(--border); padding: 6px 12px; text-align: left; }
.md-preview :deep(th) { background: var(--bg-mute); color: var(--text-2); font-weight: 500; }
.md-preview :deep(hr) { border: none; border-top: 1px solid var(--border); margin: 1.4em 0; }
.md-preview :deep(img) { max-width: 100%; }
.chat-input { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--border); }

@media (max-width: 640px) { .layout { flex-direction: column; } .nav { width: auto; flex-direction: row; flex-wrap: wrap; } .readonly { display: none; } .ws { flex-direction: column; } .ws-tree { width: auto; } }

/* 启动闪屏：黑底居中 spinner，与登录页/管理台同色调，避免突兀文字 */
.boot-splash {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}
.boot-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--border);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: boot-spin 0.7s linear infinite;
}
@keyframes boot-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .boot-spinner { animation: none; } }
</style>
