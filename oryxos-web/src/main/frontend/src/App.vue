<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import logoUrl from './assets/logo.svg'
import LoginView from './views/LoginView.vue'
import { isNearBottom } from './chat-scroll.js'
import { filterSkills, hiddenSelectedCount, selectAllVisible, clearVisible, renderSet } from './skill-filter.js'

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
  // 人格库（025）：copy-in 模板库独立成页，Agent 新建「从人格库导入」只负责选择，人格增删改统一在这里
  { key: 'personas', label: '人格库' },
  { key: 'schedules', label: '定时任务', path: '/api/v2/schedules' },
  // Skill 列表（第 32 节）：全局 Skill 库，自定义加载器（loadSkills）不走通用 path。知识库仍为占位页
  { key: 'skills', label: 'Skill 列表' },
  { key: 'knowledge', label: '知识库' },
  { key: 'report', label: '审计' },
]

const RUNTIME_NAV = [
  { key: 'sessions', label: '会话列表', path: '/api/v1/sessions' },
  { key: 'providers', label: 'Provider 列表' },
  { key: 'mcp', label: 'MCP 管理' },
  { key: 'tools', label: 'Tool 列表', path: '/api/v1/tools' },
  { key: 'notify-channels', label: 'Notify 渠道' },
  { key: 'whitelist', label: 'SandBox 列表' },
  { key: 'tool-policy', label: '工具策略' },
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
loadRuntimeInfo().then(() => loadOverviewStats())

// 概览页数据：动态接入实时端点，每项统计卡独立 loading/error/value 状态
const overviewLoading = ref(false)
const overviewStats = reactive({
  agents: { value: null, loading: true, error: null },
  tools: { value: null, loading: true, error: null, toolNames: [] },
  sessions: { value: null, loading: true, error: null },
  providers: { value: null, loading: true, error: null, providerNames: [] },
})
async function loadOverviewStats() {
  overviewLoading.value = true
  // agents ← GET /profiles
  fetch('/api/v1/profiles')
    .then(res => res.json())
    .then(body => {
      if (body.code !== 0) throw new Error(body.message || '加载失败')
      const data = body.data || []
      overviewStats.agents.value = data.length
      overviewStats.agents.loading = false
    })
    .catch(e => { overviewStats.agents.error = e.message; overviewStats.agents.loading = false })
  // tools ← GET /tools
  fetch('/api/v1/tools')
    .then(res => res.json())
    .then(body => {
      if (body.code !== 0) throw new Error(body.message || '加载失败')
      const data = body.data || []
      overviewStats.tools.value = data.length
      overviewStats.tools.toolNames = data.slice(0, 3).map(t => t.name)
      overviewStats.tools.loading = false
    })
    .catch(e => { overviewStats.tools.error = e.message; overviewStats.tools.loading = false })
  // sessions ← GET /sessions/stats
  fetch('/api/v1/sessions/stats')
    .then(res => res.json())
    .then(body => {
      if (body.code !== 0) throw new Error(body.message || '加载失败')
      overviewStats.sessions.value = body.data?.active ?? 0
      overviewStats.sessions.loading = false
    })
    .catch(e => { overviewStats.sessions.error = e.message; overviewStats.sessions.loading = false })
  // providers ← GET /api/v1/providers（已配置的 Provider，非 Profile 引用到的）
  fetch('/api/v1/providers')
    .then(res => res.json())
    .then(body => {
      if (body.code !== 0) throw new Error(body.message || '加载失败')
      overviewStats.providers.value = (body.data || []).length
      overviewStats.providers.providerNames = (body.data || []).map(p => p.name)
      overviewStats.providers.loading = false
    })
    .catch(e => { overviewStats.providers.error = e.message; overviewStats.providers.loading = false })
  overviewLoading.value = false
}
// 衍生为模板用的 cards 数组
const overviewCards = computed(() => [
  { label: 'Agent', value: overviewStats.agents.value, loading: overviewStats.agents.loading, error: overviewStats.agents.error, hint: '已配置的 Profile' },
  { label: '内置 Tool', value: overviewStats.tools.value, loading: overviewStats.tools.loading, error: overviewStats.tools.error, hint: overviewStats.tools.toolNames.length ? overviewStats.tools.toolNames.join(' / ') + ' …' : '文件 / Shell / HTTP / 记忆 …' },
  { label: '活跃会话', value: overviewStats.sessions.value, loading: overviewStats.sessions.loading, error: overviewStats.sessions.error, hint: '当前活跃' },
  { label: 'Provider', value: overviewStats.providers.value, loading: overviewStats.providers.loading, error: overviewStats.providers.error, hint: overviewStats.providers.value != null && overviewStats.providers.value > 0 ? '已连通' : '—' },
])
const overview = {
  tagline: '装在你自己基础设施上的分布式 AI Agent 操作系统 —— 统一底座运行多个业务 Agent',
  status: '运行中',
  version: 'v0.1.4 · RELEASE',
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
    return ['name', 'profileName', 'key', 'cron', 'zone', 'enabled', 'runCount', 'lastStatus', 'lastRunAt']
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
  if (key === 'personas') { cancelPersonaForm(); loadPersonaPresets() }
  if (key === 'notify-channels') { cancelNc(); loadNotifyChannels() }
  if (key === 'providers') { cancelPv(); loadProviders() }
  if (key === 'whitelist') { cancelWl(); loadWhitelist() }
  if (key === 'tool-policy') { cancelTp(); loadToolPolicy() }
  if (key === 'mcp') { cancelMcp(); loadMcp(); loadMcpCatalog() }
  if (key === 'skills') { cancelSkill(); closeSkillDetail(); loadSkills() }
  if (key === 'knowledge') { cancelKb(); closeKbDetail(); loadKnowledge() }
  if (key === 'overview') { loadOverviewStats() }
  if (key === 'report') { loadReport() }
}

// 刷新当前页的列表：各页复用各自的加载函数（agents / notify-channels / 概览 / 其余按 path 的通用列表）
function refresh() {
  const key = active.value
  if (key === 'agents') { loadAgents(); return }
  if (key === 'personas') { loadPersonaPresets(); return }
  if (key === 'notify-channels') { loadNotifyChannels(); return }
  if (key === 'providers') { loadProviders(); return }
  if (key === 'whitelist') { loadWhitelist(); return }
  if (key === 'mcp') { loadMcp(); return }
  if (key === 'skills') { loadSkills(); return }
  if (key === 'knowledge') { kbDetail.value ? refreshKbDetail(kbDetail.value.name) : loadKnowledge(); return }
  if (key === 'overview') { loadOverviewStats(); return }
  if (key === 'report') { loadReport(); return }
  if (NAV.find((n) => n.key === key)?.path) load(key)
}

// —— 知识库（014）：列表/详情/创建/上传/重建/删除；管理操作按后端能力集渲染（FR-009）——
const kb = ref({ loading: false, error: null, data: [] })
const kbDetail = ref(null) // { name, base, documents, loading, error, busy }
const kbForm = reactive({ open: false, name: '', description: '', busy: false, error: '' })
async function loadKnowledge() {
  kb.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/knowledge')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    kb.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { kb.value = { loading: false, error: e.message, data: [] } }
}
function cancelKb() { kbForm.open = false; kbForm.name = ''; kbForm.description = ''; kbForm.busy = false; kbForm.error = '' }
function closeKbDetail() { kbDetail.value = null }
async function refreshKbDetail(name) {
  kbDetail.value = { ...(kbDetail.value || { name }), name, loading: true, error: null, busy: false }
  try {
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    kbDetail.value = { name, base: body.data.base, documents: body.data.documents || [], loading: false, error: null, busy: false }
    loadKbMetrics(kbMetrics.range)
  } catch (e) { kbDetail.value = { name, base: null, documents: [], loading: false, error: e.message, busy: false } }
}
async function createKb() {
  kbForm.busy = true; kbForm.error = ''
  try {
    const res = await fetch('/api/v1/knowledge', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: kbForm.name.trim(), description: kbForm.description.trim() }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '创建失败')
    cancelKb(); await loadKnowledge()
  } catch (e) { kbForm.error = e.message } finally { kbForm.busy = false }
}
async function deleteKb(name) {
  if (!confirm(`删除知识库「${name}」？（目录与索引一并删除）`)) return
  try {
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) {
      // 409：被 Agent 引用——点名引用方（FR-011）
      const refs = (body.data?.references || []).map((r) => r.agentName).join('、')
      throw new Error(refs ? `${body.message}（引用方：${refs}）` : (body.message || '删除失败'))
    }
    if (kbDetail.value?.name === name) closeKbDetail()
    await loadKnowledge()
  } catch (e) { kb.value = { ...kb.value, error: e.message } }
}
async function uploadKbDoc(event) {
  const file = event.target.files?.[0]
  event.target.value = '' // 允许重复选同一文件
  if (!file || !kbDetail.value) return
  const name = kbDetail.value.name
  kbDetail.value = { ...kbDetail.value, busy: true, error: null }
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}/documents`, { method: 'POST', body: form })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '上传失败')
    await refreshKbDetail(name)
  } catch (e) { kbDetail.value = { ...kbDetail.value, busy: false, error: e.message } }
}
async function reindexKb() {
  if (!kbDetail.value) return
  const name = kbDetail.value.name
  kbDetail.value = { ...kbDetail.value, busy: true, error: null }
  try {
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}/reindex`, { method: 'POST' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '重建失败')
    await refreshKbDetail(name)
  } catch (e) { kbDetail.value = { ...kbDetail.value, busy: false, error: e.message } }
}
// —— 使用看板（FR-023）：只消费审计聚合；时间窗三档 ——
const kbMetrics = reactive({ range: '7d', loading: false, error: null, data: null })
function metricsFrom(range) {
  const now = Date.now()
  if (range === '7d') return new Date(now - 7 * 86400e3).toISOString()
  if (range === '30d') return new Date(now - 30 * 86400e3).toISOString()
  return new Date(0).toISOString()
}
async function loadKbMetrics(range) {
  if (!kbDetail.value) return
  kbMetrics.range = range || kbMetrics.range
  kbMetrics.loading = true; kbMetrics.error = null
  try {
    const name = kbDetail.value.name
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}/metrics?from=${encodeURIComponent(metricsFrom(kbMetrics.range))}&to=${encodeURIComponent(new Date().toISOString())}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    kbMetrics.data = body.data
  } catch (e) { kbMetrics.error = e.message } finally { kbMetrics.loading = false }
}
function fmtRate(rate) { return rate == null ? '—' : (rate * 100).toFixed(2) + '%' }

// —— 报表（016 审计看板）：只读审计聚合，KPI + 分布 + 明细下钻；时间窗三档 ——
const report = reactive({ range: '7d', loading: false, error: null, llm: null, tool: null, byModel: [], byTool: [], byAgent: [], llmList: [], toolList: [] })
function reportFrom(range) {
  const now = Date.now()
  if (range === '7d') return new Date(now - 7 * 86400e3).toISOString()
  if (range === '30d') return new Date(now - 30 * 86400e3).toISOString()
  return new Date(0).toISOString()
}
async function loadReport(range) {
  report.range = range || report.range
  report.loading = true; report.error = null
  const q = `from=${encodeURIComponent(reportFrom(report.range))}&to=${encodeURIComponent(new Date().toISOString())}`
  try {
    const [llm, tool, byModel, byTool, byAgent, llmList, toolList] = await Promise.all([
      fetch(`/api/v1/audit/llm/summary?${q}`).then((r) => r.json()),
      fetch(`/api/v1/audit/tool/summary?${q}`).then((r) => r.json()),
      fetch(`/api/v1/audit/llm/by-model?${q}`).then((r) => r.json()),
      fetch(`/api/v1/audit/tool/by-name?${q}`).then((r) => r.json()),
      fetch(`/api/v1/audit/by-agent?${q}`).then((r) => r.json()),
      fetch(`/api/v1/audit/llm?${q}&limit=100`).then((r) => r.json()),
      fetch(`/api/v1/audit/tool?${q}&limit=100`).then((r) => r.json()),
    ])
    for (const b of [llm, tool, byModel, byTool, byAgent, llmList, toolList]) {
      if (b.code !== 0) throw new Error(b.message || '加载失败')
    }
    report.llm = llm.data
    report.tool = tool.data
    report.byModel = byModel.data || []
    report.byTool = byTool.data || []
    report.byAgent = byAgent.data || []
    report.llmList = llmList.data || []
    report.toolList = toolList.data || []
  } catch (e) { report.error = e.message } finally { report.loading = false }
}
function fmtCost(micros) { return micros == null ? '—' : '¥' + (micros / 1e6).toFixed(4) }
function barWidth(list, count) { return ((count / Math.max(1, ...list.map((x) => x.count))) * 100) + '%' }
// 下钻过滤：点击分布项 → 过滤明细表（模型/工具/Agent 三维度）；点同一项再点一次 = 清除
const reportFilter = ref(null) // { type: 'model' | 'tool' | 'agent', key }
function setReportFilter(type, key) {
  const next = reportFilter.value?.type === type && reportFilter.value?.key === key ? null : { type, key }
  reportFilter.value = next
  llmPage.page = 1
  toolPage.page = 1
  if (!next) return
  // 点击分布项 → 自动展开对应明细表
  if (type === 'model') reportExpand.llm = true
  if (type === 'tool') reportExpand.tool = true
  if (type === 'agent') { reportExpand.llm = true; reportExpand.tool = true }
}
function clearReportFilter() {
  reportFilter.value = null
  llmPage.page = 1
  toolPage.page = 1
}
// 明细表折叠：默认折叠，点击标题展开
const reportExpand = reactive({ llm: false, tool: false })
// —— Trace 时间线（021）：按 trace ID 回放单轮全链路（LLM+工具合并时间序 + 成本/耗时汇总）；
// 明细行与执行历史的 traceId 可点击填入查询框。摘要为服务端截断+脱敏后的展示值。 ——
const trace = reactive({ id: '', loading: false, error: null, result: null })
async function loadTrace(id) {
  const q = (id ?? trace.id ?? '').trim()
  if (!q) return
  trace.id = q
  trace.loading = true
  trace.error = null
  trace.result = null
  try {
    const res = await fetch(`/api/v1/audit/trace/${encodeURIComponent(q)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '查询失败')
    trace.result = body.data
  } catch (e) { trace.error = e.message } finally { trace.loading = false }
}
const filteredLlmList = computed(() => {
  if (!reportFilter.value) return report.llmList
  const { type, key } = reportFilter.value
  if (type === 'model') return report.llmList.filter((c) => c.model === key)
  if (type === 'agent') return report.llmList.filter((c) => (c.profileName || '(未归属)') === key)
  return report.llmList
})
const filteredToolList = computed(() => {
  if (!reportFilter.value) return report.toolList
  const { type, key } = reportFilter.value
  if (type === 'tool') return report.toolList.filter((t) => t.toolName === key)
  if (type === 'agent') return report.toolList.filter((t) => (t.profileName || '(未归属)') === key)
  return report.toolList
})
// 明细分页：每页默认 10 条，可改每页大小
const llmPage = reactive({ page: 1, size: 10 })
const toolPage = reactive({ page: 1, size: 10 })
const totalLlmPages = computed(() => Math.max(1, Math.ceil(filteredLlmList.value.length / llmPage.size)))
const totalToolPages = computed(() => Math.max(1, Math.ceil(filteredToolList.value.length / toolPage.size)))
const pagedLlmList = computed(() => {
  const start = (llmPage.page - 1) * llmPage.size
  return filteredLlmList.value.slice(start, start + llmPage.size)
})
const pagedToolList = computed(() => {
  const start = (toolPage.page - 1) * toolPage.size
  return filteredToolList.value.slice(start, start + toolPage.size)
})

async function deleteKbDoc(relPath) {
  if (!kbDetail.value) return
  if (!confirm(`删除文档「${relPath}」？（源文件与索引片段一并删除）`)) return
  const name = kbDetail.value.name
  try {
    const res = await fetch(`/api/v1/knowledge/${encodeURIComponent(name)}/documents?path=${encodeURIComponent(relPath)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await refreshKbDetail(name)
  } catch (e) { kbDetail.value = { ...kbDetail.value, error: e.message } }
}

// —— Skill CRUD：.oryxos/skills/<name>/ 存在即已安装，Agent 通过本地相对软连接绑定。——
const skills = ref({ loading: false, error: null, data: [] })
// 028-agent-skill-filter：Skill 选择器共享筛选态（新建页与详情编辑页共用；视图互斥故单实例不互染）。
// 选择集（agentCreate.skills / agentBinding.selected）与显示集（filterSkills 输出）解耦——筛选只影响显示。
const skillFilter = reactive({ query: '', showHidden: false })
// 新建页筛选视野与渲染集（computed：随 skills.data / skillFilter.query / agentCreate.skills 变化刷新）
const createSkillVisible = computed(() => filterSkills(skills.value.data, skillFilter.query))
const createSkillRender = computed(() => renderSet(createSkillVisible.value, skills.value.data, agentCreate.skills, skillFilter.showHidden))
const createSkillHiddenCount = computed(() => hiddenSelectedCount(createSkillVisible.value, agentCreate.skills))
// 详情编辑页筛选视野与渲染集（同型，绑 agentBinding.selected）
const editSkillVisible = computed(() => filterSkills(skills.value.data, skillFilter.query))
const editSkillRender = computed(() => renderSet(editSkillVisible.value, skills.value.data, agentBinding.selected, skillFilter.showHidden))
const editSkillHiddenCount = computed(() => hiddenSelectedCount(editSkillVisible.value, agentBinding.selected))
// 绑定一致性不在页面常驻展示：只在 Skill 变更（新建/编辑/归档/导入）后回检，
// 发现残留或损坏绑定才展开告警面板，无问题保持静默；检查本身失败也会展开。
const skillIssues = ref({ loading: false, error: null, data: [] })
const skillIssuesOpen = ref(false)
async function checkSkillIssues() {
  skillIssues.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/skills/binding-issues')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '绑定检查失败')
    skillIssues.value = { loading: false, error: null, data: body.data || [] }
    skillIssuesOpen.value = skillIssues.value.data.length > 0
  } catch (e) {
    skillIssues.value = { loading: false, error: e.message, data: [] }
    skillIssuesOpen.value = true
  }
}
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
    cancelSkill(); await loadSkills(); await checkSkillIssues()
  } catch (e) { skillForm.error = e.message } finally { skillForm.busy = false }
}
async function deleteSkill(name) {
  if (!confirm(`归档 Skill「${name}」？存在活跃或归档 Agent 引用时会拒绝，实体不会被物理删除。`)) return
  try {
    const res = await fetch(`/api/v1/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) {
      const refs = (body.data?.references || []).map((r) => `${r.agentName}(${r.state})`).join('、')
      throw new Error(`${body.message || '归档失败'}${refs ? `：${refs}` : ''}`)
    }
    await loadSkills(); await checkSkillIssues()
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
    cancelImport(); await loadSkills(); await checkSkillIssues()
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
// 回退：工作区树暂不可用时，直接渲染已安装实体返回的 SKILL.md 正文。
const skillDetailBodyMd = computed(() =>
  skillDetail.value?.body ? DOMPurify.sanitize(marked.parse(skillDetail.value.body)) : ''
)

// —— 会话详情：点一行会话，拉 GET /sessions/{id} 看完整对话内容 ——
const sessionDetail = ref(null) // {loading, error, id, data:{sessionId, profileName, messages[]}}
const sessionDetailScrollEl = ref(null)

function scrollSessionDetailToBottom() {
  nextTick(() => {
    const el = sessionDetailScrollEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function openSession(id) {
  sessionDetail.value = { loading: true, error: null, id, data: null }
  try {
    const res = await fetch(`/api/v1/sessions/${encodeURIComponent(id)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    sessionDetail.value = { loading: false, error: null, id, data: body.data }
    scrollSessionDetailToBottom()
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
const busy = ref(null) // 正在操作的 scheduleId，防重复点击

// 立即执行一次（POST /schedules/{id}/run），跑完刷新列表
async function runTask(scheduleId) {
  busy.value = scheduleId
  try {
    const res = await fetch(`/api/v2/schedules/${encodeURIComponent(scheduleId)}/run`, { method: 'POST' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '执行失败')
    await load('schedules')
    // 若正打开着这个任务的执行记录，跑完顺手刷新
    if (execDetail.value?.scheduleId === scheduleId) await openExecutions(scheduleId)
  } catch (e) {
    state.schedules = { ...state.schedules, error: e.message }
  } finally {
    busy.value = null
  }
}

// 执行记录历史：点"执行记录"拉 GET /schedules/{id}/executions
const execDetail = ref(null) // {loading, error, scheduleId, data:[{startedAt,success,durationMs,errorMessage,sessionId}]}

async function openExecutions(scheduleId) {
  execDetail.value = { loading: true, error: null, scheduleId, data: null }
  try {
    const res = await fetch(`/api/v2/schedules/${encodeURIComponent(scheduleId)}/executions`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    execDetail.value = { loading: false, error: null, scheduleId, data: body.data }
  } catch (e) {
    execDetail.value = { loading: false, error: e.message, scheduleId, data: null }
  }
}

function closeExecutions() {
  execDetail.value = null
}

// 启用/停用（PUT /schedules/{id}），切换后刷新列表
async function toggleTask(row) {
  busy.value = row.scheduleId
  try {
    const res = await fetch(`/api/v2/schedules/${encodeURIComponent(row.scheduleId)}`, {
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
const agentCreate = reactive({
  open: false, name: '', description: '', provider: '', model: '', notifyChannel: '', skills: [],
  requiredSkills: [], suggestedSkills: [], knowledge: [], suggestedKnowledge: [],
  files: null, busy: false, error: '',
})

// 新建页用的 provider / model 下拉数据源：provider 来自 GET /providers；model 来自 GET /providers/{name}/models（服务端代理）
const createProviders = ref({ loading: false, error: null, data: [] })
const createModels = ref({ loading: false, error: null, data: [] })
async function loadCreateProviders() {
  createProviders.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/providers')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    createProviders.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { createProviders.value = { loading: false, error: e.message, data: [] } }
}
async function loadCreateModels(name) {
  if (!name) { createModels.value = { loading: false, error: null, data: [] }; return }
  createModels.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch(`/api/v1/providers/${encodeURIComponent(name)}/models`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    createModels.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { createModels.value = { loading: false, error: e.message, data: [] } }
}
function onProviderChange() { agentCreate.model = ''; loadCreateModels(agentCreate.provider) }

// 打开新建页：重置字段 + 拉通知渠道下拉数据
function openCreate() {
  agentCreate.open = true
  agentCreate.name = ''
  agentCreate.description = ''
  agentCreate.provider = ''
  agentCreate.model = ''
  agentCreate.notifyChannel = ''
  agentCreate.skills = []
  agentCreate.requiredSkills = []
  agentCreate.suggestedSkills = []
  agentCreate.knowledge = []
  agentCreate.suggestedKnowledge = []
  agentCreate.files = null
  agentCreate.busy = false
  agentCreate.error = ''
  skillFilter.query = ''; skillFilter.showHidden = false // 进入新建页清空筛选态
  loadNotifyChannels()
  loadSkills() // Skill 选择器的数据源（可手动指定必启用的 Skill；不选则由作者模型自动选）
  loadKnowledge() // 知识库多选的数据源（FR-018 关联入口之一）
  loadCreateProviders() // provider 下拉数据源
}

function cancelCreate() { agentCreate.open = false }

// 用大模型按描述生成各文件内容（需先填 name），生成后可逐个编辑
async function generateFiles() {
  if (!agentCreate.name.trim()) { agentCreate.error = '请先填写 Agent 名'; return }
  agentCreate.busy = true; agentCreate.error = ''
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentCreate.name)}/generate-files`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: agentCreate.description, notifyChannel: agentCreate.notifyChannel, requiredSkills: agentCreate.skills, provider: agentCreate.provider || undefined, model: agentCreate.model || undefined }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '生成失败')
    agentCreate.files = body.data.files || {}
    agentCreate.requiredSkills = body.data.requiredSkills || []
    agentCreate.suggestedSkills = body.data.suggestedSkills || []
    agentCreate.skills = body.data.bindingSkills || []
    // 一句话生成的知识库绑定建议（FR-018）：合并进选择器，作者确认后随创建生效
    agentCreate.suggestedKnowledge = body.data.bindingKnowledge || []
    agentCreate.knowledge = Array.from(new Set([...agentCreate.knowledge, ...agentCreate.suggestedKnowledge]))
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
          body: JSON.stringify({ files: agentCreate.files, skillBindings: agentCreate.skills, knowledgeBindings: agentCreate.knowledge }),
        })
        : await fetch('/api/v1/agents', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: agentCreate.name, description: agentCreate.description, provider: agentCreate.provider || undefined, model: agentCreate.model || undefined, skillBindings: agentCreate.skills, knowledgeBindings: agentCreate.knowledge }),
        })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '创建失败')
    agentCreate.open = false
    await loadAgents()
  } catch (e) { agentCreate.error = e.message } finally { agentCreate.busy = false }
}

// —— 从人格库导入（025 Web 导入）：Agent 新建页「从人格库导入」入口（12 内置 + 自定义）——
// 与「一句话生成」同一页，顶部切换模式；两条路都收敛到 import-preview → import → saveFiles 校验链。
// 人格的新建/编辑/删除统一在左侧「人格库」页操作；这里只负责选中某个人格、上传/粘贴 .md、预览、落盘。
const createMode = ref('llm') // 新建页模式：'llm' 一句话生成 / 'import' 人格库导入
const personaPresets = ref({ loading: false, error: null, data: [] })
const personaPageError = ref('') // 人格库页的页面级错误（删除失败等，非弹框内）
const agentImport = reactive({
  sourceContent: '', name: '', selected: '', provider: '', model: '', preview: null, busy: false, error: '',
})
function onImportProviderChange() { agentImport.model = ''; loadCreateModels(agentImport.provider) }
function setCreateMode(m) {
  createMode.value = m
  if (m === 'import') {
    if (!personaPresets.value.data.length && !personaPresets.value.loading) loadPersonaPresets()
    if (!createProviders.value.data.length && !createProviders.value.loading) loadCreateProviders()
  }
}
async function loadPersonaPresets() {
  personaPresets.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/personas')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    personaPresets.value = { loading: false, error: null, data: body.data || [] }
    personaPageError.value = ''
  } catch (e) { personaPresets.value = { loading: false, error: e.message, data: [] } }
}
// 选中人格：拉源文件全文作导入草稿，Agent 名建议用 key（中文 displayName 派生不出合法 slug）。写/改人格去「人格库」页
async function pickPreset(p) {
  agentImport.selected = p.key
  agentImport.name = p.key
  agentImport.preview = null
  agentImport.error = ''
  agentImport.busy = true
  try {
    const res = await fetch(`/api/v1/personas/${encodeURIComponent(p.key)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    agentImport.sourceContent = body.data.sourceContent
  } catch (e) { agentImport.error = e.message } finally { agentImport.busy = false }
}
// 上传 .md 文件：读文本作导入草稿，Agent 名从文件名推合法 slug
function onImportFile(evt) {
  const file = evt.target && evt.target.files && evt.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    agentImport.sourceContent = String(reader.result || '')
    agentImport.selected = ''
    agentImport.preview = null
    const base = (file.name || '').replace(/\.md$/i, '').replace(/[^A-Za-z0-9_-]/g, '')
    agentImport.name = base || agentImport.name
  }
  reader.readAsText(file)
}
// 预览：不落盘，返回渲染出的 AGENT.md + 人格字段投影（确认前可改 Agent 名 / 源文件内容再重预览）
async function previewImport() {
  if (!agentImport.sourceContent.trim()) { agentImport.error = '请先选择预设或粘贴源文件内容'; return }
  agentImport.busy = true; agentImport.error = ''
  try {
    const res = await fetch('/api/v1/agents/import-preview', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceContent: agentImport.sourceContent, name: agentImport.name || undefined,
        provider: agentImport.provider || undefined, model: agentImport.model || undefined,
      }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '预览失败')
    agentImport.preview = body.data
    if (body.data.name) agentImport.name = body.data.name
  } catch (e) { agentImport.error = e.message } finally { agentImport.busy = false }
}
// 确认导入：落盘并注册（importAgent 走 saveFiles 校验链），成功后直接进详情
async function submitImport() {
  if (!agentImport.sourceContent.trim()) return
  if (!agentImport.name.trim()) { agentImport.error = '请填写 Agent 名'; return }
  agentImport.busy = true; agentImport.error = ''
  try {
    const res = await fetch('/api/v1/agents/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceContent: agentImport.sourceContent, name: agentImport.name,
        provider: agentImport.provider || undefined, model: agentImport.model || undefined,
      }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '导入失败')
    const imported = body.data
    cancelCreate()
    await loadAgents()
    openAgent(imported)
  } catch (e) { agentImport.error = e.message } finally { agentImport.busy = false }
}

// —— 人格库页（copy-in 模板库）管理：新建/编辑/查看共用一个弹框，源文件原文即库内容 ——
const personaForm = reactive({ open: false, editing: false, viewOnly: false, key: '', sourceContent: '', busy: false, error: '' })
function openPersonaEditor() {
  personaForm.open = true; personaForm.editing = false; personaForm.viewOnly = false
  personaForm.key = ''; personaForm.sourceContent = ''; personaForm.error = ''; personaForm.busy = false
}
function cancelPersonaForm() { personaForm.open = false }
// 从本地 .md 文件导入人格源文件草稿：内容读入下方文本框；新建且 key 为空时从文件名派生合法 slug（可改）
function onPersonaFile(evt) {
  const file = evt.target && evt.target.files && evt.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    personaForm.sourceContent = String(reader.result || '')
    if (!personaForm.editing && !personaForm.key.trim()) {
      personaForm.key = (file.name || '').replace(/\.md$/i, '').replace(/[^A-Za-z0-9_-]/g, '')
    }
    personaForm.error = ''
  }
  reader.readAsText(file)
  evt.target.value = '' // 重置 input，允许连续选同一个文件
}
// 编辑/查看先拉详情（源全文），就绪再开弹框，避免闪现空文本框
async function editPersona(p) {
  personaForm.busy = true; personaForm.error = ''
  try {
    const res = await fetch(`/api/v1/personas/${encodeURIComponent(p.key)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    personaForm.open = true; personaForm.editing = true; personaForm.viewOnly = false
    personaForm.key = p.key; personaForm.sourceContent = body.data.sourceContent; personaForm.busy = false
  } catch (e) { personaPageError.value = e.message; personaForm.busy = false }
}
async function viewPersona(p) {
  personaForm.busy = true; personaForm.error = ''
  try {
    const res = await fetch(`/api/v1/personas/${encodeURIComponent(p.key)}`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    personaForm.open = true; personaForm.editing = false; personaForm.viewOnly = true
    personaForm.key = p.key; personaForm.sourceContent = body.data.sourceContent; personaForm.busy = false
  } catch (e) { personaPageError.value = e.message; personaForm.busy = false }
}
// 保存：编辑走 PUT（key 不可改），新建走 POST；内置 key 后端 400 只读
async function savePersonaForm() {
  const key = (personaForm.key || '').trim()
  if (!key) { personaForm.error = '请填写 key'; return }
  if (!personaForm.sourceContent.trim()) { personaForm.error = '人格内容不能为空'; return }
  personaForm.busy = true; personaForm.error = ''
  try {
    const res = await fetch(personaForm.editing ? `/api/v1/personas/${encodeURIComponent(key)}` : '/api/v1/personas', {
      method: personaForm.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key, sourceContent: personaForm.sourceContent }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    personaForm.open = false
    await loadPersonaPresets()
  } catch (e) { personaForm.error = e.message } finally { personaForm.busy = false }
}
// 删除自定义人格（物理删，不可撤销）：内置只读（后端 400）；删的是导入页当前选中项时清空选中
async function deletePersona(p) {
  if (p.builtin) { personaPageError.value = '内置人格只读，不能删除'; return }
  if (!window.confirm(`删除自定义人格「${p.label}」？此操作不可撤销。`)) return
  personaPageError.value = ''
  try {
    const res = await fetch(`/api/v1/personas/${encodeURIComponent(p.key)}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    if (agentImport.selected === p.key) { agentImport.selected = '' }
    await loadPersonaPresets()
  } catch (e) { personaPageError.value = e.message }
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

// —— Notify 渠道管理（CRUD /api/v1/notify-channels）：命名的通知出口，type ∈ feishu/wecom/dingtalk/webhook/email ——
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
const nc = reactive({ open: false, editing: null, name: '', type: 'feishu', url: '', description: '', host: '', port: '', from: '', to: '', username: '', password: '', subject: '', encryption: '', busy: false, error: null })

async function saveNotifyChannel() {
  nc.busy = true; nc.error = null
  try {
    const url = nc.editing
      ? `/api/v1/notify-channels/${encodeURIComponent(nc.editing)}`
      : '/api/v1/notify-channels'
    const config = nc.type === 'email' ? buildEmailConfig() : undefined
    let payload = nc.type === 'email'
      ? { type: nc.type, url: '', config, description: nc.description }
      : { type: nc.type, url: nc.url, description: nc.description }
    if (!nc.editing) payload = { name: nc.name, ...payload }
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
  const c = row.config || {}
  nc.host = c.host || ''; nc.port = c.port || ''; nc.from = c.from || ''; nc.to = c.to || ''
  nc.username = c.username || ''; nc.password = c.password || ''; nc.subject = c.subject || ''; nc.encryption = c.encryption || ''
  nc.error = null
  nc.open = true
}

function buildEmailConfig() {
  const config = {}
  for (const k of ['host', 'port', 'from', 'to', 'username', 'password', 'subject', 'encryption']) {
    if (nc[k]) config[k] = nc[k]
  }
  return config
}

function cancelNc() {
  nc.open = false; nc.editing = null; nc.name = ''; nc.type = 'feishu'; nc.url = ''; nc.description = ''
  nc.host = ''; nc.port = ''; nc.from = ''; nc.to = ''; nc.username = ''; nc.password = ''; nc.subject = ''; nc.encryption = ''
  nc.error = null
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
const providerTests = ref({})
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
  loadPricing()
}

async function testProvider(name) {
  providerTests.value = {
    ...providerTests.value,
    [name]: { loading: true, ok: null, message: '测试中…' },
  }
  try {
    const res = await fetch(`/api/v1/providers/${encodeURIComponent(name)}/test`, { method: 'POST' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '连通测试失败')
    const data = body.data || {}
    const samples = (data.sampleModels || []).slice(0, 3).join('、')
    providerTests.value = {
      ...providerTests.value,
      [name]: {
        loading: false,
        ok: true,
        message: samples ? `可用 · ${data.modelCount || 0} 个模型 · ${samples}` : `可用 · ${data.modelCount || 0} 个模型`,
      },
    }
  } catch (e) {
    providerTests.value = {
      ...providerTests.value,
      [name]: { loading: false, ok: false, message: e.message || '连通测试失败' },
    }
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

// —— 模型定价（016 审计看板）：(provider, model) → 输入/输出 token 单价（元/百万 token）——
const pricing = ref({ loading: false, error: null, data: [] })
const pricingForm = reactive({ open: false, editing: null, provider: '', model: '', promptPrice: '', completionPrice: '', busy: false, error: null })
async function loadPricing() {
  pricing.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch('/api/v1/pricing')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    pricing.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { pricing.value = { loading: false, error: e.message, data: [] } }
}
function openPricingForm(row) {
  pricingForm.editing = row?.id ?? null
  pricingForm.provider = row?.provider ?? ''
  pricingForm.model = row?.model ?? ''
  pricingForm.promptPrice = row?.promptPrice ?? ''
  pricingForm.completionPrice = row?.completionPrice ?? ''
  pricingForm.error = null
  pricingForm.open = true
}
function cancelPricing() {
  pricingForm.open = false; pricingForm.editing = null; pricingForm.provider = ''; pricingForm.model = ''; pricingForm.promptPrice = ''; pricingForm.completionPrice = ''; pricingForm.error = null
}
function parsePrice(v) { return v === '' || v == null ? null : Number(v) }
async function savePricing() {
  pricingForm.busy = true; pricingForm.error = null
  try {
    const prices = { promptPrice: parsePrice(pricingForm.promptPrice), completionPrice: parsePrice(pricingForm.completionPrice) }
    const payload = pricingForm.editing
      ? prices
      : { provider: pricingForm.provider.trim(), model: pricingForm.model.trim(), ...prices }
    const url = pricingForm.editing ? `/api/v1/pricing/${pricingForm.editing}` : '/api/v1/pricing'
    const res = await fetch(url, {
      method: pricingForm.editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    cancelPricing(); await loadPricing()
  } catch (e) { pricingForm.error = e.message } finally { pricingForm.busy = false }
}
async function deletePricing(id) {
  if (!confirm('删除这条模型定价？')) return
  try {
    const res = await fetch(`/api/v1/pricing/${id}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadPricing()
  } catch (e) { pricing.value = { ...pricing.value, error: e.message } }
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

// —— Sandbox 白名单管理（CRUD /api/v1/sandbox/whitelist）：四类 file/shell/http/smtp 的白名单条目 ——
const WL_CATS = [
  { key: 'file', label: '文件路径', ph: '允许访问的路径，如 /data 或 /tmp/*' },
  { key: 'shell', label: '可执行文件', ph: '允许执行的可执行文件，如 python3（授予本机代码执行权限）' },
  { key: 'http', label: 'HTTP 域名', ph: '允许访问的域名，如 *.example.com' },
  { key: 'smtp', label: 'SMTP 端点', ph: '允许发信的邮件服务器，如 mail.example.com:25' },
]
const wl = ref({ loading: false, error: null, file: [], shell: [], http: [], smtp: [] })
async function loadWhitelist() {
  wl.value = { loading: true, error: null, file: [], shell: [], http: [], smtp: [] }
  try {
    const res = await fetch('/api/v1/sandbox/whitelist')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    const d = body.data || {}
    wl.value = { loading: false, error: null, file: d.file || [], shell: d.shell || [], http: d.http || [], smtp: d.smtp || [] }
  } catch (e) {
    wl.value = { loading: false, error: e.message, file: [], shell: [], http: [], smtp: [] }
  }
}

// 新增白名单表单：category ∈ file/shell/http/smtp，value 为一条白名单条目
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

// —— 工具策略管理（020：CRUD /api/v1/tool-policy）：全局 deny / Agent 例外 / Agent 收紧 + 每 Agent 有效工具集 ——
const TP_TYPES = [
  { key: 'GLOBAL_DENY', label: '全局禁用', needAgent: false },
  { key: 'AGENT_EXEMPT', label: 'Agent 例外（豁免全局禁用）', needAgent: true },
  { key: 'AGENT_DENY', label: 'Agent 定向禁用', needAgent: true },
]
const tp = ref({ loading: false, error: null, rules: [], effective: [], denied: [] })
async function loadToolPolicy() {
  tp.value = { loading: true, error: null, rules: [], effective: [], denied: [] }
  try {
    const res = await fetch('/api/v1/tool-policy')
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    // 策略拒绝的调用记录（审计筛选，FR-006/SC-005）
    const auditRes = await fetch('/api/v1/audit/tool?blockedBy=policy&limit=50')
    const auditBody = await auditRes.json()
    tp.value = {
      loading: false, error: null,
      rules: body.data.rules || [], effective: body.data.effective || [],
      denied: auditBody.code === 0 ? (auditBody.data || []) : [],
    }
  } catch (e) {
    tp.value = { loading: false, error: e.message, rules: [], effective: [], denied: [] }
  }
}
const tpForm = reactive({ open: false, ruleType: 'GLOBAL_DENY', agentName: '', pattern: '', busy: false, error: null })
const tpNeedAgent = computed(() => TP_TYPES.find((t) => t.key === tpForm.ruleType)?.needAgent)
function tpTypeLabel(type) { return TP_TYPES.find((t) => t.key === type)?.label || type }
async function addToolPolicyRule() {
  tpForm.busy = true; tpForm.error = null
  try {
    const payload = { ruleType: tpForm.ruleType, pattern: tpForm.pattern }
    if (tpNeedAgent.value) payload.agentName = tpForm.agentName
    const res = await fetch('/api/v1/tool-policy/rules', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '新增失败')
    cancelTp(); await loadToolPolicy()
  } catch (e) { tpForm.error = e.message } finally { tpForm.busy = false }
}
function cancelTp() { tpForm.open = false; tpForm.ruleType = 'GLOBAL_DENY'; tpForm.agentName = ''; tpForm.pattern = ''; tpForm.error = null }
async function deleteToolPolicyRule(rule) {
  if (!confirm(`删除策略规则「${tpTypeLabel(rule.ruleType)} ${rule.pattern}」？删除即刻生效。`)) return
  try {
    const res = await fetch(`/api/v1/tool-policy/rules/${rule.id}`, { method: 'DELETE' })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '删除失败')
    await loadToolPolicy()
  } catch (e) { tp.value = { ...tp.value, error: e.message } }
}

// —— Agent 详情：Tab 切换（基本信息 / 文件 / 会话 / 记忆）——
const agentDetail = ref(null) // { name, agent, tab, loading, error, node, editing }
const agentBinding = reactive({ selected: [], saving: false, error: null, issues: [], saved: false })
const agentKb = reactive({ selected: [], saving: false, error: null, issues: [], saved: false })
const fileView = ref(null) // { path, loading, error, content, saving, saved }
// 详情页「编辑基本信息」表单态 + 编辑态的 model 下拉数据源（与新建页的 createModels 分开，避免串台）
const editBasic = reactive({ description: '', provider: '', model: '' })
const editModels = ref({ loading: false, error: null, data: [] })
const editSaving = ref(false)
const editError = ref(null)
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
const chat = reactive({ sessionId: null, messages: [], loading: false, error: null, input: '', sending: false, stream: '', toolHint: null })
const chatScrollEl = ref(null) // 会话列表滚动容器：回复/重载后自动滚到底部（最新一条）

const CHAT_SEND_MODE_KEY = 'oryxos.admin.chatSendMode'
const CHAT_SEND_MODES = ['enter', 'modifier']

function loadChatSendMode() {
  try {
    const v = localStorage.getItem(CHAT_SEND_MODE_KEY)
    return CHAT_SEND_MODES.includes(v) ? v : 'modifier'
  } catch {
    return 'modifier'
  }
}

const chatSendMode = ref(loadChatSendMode())

const chatSendHint = computed(() => {
  if (chatSendMode.value === 'enter') return 'Enter 发送，Shift+Enter 换行'
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.platform)
  return isMac ? '⌘+Enter 发送，Enter 换行' : 'Ctrl+Enter 发送，Enter 换行'
})

function setChatSendMode(mode) {
  if (!CHAT_SEND_MODES.includes(mode)) return
  chatSendMode.value = mode
  try {
    localStorage.setItem(CHAT_SEND_MODE_KEY, mode)
  } catch {
    /* private mode / quota */
  }
}

function onChatInputKeydown(e) {
  if (e.key !== 'Enter') return
  const canSend = !chat.sending && chat.input.trim()
  if (chatSendMode.value === 'enter') {
    if (e.shiftKey || !canSend) return
    e.preventDefault()
    sendChat()
    return
  }
  if (!(e.ctrlKey || e.metaKey) || !canSend) return
  e.preventDefault()
  sendChat()
}
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
  agentDetail.value = { name: agent.name, agent, tab: 'info', loading: true, error: null, node: null, editing: false }
  agentBinding.selected = [...(agent.skills || [])]
  agentBinding.error = null
  agentBinding.issues = []
  agentBinding.saved = false
  skillFilter.query = ''; skillFilter.showHidden = false // 进入详情编辑页清空筛选态
  agentKb.selected = []
  agentKb.error = null
  agentKb.issues = []
  agentKb.saved = false
  fileView.value = null
  resetChat()
  resetAgentMemory()
  try {
    const [treeRes, bindingRes, kbRes] = await Promise.all([
      fetch('/api/v1/workspace/tree'),
      fetch(`/api/v1/agents/${encodeURIComponent(agent.name)}/skills`),
      fetch(`/api/v1/agents/${encodeURIComponent(agent.name)}/knowledge`),
      loadSkills(), // Skill 绑定选择器的数据源：存在即已安装
      loadKnowledge(), // 知识库绑定选择器的数据源
    ])
    const body = await treeRes.json()
    const bindingBody = await bindingRes.json()
    const kbBody = await kbRes.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    if (bindingBody.code !== 0) throw new Error(bindingBody.message || '绑定加载失败')
    if (kbBody.code !== 0) throw new Error(kbBody.message || '知识库绑定加载失败')
    agentKb.selected = (kbBody.data.bindings || []).map((b) => b.name)
    agentKb.issues = kbBody.data.issues || []
    const agentsNode = (body.data.children || []).find((c) => c.name === 'agents')
    const node = (agentsNode?.children || []).find((c) => c.name === agent.name) || null
    const outputTree = (body.data.children || []).find((c) => c.name === 'output') || null
    agentBinding.selected = (bindingBody.data.bindings || []).map((b) => b.name)
    agentBinding.issues = bindingBody.data.issues || []
    agentDetail.value = { ...agentDetail.value, loading: false, node, outputTree }
  } catch (e) {
    agentDetail.value = { ...agentDetail.value, loading: false, error: e.message }
  }
}

async function saveAgentBindings() {
  if (!agentDetail.value) return
  agentBinding.saving = true; agentBinding.error = null; agentBinding.saved = false
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentDetail.value.name)}/skills`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ skills: agentBinding.selected }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存绑定失败')
    agentBinding.selected = (body.data.bindings || []).map((b) => b.name)
    agentBinding.issues = body.data.issues || []
    agentBinding.saved = true
    agentDetail.value = {
      ...agentDetail.value,
      agent: { ...agentDetail.value.agent, skills: [...agentBinding.selected] },
    }
    await loadAgents()
    await reloadAgent() // skills/ 软连接已变，刷新文件树
  } catch (e) { agentBinding.error = e.message } finally { agentBinding.saving = false }
}

async function saveAgentKnowledge() {
  if (!agentDetail.value) return
  agentKb.saving = true; agentKb.error = null; agentKb.saved = false
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentDetail.value.name)}/knowledge`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ knowledge: agentKb.selected }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存知识库绑定失败')
    agentKb.selected = (body.data.bindings || []).map((b) => b.name)
    agentKb.issues = body.data.issues || []
    agentKb.saved = true
    await reloadAgent() // 绑定落成软连接后立即刷新文件树，工作区 tab 不再是旧内容
  } catch (e) { agentKb.error = e.message } finally { agentKb.saving = false }
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
    reloadAgent() // 每次进入都重拉文件树：绑定保存/GitOps 外部改动不再显示旧内容
  }
  if (tab === 'chat') {
    loadChat()
  } else if (tab === 'memory') {
    loadMemory()
  } else if (tab === 'executions') {
    loadExecutions()
  }
}

// —— 详情页「编辑基本信息」：结构化改 description / provider / model / skills（只动 AGENT.md frontmatter，正文与其它配置不动）——
function startEditBasic() {
  const a = agentDetail.value?.agent || {}
  editBasic.description = a.description || ''
  editBasic.provider = a.provider || ''
  editBasic.model = a.model || ''
  editError.value = null
  agentDetail.value = { ...agentDetail.value, editing: true }
  loadCreateProviders() // provider 下拉数据源（与新建页共用）
  loadEditModels(editBasic.provider) // 拉当前 provider 的模型列表
}
function cancelEditBasic() {
  agentDetail.value = { ...agentDetail.value, editing: false }
  editError.value = null
}
async function loadEditModels(name) {
  if (!name) { editModels.value = { loading: false, error: null, data: [] }; return }
  editModels.value = { loading: true, error: null, data: [] }
  try {
    const res = await fetch(`/api/v1/providers/${encodeURIComponent(name)}/models`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    editModels.value = { loading: false, error: null, data: body.data || [] }
  } catch (e) { editModels.value = { loading: false, error: e.message, data: [] } }
}
function onEditProviderChange() { editBasic.model = ''; loadEditModels(editBasic.provider) }
async function saveEditBasic() {
  if (!editBasic.provider || !editBasic.model) { editError.value = 'provider 与 model 必填'; return }
  editSaving.value = true; editError.value = null
  const name = agentDetail.value.name
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/basic`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        description: editBasic.description,
        provider: editBasic.provider,
        model: editBasic.model,
      }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    agentDetail.value = { ...agentDetail.value, editing: false }
    await reloadAgent() // 刷新 agent 元数据（provider/model/skills 即时反映）
  } catch (e) { editError.value = e.message } finally { editSaving.value = false }
}

// —— 025 人格卡：Agent 详情「基本信息」页的 7 字段人格展示 + 编辑（camelCase 键对 PUT /agents/{name}/persona，落盘 AGENT.md persona 段）——
const personaEdit = reactive({ open: false, name: '', role: '', traits: '', tone: '', values: '', boundaries: '', sampleStyle: '', saving: false, error: '' })
function startEditPersona() {
  const p = (agentDetail.value && agentDetail.value.agent && agentDetail.value.agent.persona) || {}
  personaEdit.open = true
  personaEdit.name = p.name || ''
  personaEdit.role = p.role || ''
  personaEdit.traits = p.traits || ''
  personaEdit.tone = p.tone || ''
  personaEdit.values = p.values || ''
  personaEdit.boundaries = p.boundaries || ''
  personaEdit.sampleStyle = p.sampleStyle || ''
  personaEdit.saving = false
  personaEdit.error = ''
}
function cancelEditPersona() { personaEdit.open = false }
async function savePersona() {
  if (!personaEdit.name.trim() || !personaEdit.role.trim()) { personaEdit.error = 'name 与 role 为必填'; return }
  personaEdit.saving = true; personaEdit.error = ''
  try {
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentDetail.value.name)}/persona`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: personaEdit.name.trim(), role: personaEdit.role.trim(),
        traits: personaEdit.traits.trim(), tone: personaEdit.tone.trim(),
        values: personaEdit.values.trim(), boundaries: personaEdit.boundaries.trim(),
        sampleStyle: personaEdit.sampleStyle.trim(),
      }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    personaEdit.open = false
    await reloadAgent()
  } catch (e) { personaEdit.error = e.message } finally { personaEdit.saving = false }
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
  return s < 60 ? s.toFixed(2) + ' s' : Math.floor(s / 60) + ' 分 ' + Math.round(s % 60) + ' 秒'
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
  chat.stream = ''
  chat.toolHint = null
}

// 会话列表按需滚到底部：刷新前仍在底部附近才继续跟随，用户上翻历史时保留阅读位置。
// nextTick 确保 chatTurns 渲染完再读 scrollHeight，否则还是旧值、滚不到底。
function scrollChatToBottom(shouldScroll) {
  if (!shouldScroll) return
  nextTick(() => {
    const el = chatScrollEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function loadChat() {
  const shouldScroll = isNearBottom(chatScrollEl.value)
  chat.loading = true; chat.error = null
  try {
    const name = agentDetail.value.name
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/session`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '加载失败')
    chat.sessionId = body.data.sessionId
    chat.messages = body.data.messages || []
  } catch (e) { chat.error = e.message } finally { chat.loading = false }
  scrollChatToBottom(shouldScroll)
}

// 019：解析 SSE 行协议（event/data 对，注释行心跳忽略）——EventSource 不支持 POST，手工读 ReadableStream
async function readSse(res, onEvent) {
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx); buf = buf.slice(idx + 2)
      let event = 'message', data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
        // 以 ":" 开头的注释行（心跳）直接忽略
      }
      if (data) onEvent(event, JSON.parse(data))
    }
  }
}

async function sendChat() {
  if (chat.sending || !chat.input.trim()) return
  chat.sending = true; chat.error = null; chat.stream = ''; chat.toolHint = null
  try {
    const name = agentDetail.value.name
    const res = await fetch(`/api/v1/agents/${encodeURIComponent(name)}/session/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ content: chat.input }),
    })
    const type = res.headers.get('Content-Type') || ''
    if (!type.includes('text/event-stream')) {
      // 流开始前的失败（404/400/401）或非流式兜底：沿用原 JSON 路径
      const body = await res.json()
      if (body.code !== 0) throw new Error(body.message || '发送失败')
    } else {
      let failed = null
      await readSse(res, (event, data) => {
        if (event === 'token') { chat.stream += data.delta; scrollChatToBottom(true) }
        else if (event === 'tool_start') chat.toolHint = data.name
        else if (event === 'tool_end') chat.toolHint = null
        else if (event === 'error') failed = data.message || '处理失败'
      })
      if (failed) throw new Error(failed)
    }
    chat.input = ''
    await loadChat()
  } catch (e) { chat.error = e.message } finally { chat.sending = false; chat.stream = ''; chat.toolHint = null }
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
            <div v-for="s in overviewCards" :key="s.label" class="card">
              <div class="card-val">
                <template v-if="s.loading">...</template>
                <template v-else-if="s.error">—</template>
                <template v-else>{{ s.value ?? '—' }}</template>
              </div>
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
              <span v-for="p in overviewStats.providers.providerNames" :key="p" class="tag">{{ p }}</span>
              <span v-if="!overviewStats.providers.providerNames.length" class="empty">（无）</span>
            </p>
          </div>

          <p v-if="overviewLoading" class="note mono">统计卡数据加载中…</p>
        </template>

        <template v-else>
          <div class="page-head">
            <h2>{{ current.label }}</h2>
            <button class="btn" @click="refresh()">刷新</button>
          </div>

          <!-- 报表（016 审计看板）：KPI 汇总 + 分布条形图 + 明细下钻；时间窗三档 -->
          <div v-if="active === 'report'">
            <div class="md-toggle" style="margin-bottom:14px">
              <button v-for="r in ['7d','30d','all']" :key="r" :class="['md-seg', { on: report.range === r }]" @click="loadReport(r)">{{ r === '7d' ? '近 7 天' : r === '30d' ? '近 30 天' : '全部' }}</button>
            </div>
            <!-- Trace 时间线（021）：按 trace ID 查单轮全链路——v0.3 Demo「触发 → 拿 traceId → 查完整链路与成本」 -->
            <div style="display:flex;gap:8px;margin-bottom:14px">
              <input class="mono" v-model="trace.id" placeholder="输入 trace ID 回放单轮全链路（响应/SSE/执行历史里都有）" style="flex:1" @keyup.enter="loadTrace()" />
              <button class="btn btn-primary" @click="loadTrace()">查询链路</button>
            </div>
            <p v-if="trace.loading" class="empty">链路查询中…</p>
            <p v-else-if="trace.error" class="error">出错：{{ trace.error }}</p>
            <template v-else-if="trace.result">
              <p v-if="!trace.result.found" class="empty">未找到 trace「{{ trace.result.traceId }}」的审计记录</p>
              <template v-else>
                <div class="sess-meta mono" style="margin-bottom:8px">
                  步骤 {{ trace.result.summary.steps }} · LLM {{ trace.result.summary.llmCalls }} 次 · 工具 {{ trace.result.summary.toolCalls }} 次
                  · token {{ trace.result.summary.totalTokens }} · 成本 {{ fmtCost(trace.result.summary.costMicros) }}
                  · 总耗时 {{ fmtDuration(trace.result.summary.totalDurationMs) }}
                </div>
                <table style="margin-bottom:18px">
                  <thead><tr><th>#</th><th>类型</th><th>名称</th><th>结果</th><th>耗时</th><th>时间</th><th>token / 摘要（已脱敏）</th></tr></thead>
                  <tbody>
                    <tr v-for="s in trace.result.steps" :key="s.seq">
                      <td class="mono">{{ s.seq }}</td>
                      <td><span class="tag">{{ s.type }}</span></td>
                      <td class="mono">{{ s.name }}</td>
                      <td><span :class="['tag', s.success ? 'ok' : 'off']">{{ s.success ? '成功' : (s.blockedBy === 'policy' ? '策略拦截' : '失败') }}</span></td>
                      <td class="mono">{{ fmtDuration(s.durationMs) }}</td>
                      <td class="mono">{{ fmtTime(s.at) }}</td>
                      <td class="mono" style="max-width:380px;overflow-wrap:anywhere">
                        <template v-if="s.type === 'LLM'">{{ s.totalTokens != null ? 'token ' + s.totalTokens : '—' }}{{ s.costMicros != null ? ' · ' + fmtCost(s.costMicros) : '' }}</template>
                        <template v-else>{{ s.inputSummary || '' }}<span v-if="s.errorMessage" class="error"> · {{ s.errorMessage }}</span></template>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </template>
            </template>
            <p v-if="report.loading" class="empty">加载中…</p>
            <p v-else-if="report.error" class="error">出错：{{ report.error }}</p>
            <template v-else>
              <div class="cards">
                <div class="card"><div class="card-val">{{ report.llm?.count ?? '—' }}</div><div class="card-label">LLM 调用</div><div class="card-hint">总次数</div></div>
                <div class="card"><div class="card-val">{{ fmtCost(report.llm?.totalCostMicros) }}</div><div class="card-label">总成本</div><div class="card-hint">已计量调用</div></div>
                <div class="card"><div class="card-val">{{ fmtRate(report.llm?.successRate) }}</div><div class="card-label">LLM 成功率</div><div class="card-hint">成功 / 总数</div></div>
                <div class="card"><div class="card-val">{{ report.llm ? fmtDuration(report.llm.avgDurationMs) : '—' }}</div><div class="card-label">LLM 平均耗时</div><div class="card-hint">单次调用</div></div>
                <div class="card"><div class="card-val">{{ report.tool?.count ?? '—' }}</div><div class="card-label">工具调用</div><div class="card-hint">总次数</div></div>
                <div class="card"><div class="card-val">{{ fmtRate(report.tool?.successRate) }}</div><div class="card-label">工具成功率</div><div class="card-hint">成功 / 总数</div></div>
              </div>

              <h3 class="sec">模型分布</h3>
              <div v-if="report.byModel.length" class="bars">
                <div v-for="m in report.byModel" :key="m.key"
                     :class="['bar-row', 'clickable', { on: reportFilter?.type === 'model' && reportFilter?.key === m.key }]"
                     @click="setReportFilter('model', m.key)">
                  <div class="bar-label mono">{{ m.key }}</div>
                  <div class="bar-track"><div class="bar-fill" :style="{ width: barWidth(report.byModel, m.count) }"></div></div>
                  <div class="bar-val mono">{{ m.count }} · {{ fmtCost(m.totalCostMicros) }}</div>
                </div>
              </div>
              <p v-else class="empty">（暂无数据）</p>

              <h3 class="sec">工具分布</h3>
              <div v-if="report.byTool.length" class="bars">
                <div v-for="m in report.byTool" :key="m.key"
                     :class="['bar-row', 'clickable', { on: reportFilter?.type === 'tool' && reportFilter?.key === m.key }]"
                     @click="setReportFilter('tool', m.key)">
                  <div class="bar-label mono">{{ m.key }}</div>
                  <div class="bar-track"><div class="bar-fill" :style="{ width: barWidth(report.byTool, m.count) }"></div></div>
                  <div class="bar-val mono">{{ m.count }}</div>
                </div>
              </div>
              <p v-else class="empty">（暂无数据）</p>

              <h3 class="sec">Agent 分布</h3>
              <div v-if="report.byAgent.length" class="bars">
                <div v-for="m in report.byAgent" :key="m.key"
                     :class="['bar-row', 'clickable', { on: reportFilter?.type === 'agent' && reportFilter?.key === m.key }]"
                     @click="setReportFilter('agent', m.key)">
                  <div class="bar-label mono">{{ m.key }}</div>
                  <div class="bar-track"><div class="bar-fill" :style="{ width: barWidth(report.byAgent, m.count) }"></div></div>
                  <div class="bar-val mono">{{ m.count }} · {{ fmtCost(m.totalCostMicros) }}</div>
                </div>
              </div>
              <p v-else class="empty">（暂无数据）</p>

              <div v-if="reportFilter" class="filter-bar">
                <span>已过滤：<b>{{ reportFilter.key }}</b>（{{ reportFilter.type === 'model' ? '模型' : reportFilter.type === 'tool' ? '工具' : 'Agent' }}）</span>
                <button class="btn" @click="clearReportFilter">✕ 清除过滤</button>
              </div>

              <h3 class="sec clickable" @click="reportExpand.llm = !reportExpand.llm">
                LLM 调用明细 <span class="mono">{{ reportExpand.llm ? '▾' : '▸' }}</span>
              </h3>
              <template v-if="reportExpand.llm">
                <table>
                  <thead><tr><th>时间</th><th>Agent</th><th>Provider</th><th>模型</th><th>输入</th><th>输出</th><th>总</th><th>成本</th><th>耗时</th><th>结果</th><th>Trace</th></tr></thead>
                  <tbody>
                    <tr v-if="!pagedLlmList.length"><td colspan="11" class="empty">（暂无数据）</td></tr>
                    <tr v-for="c in pagedLlmList" :key="c.id">
                      <td class="mono">{{ fmtTime(c.createdAt) }}</td>
                      <td>{{ c.profileName || '—' }}</td>
                      <td>{{ c.provider }}</td>
                      <td class="mono">{{ c.model }}</td>
                      <td class="mono">{{ c.promptTokens ?? '—' }}</td>
                      <td class="mono">{{ c.completionTokens ?? '—' }}</td>
                      <td class="mono">{{ c.totalTokens ?? '—' }}</td>
                      <td class="mono">{{ fmtCost(c.costMicros) }}</td>
                      <td class="mono">{{ fmtDuration(c.durationMs) }}</td>
                      <td><span :class="['tag', c.success ? 'ok' : 'off']">{{ c.success ? '成功' : '失败' }}</span></td>
                      <td class="mono trace-cell"><a v-if="c.traceId" href="#" title="点击回放该轮时间线" @click.prevent="loadTrace(c.traceId)">{{ c.traceId }}</a><template v-else>—</template></td>
                    </tr>
                  </tbody>
                </table>
                <div class="pager">
                  <span class="mono">共 {{ filteredLlmList.length }} 条</span>
                  <select v-model.number="llmPage.size">
                    <option :value="10">10 条/页</option>
                    <option :value="20">20 条/页</option>
                    <option :value="50">50 条/页</option>
                  </select>
                  <button class="btn" :disabled="llmPage.page <= 1" @click="llmPage.page--">上一页</button>
                  <span class="mono">{{ llmPage.page }} / {{ totalLlmPages }}</span>
                  <button class="btn" :disabled="llmPage.page >= totalLlmPages" @click="llmPage.page++">下一页</button>
                </div>
              </template>

              <h3 class="sec clickable" @click="reportExpand.tool = !reportExpand.tool">
                工具调用明细 <span class="mono">{{ reportExpand.tool ? '▾' : '▸' }}</span>
              </h3>
              <template v-if="reportExpand.tool">
                <table>
                  <thead><tr><th>时间</th><th>Agent</th><th>工具</th><th>耗时</th><th>结果</th><th>Trace</th></tr></thead>
                  <tbody>
                    <tr v-if="!pagedToolList.length"><td colspan="6" class="empty">（暂无数据）</td></tr>
                    <tr v-for="t in pagedToolList" :key="t.id">
                      <td class="mono">{{ fmtTime(t.createdAt) }}</td>
                      <td>{{ t.profileName || '—' }}</td>
                      <td class="mono">{{ t.toolName }}</td>
                      <td class="mono">{{ fmtDuration(t.durationMs) }}</td>
                      <td><span :class="['tag', t.success ? 'ok' : 'off']">{{ t.success ? '成功' : '失败' }}</span></td>
                      <td class="mono trace-cell"><a v-if="t.traceId" href="#" title="点击回放该轮时间线" @click.prevent="loadTrace(t.traceId)">{{ t.traceId }}</a><template v-else>—</template></td>
                    </tr>
                  </tbody>
                </table>
                <div class="pager">
                  <span class="mono">共 {{ filteredToolList.length }} 条</span>
                  <select v-model.number="toolPage.size">
                    <option :value="10">10 条/页</option>
                    <option :value="20">20 条/页</option>
                    <option :value="50">50 条/页</option>
                  </select>
                  <button class="btn" :disabled="toolPage.page <= 1" @click="toolPage.page--">上一页</button>
                  <span class="mono">{{ toolPage.page }} / {{ totalToolPages }}</span>
                  <button class="btn" :disabled="toolPage.page >= totalToolPages" @click="toolPage.page++">下一页</button>
                </div>
              </template>
            </template>
          </div>

          <!-- Skill：纯 CRUD 列表（存在即已安装）；绑定一致性仅在变更后回检发现问题时展示 -->
          <div v-if="active === 'skills'">
            <template v-if="!skillDetail">
            <div class="toolbar">
              <button class="btn" @click="newImport()">从 GitHub 拉取</button>
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
                  <label class="empty" style="display:block;margin:6px 0 2px">正文（仅在 Agent 判断任务需要后，经 read_file 按需读取）</label>
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
            <!-- 绑定一致性不常驻：Skill 变更后回检，发现残留/损坏绑定（或检查本身失败）才展开 -->
            <div v-if="skillIssuesOpen" class="issue-banner">
              <div class="issue-head">
                <span class="issue-title">绑定一致性问题<template v-if="skillIssues.data.length">（{{ skillIssues.data.length }}）</template></span>
                <button class="modal-x" @click="skillIssuesOpen = false">✕</button>
              </div>
              <p v-if="skillIssues.loading" class="empty">检查中…</p>
              <p v-else-if="skillIssues.error" class="error">检查失败：{{ skillIssues.error }}</p>
              <ul v-else class="issue-list">
                <li v-for="(issue, i) in skillIssues.data" :key="`${issue.path}:${i}`">
                  <span class="mono">{{ issue.agentName }}（{{ issue.agentState }}）</span>
                  <span class="issue-type">{{ issue.type }}</span>
                  <span class="mono">{{ issue.entryName || '—' }}</span>
                  <span class="empty">{{ issue.message }}</span>
                </li>
              </ul>
            </div>
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
                    <span class="mono">{{ node.type === 'dir' ? '📁' : node.type === 'link' ? '🔗' : '📄' }} {{ node.name }}</span>
                    <span v-if="node.type === 'link'" class="empty"> → {{ node.linkTarget }} · {{ node.linkStatus }}</span>
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

          <!-- 知识库（014）：列表 + 详情（文档清单/上传/重建/单文档删除）；管理操作按后端能力集渲染 -->
          <div v-else-if="active === 'knowledge'">
            <!-- 列表视图 -->
            <template v-if="!kbDetail">
              <div class="toolbar">
                <button class="btn btn-primary" @click="kbForm.open = true">+ 新建知识库</button>
              </div>
              <div v-if="kbForm.open" class="modal-overlay" @click.self="cancelKb()">
                <div class="modal-card">
                  <div class="modal-head"><h3>新建知识库</h3><button class="modal-x" @click="cancelKb()">✕</button></div>
                  <div class="modal-body">
                    <input v-model="kbForm.name" class="gen-input" placeholder="库名（字母/数字/下划线/连字符，即目录名）" />
                    <textarea v-model="kbForm.description" class="gen-input" rows="2" placeholder="描述（会注入 Agent 上下文，写清这库装什么知识）"></textarea>
                    <p v-if="kbForm.error" class="error">{{ kbForm.error }}</p>
                  </div>
                  <div class="modal-foot">
                    <button class="btn" @click="cancelKb">取消</button>
                    <button class="btn btn-primary" :disabled="kbForm.busy || !kbForm.name.trim() || !kbForm.description.trim()" @click="createKb">创建</button>
                  </div>
                </div>
              </div>
              <p v-if="kb.loading" class="empty">加载中…</p>
              <p v-else-if="kb.error" class="error">出错：{{ kb.error }}</p>
              <table v-else>
                <thead><tr><th>名称</th><th>描述</th><th>后端</th><th>文档数</th><th>片段数</th><th>索引状态</th><th style="width:160px">操作</th></tr></thead>
                <tbody>
                  <tr v-if="!kb.data.length"><td colspan="7" class="empty">（暂无知识库，点右上「新建知识库」或向 .oryxos/knowledge/ 放入目录）</td></tr>
                  <tr v-for="b in kb.data" :key="b.name">
                    <td class="mono">{{ b.name }}</td>
                    <td>{{ b.description }}</td>
                    <td class="mono">{{ b.backend }}</td>
                    <td>{{ b.documentCount }}</td>
                    <td>{{ b.chunkCount }}</td>
                    <td>{{ b.indexStatus }}</td>
                    <td class="ops">
                      <button class="btn" @click="refreshKbDetail(b.name)">详情</button>
                      <button v-if="b.capabilities?.createDelete" class="btn" @click="deleteKb(b.name)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
            <!-- 详情视图：文档清单 + 上传 + 重建（能力感知：只读后端不出上传/重建入口，FR-009） -->
            <div v-else>
              <button class="btn back" @click="closeKbDetail">← 返回知识库列表</button>
              <div class="sess-meta"><span>知识库</span><span class="mono">{{ kbDetail.name }}</span></div>
              <p v-if="kbDetail.loading" class="empty">加载中…</p>
              <template v-else>
                <p class="empty">{{ kbDetail.base?.description || '—' }}（后端：{{ kbDetail.base?.backend || '—' }} · 状态：{{ kbDetail.base?.indexStatus || '—' }} · 片段 {{ kbDetail.base?.chunkCount ?? '—' }}）</p>
                <div class="ops" style="margin:8px 0">
                  <label v-if="kbDetail.base?.capabilities?.importDocs" class="btn" style="cursor:pointer">
                    上传文档（md / txt / 文本型 PDF）
                    <input type="file" accept=".md,.markdown,.txt,.pdf" style="display:none" @change="uploadKbDoc" />
                  </label>
                  <button v-if="kbDetail.base?.capabilities?.rebuild" class="btn" :disabled="kbDetail.busy" @click="reindexKb">重建索引</button>
                  <button class="btn" :disabled="kbDetail.busy" @click="refreshKbDetail(kbDetail.name)">刷新状态</button>
                </div>
                <p v-if="kbDetail.busy" class="empty">处理中…（切分向量化在后台推进，可点「刷新状态」跟进）</p>
                <p v-if="kbDetail.error" class="error">{{ kbDetail.error }}</p>
                <table>
                  <thead><tr><th>文档</th><th>状态</th><th>片段数</th><th>最近索引</th><th style="width:90px">操作</th></tr></thead>
                  <tbody>
                    <tr v-if="!kbDetail.documents.length"><td colspan="5" class="empty">（暂无文档）</td></tr>
                    <tr v-for="d in kbDetail.documents" :key="d.relPath">
                      <td class="mono">{{ d.relPath }}</td>
                      <td>{{ d.state }}<span v-if="d.failureReason" class="error">：{{ d.failureReason }}</span></td>
                      <td>{{ d.chunkCount }}</td>
                      <td class="mono">{{ d.indexedAt ? new Date(d.indexedAt).toLocaleString() : '—' }}</td>
                      <td class="ops"><button v-if="kbDetail.base?.capabilities?.importDocs" class="btn" @click="deleteKbDoc(d.relPath)">删除</button></td>
                    </tr>
                  </tbody>
                </table>

                <!-- 使用看板（FR-023）：只消费审计数据聚合，指标可与审计记录核对（SC-009） -->
                <h3 class="sec" style="margin-top:20px">使用看板</h3>
                <div class="md-toggle" style="margin-bottom:8px">
                  <button :class="['md-seg', { on: kbMetrics.range === '7d' }]" @click="loadKbMetrics('7d')">近 7 天</button>
                  <button :class="['md-seg', { on: kbMetrics.range === '30d' }]" @click="loadKbMetrics('30d')">近 30 天</button>
                  <button :class="['md-seg', { on: kbMetrics.range === 'all' }]" @click="loadKbMetrics('all')">全部</button>
                </div>
                <p v-if="kbMetrics.loading" class="empty">加载中…</p>
                <p v-else-if="kbMetrics.error" class="error">看板加载失败：{{ kbMetrics.error }}</p>
                <template v-else-if="kbMetrics.data">
                  <p class="empty">
                    检索 <b>{{ kbMetrics.data.retrievalCount }}</b> 次 ·
                    零结果率 <b>{{ fmtRate(kbMetrics.data.zeroResultRate) }}</b>（{{ kbMetrics.data.zeroResultCount }} 次）·
                    降级率 <b>{{ fmtRate(kbMetrics.data.degradedRate) }}</b> ·
                    出处引用率 <b>{{ fmtRate(kbMetrics.data.citationRate) }}</b>（近似）
                  </p>
                  <template v-if="kbMetrics.data.hitDocuments.length">
                    <p class="empty">命中文档分布：</p>
                    <table>
                      <thead><tr><th>文档</th><th style="width:90px">命中次数</th></tr></thead>
                      <tbody>
                        <tr v-for="h in kbMetrics.data.hitDocuments" :key="h.relPath">
                          <td class="mono">{{ h.relPath }}</td><td>{{ h.hits }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </template>
                  <template v-if="kbMetrics.data.zeroResultQueries.length || kbMetrics.data.unattributedZeroResults">
                    <p class="empty">零结果查询（判断该补什么文档）：</p>
                    <ul class="issue-list">
                      <li v-for="(q, i) in kbMetrics.data.zeroResultQueries" :key="'z'+i" class="mono">{{ q }}</li>
                      <li v-for="(q, i) in kbMetrics.data.unattributedZeroResultQueries" :key="'u'+i" class="mono">{{ q }}（跨库聚合，未限定本库）</li>
                    </ul>
                  </template>
                  <p v-else-if="!kbMetrics.data.retrievalCount" class="empty">（时间窗内暂无检索记录）</p>
                </template>
              </template>
            </div>
          </div>

          <!-- Sandbox 白名单：四类 file/shell/http/smtp 的 CRUD（新增走弹框 / 逐行删除） -->
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
                    <option value="smtp">SMTP 端点</option>
                  </select>
                  <input v-model="wlForm.value" class="gen-input" :placeholder="wlPlaceholder" />
                  <p class="empty">选择类别并填写一条白名单条目：文件路径 / 可执行文件 / HTTP 域名 / SMTP 端点（域名支持通配，如 *.example.com）。</p>
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

          <!-- 020：工具策略 —— 规则 CRUD + 每 Agent 有效工具集（含被移除原因）+ 策略拒绝记录 -->
          <div v-else-if="active === 'tool-policy'">
            <div class="toolbar">
              <button class="btn btn-primary" @click="tpForm.open = true">+ 新增策略规则</button>
            </div>
            <div v-if="tpForm.open" class="modal-overlay" @click.self="cancelTp()">
              <div class="modal-card">
                <div class="modal-head"><h3>新增策略规则</h3><button class="modal-x" @click="cancelTp()">✕</button></div>
                <div class="modal-body">
                  <select v-model="tpForm.ruleType" class="gen-input">
                    <option v-for="t in TP_TYPES" :key="t.key" :value="t.key">{{ t.label }}</option>
                  </select>
                  <input v-if="tpNeedAgent" v-model="tpForm.agentName" class="gen-input" placeholder="Agent 名（如 ops-agent）" />
                  <input v-model="tpForm.pattern" class="gen-input" placeholder="工具名（如 shell）或 MCP 通配（如 github-mcp:*）" />
                  <p class="empty">策略只做减法：例外仅解除全局禁用，不能授予 Agent 未声明的工具；变更即刻生效（热更新）。</p>
                  <p v-if="tpForm.error" class="error">{{ tpForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelTp">取消</button>
                  <button class="btn btn-primary" :disabled="tpForm.busy || !tpForm.pattern.trim() || (tpNeedAgent && !tpForm.agentName.trim())" @click="addToolPolicyRule">新增</button>
                </div>
              </div>
            </div>
            <p v-if="tp.loading" class="empty">加载中…</p>
            <p v-else-if="tp.error" class="error">出错：{{ tp.error }}</p>
            <template v-else>
              <h3 class="sec" style="margin-top:20px">策略规则</h3>
              <table>
                <thead><tr><th>类型</th><th>Agent</th><th>pattern</th><th>来源</th><th>时间</th><th style="width:90px">操作</th></tr></thead>
                <tbody>
                  <tr v-if="!tp.rules.length"><td colspan="6" class="empty">（暂无策略规则——零策略时一切行为与现状一致）</td></tr>
                  <tr v-for="r in tp.rules" :key="r.id">
                    <td>{{ tpTypeLabel(r.ruleType) }}</td>
                    <td class="mono">{{ r.agentName || '（全部）' }}</td>
                    <td class="mono">{{ r.pattern }}<span v-if="r.unknownTarget" class="error" title="未注册的工具名，可能拼写有误"> ⚠</span></td>
                    <td class="mono">{{ r.createdBy || '-' }}</td>
                    <td class="mono">{{ r.createdAt ? String(r.createdAt).slice(0, 19) : '-' }}</td>
                    <td class="ops"><button class="btn" @click="deleteToolPolicyRule(r)">删除</button></td>
                  </tr>
                </tbody>
              </table>
              <h3 class="sec" style="margin-top:20px">各 Agent 有效工具集</h3>
              <table>
                <thead><tr><th>Agent</th><th>声明</th><th>有效</th><th>被策略移除（原因）</th></tr></thead>
                <tbody>
                  <tr v-if="!tp.effective.length"><td colspan="4" class="empty">（暂无 Agent）</td></tr>
                  <tr v-for="e in tp.effective" :key="e.agentName">
                    <td class="mono">{{ e.agentName }}</td>
                    <td class="mono">{{ e.declared.join(', ') || '（无）' }}</td>
                    <td class="mono">{{ e.effective.join(', ') || '（全空——将以纯对话运行）' }}</td>
                    <td class="mono">
                      <template v-if="e.removed.length">
                        <div v-for="rm in e.removed" :key="rm.toolName">{{ rm.toolName }} — {{ rm.reason }}</div>
                      </template>
                      <span v-else class="empty">（无）</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <h3 class="sec" style="margin-top:20px">策略拒绝记录（最近 50 条）</h3>
              <table>
                <thead><tr><th>Agent</th><th>工具</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-if="!tp.denied.length"><td colspan="3" class="empty">（暂无策略拒绝的调用）</td></tr>
                  <tr v-for="d in tp.denied" :key="d.id">
                    <td class="mono">{{ d.profileName || '-' }}</td>
                    <td class="mono">{{ d.toolName }}</td>
                    <td class="mono">{{ d.createdAt ? String(d.createdAt).slice(0, 19) : '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </template>
          </div>

          <!-- 30 节：Agent —— 一个列表（含新建/删除）；点"详情"进这个 Agent 的文件浏览器 -->
          <div v-else-if="active === 'agents'">
            <!-- 新建视图：独立整页（不是弹框），把「大模型生成」折叠进来 -->
            <div v-if="agentCreate.open">
              <button class="btn back" @click="cancelCreate">← 返回</button>
              <div class="sess-meta"><span>新建 Agent</span></div>
              <div class="tabs">
                <button :class="['tab', { on: createMode === 'llm' }]" @click="setCreateMode('llm')">一句话生成</button>
                <button :class="['tab', { on: createMode === 'import' }]" @click="setCreateMode('import')">从人格库导入</button>
              </div>
              <template v-if="createMode === 'llm'">
              <div class="gen-box">
                <label class="empty" style="display:block;margin-bottom:2px">Agent 名（字母/数字/下划线/连字符，必填）</label>
                <input v-model="agentCreate.name" class="gen-input" placeholder="例如 pr-digest" />
                <label class="empty" style="display:block;margin:6px 0 2px">描述这个 Agent 要做什么</label>
                <textarea v-model="agentCreate.description" class="gen-draft" rows="4" placeholder="例如：每天早上抓取团队仓库的 PR，汇总成一份摘要推送到群里"></textarea>
                <label class="empty" style="display:block;margin:6px 0 2px">Provider（模型供应商，从已配置的 Provider 里选；不选=默认 provider）</label>
                <select v-model="agentCreate.provider" class="gen-input" @change="onProviderChange">
                  <option value="">默认 provider</option>
                  <option v-for="p in (createProviders.data || [])" :key="p.name" :value="p.name">{{ p.name }}</option>
                </select>
                <label class="empty" style="display:block;margin:6px 0 2px">Model（选好 Provider 后从它的模型列表里挑；不选=生成时再填）</label>
                <select v-model="agentCreate.model" class="gen-input" :disabled="!agentCreate.provider">
                  <option value="">— 请先选 Provider —</option>
                  <option v-for="m in (createModels.data || [])" :key="m" :value="m">{{ m }}</option>
                </select>
                <p v-if="createProviders.loading" class="empty">加载 Provider 列表…</p>
                <p v-else-if="createProviders.error" class="error">Provider 列表加载失败：{{ createProviders.error }}</p>
                <p v-else-if="agentCreate.provider && createModels.loading" class="empty">加载模型列表…</p>
                <p v-else-if="agentCreate.provider && createModels.error" class="error">模型列表加载失败：{{ createModels.error }}</p>
                <label class="empty" style="display:block;margin:6px 0 2px">通知渠道（投递目标，由你手动选；不选=本 Agent 不发通知）</label>
                <select v-model="agentCreate.notifyChannel" class="gen-input">
                  <option value="">不通知</option>
                  <option v-for="c in (notifyChannels.data || [])" :key="c.name" :value="c.name">{{ c.name }}（{{ c.type }}）</option>
                </select>
                <label class="empty" style="display:block;margin:6px 0 2px">Skill 绑定（勾选=required；作者可从已安装 Skill 再建议）</label>
                <div class="skill-picker">
                  <span v-if="!skills.data.length" class="empty">（暂无已安装 Skill，可先到 Skill 页新建或从 GitHub 拉取）</span>
                  <template v-else>
                    <input class="gen-input skill-search" v-model="skillFilter.query" placeholder="按名称或描述筛选已安装 Skill" />
                    <div v-if="createSkillHiddenCount > 0" class="skill-hidden-hint">
                      <span>当前筛选隐藏了 {{ createSkillHiddenCount }} 项已选</span>
                      <button type="button" class="btn" @click="skillFilter.showHidden = true" v-if="!skillFilter.showHidden">纳入视野</button>
                      <button type="button" class="btn" @click="skillFilter.showHidden = false" v-else>恢复筛选</button>
                    </div>
                    <div class="skill-batch">
                      <button type="button" class="btn" @click="agentCreate.skills = selectAllVisible(createSkillVisible, agentCreate.skills)" :disabled="!createSkillVisible.length">全选当前</button>
                      <button type="button" class="btn" @click="agentCreate.skills = clearVisible(createSkillVisible, agentCreate.skills)" :disabled="!createSkillVisible.length">清空当前</button>
                    </div>
                    <label v-for="s in createSkillRender" :key="s.name" class="skill-opt" :class="{ 'skill-hidden': s.hidden }" :title="s.description">
                      <input type="checkbox" :value="s.name" v-model="agentCreate.skills" />
                      <span class="mono">{{ s.name }}</span>
                    </label>
                    <span v-if="!createSkillRender.length" class="empty">（无匹配 Skill）</span>
                  </template>
                </div>
                <p v-if="agentCreate.suggestedSkills.length" class="empty">作者建议：{{ agentCreate.suggestedSkills.join('、') }}；已合并到最终绑定，可在创建前取消。</p>
                <p class="empty">绑定保存为 agents/&lt;name&gt;/skills/&lt;skill&gt; 固定相对软连接；AGENT.md 不保存 skills 字段，也不预载正文。</p>
                <label class="empty" style="display:block;margin:6px 0 2px">知识库绑定（可多选；「用大模型生成」会按需求给出建议）</label>
                <div class="skill-picker">
                  <span v-if="!kb.data.length" class="empty">（暂无知识库，可先到「知识库」页新建）</span>
                  <label v-for="b in kb.data" :key="b.name" class="skill-opt" :title="b.description">
                    <input type="checkbox" :value="b.name" v-model="agentCreate.knowledge" />
                    <span class="mono">{{ b.name }}</span>
                  </label>
                </div>
                <p v-if="agentCreate.suggestedKnowledge.length" class="empty">作者建议知识库：{{ agentCreate.suggestedKnowledge.join('、') }}；已合并到选择，可在创建前取消。</p>
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
              </template>

              <!-- 从人格库导入（025）：llm 之外的另一种建法。选人格/粘贴源 → import-preview 预览 → import 落盘，成功后直接进详情 -->
              <div v-else class="gen-box">
                <label class="empty" style="display:block;margin-bottom:2px">从人格库选（12 个内置 + 你保存的自定义；人格的新建/编辑/删除请到左侧「人格库」页），或上传/粘贴 agency-agents-zh 风格的 .md（身份段会被解析成 persona 7 字段，落盘成 AGENT.md）</label>
                <div class="skill-picker">
                  <span v-if="personaPresets.loading" class="empty">加载人格预设…</span>
                  <span v-else-if="personaPresets.error" class="error">加载失败：{{ personaPresets.error }}</span>
                  <template v-else>
                    <button v-for="p in personaPresets.data" :key="p.key"
                            :class="['preset-opt', { on: agentImport.selected === p.key }]"
                            :disabled="agentImport.busy"
                            @click="pickPreset(p)"
                            :title="p.sourceFile || '自定义人格'">
                      <span class="preset-emoji">{{ p.emoji }}</span>
                      <span class="preset-label">{{ p.label }}
                        <span class="preset-badge" :class="p.builtin ? 'b-in' : 'b-cu'">{{ p.builtin ? '内置' : '自定义' }}</span>
                      </span>
                      <span class="empty preset-desc">{{ p.description }}</span>
                    </button>
                  </template>
                </div>
                <label class="empty" style="display:block;margin:6px 0 2px">或上传 .md 源文件（.md，自动读入下方文本框）</label>
                <input type="file" accept=".md,text/markdown" class="gen-input" @change="onImportFile" />
                <label class="empty" style="display:block;margin:6px 0 2px">源文件内容（可粘贴后编辑，改动后重新「预览」）</label>
                <textarea v-model="agentImport.sourceContent" class="gen-draft" rows="6" placeholder="---&#10;name: …&#10;description: …&#10;---&#10;## 核心使命&#10;…（agency-agents-zh 人格文件；身份段按 角色/个性/性格 行级关键字解析）"></textarea>
                <label class="empty" style="display:block;margin:6px 0 2px">Agent 名（合法 slug：字母/数字/下划线/连字符；中文 displayName 派生不出合法 slug，默认用预设 key）</label>
                <input v-model="agentImport.name" class="gen-input" placeholder="例如 product-manager" />
                <label class="empty" style="display:block;margin:6px 0 2px">模型（可选，缺省落占位，导入后可在基本信息里改）</label>
                <select v-model="agentImport.provider" class="gen-input" @change="onImportProviderChange">
                  <option value="">Provider…</option>
                  <option v-for="p in (createProviders.data || [])" :key="p.name" :value="p.name">{{ p.name }}</option>
                </select>
                <select v-if="agentImport.provider" v-model="agentImport.model" class="gen-input" style="margin-top:4px">
                  <option value="">模型…</option>
                  <option v-for="m in (createModels.data || [])" :key="m" :value="m">{{ m }}</option>
                </select>
                <p v-if="createProviders.loading" class="empty">加载 Provider 列表…</p>
                <p v-else-if="createProviders.error" class="error">Provider 列表加载失败：{{ createProviders.error }}</p>
                <p v-else-if="agentImport.provider && createModels.loading" class="empty">加载模型列表…</p>
                <p v-else-if="agentImport.provider && createModels.error" class="error">模型列表加载失败：{{ createModels.error }}</p>
                <div class="ops">
                  <button class="btn" :disabled="agentImport.busy || !agentImport.sourceContent.trim()" @click="previewImport">预览</button>
                  <button class="btn btn-primary" :disabled="agentImport.busy || !agentImport.name.trim() || !agentImport.sourceContent.trim()" @click="submitImport">{{ agentImport.selected ? '导入此人格' : '导入' }}</button>
                </div>
                <p v-if="agentImport.busy" class="empty">处理中…</p>
                <p v-if="agentImport.error" class="error">{{ agentImport.error }}</p>
                <template v-if="agentImport.preview">
                  <div class="sess-meta" style="margin-top:8px"><span>预览</span><span class="mono">{{ agentImport.preview.name }}</span></div>
                  <div class="preview-valid">
                    <template v-if="agentImport.preview.validation && agentImport.preview.validation.valid">
                      <span class="ok">✅ 可导入：provider={{ agentImport.preview.validation.provider || '—' }}，model={{ agentImport.preview.validation.model || '占位（导入后可改）' }}</span>
                    </template>
                    <template v-else>
                      <span class="error">❌ 无法导入：{{ (agentImport.preview.validation && agentImport.preview.validation.message) || '未知错误' }}</span>
                    </template>
                  </div>
                  <div class="info-grid">
                    <div class="info-row"><span class="k">role</span><span>{{ agentImport.preview.expert.role || '—' }}</span></div>
                    <div class="info-row"><span class="k">traits</span><span>{{ agentImport.preview.expert.traits || '—' }}</span></div>
                    <div class="info-row"><span class="k">background</span><span>{{ agentImport.preview.expert.background || '—' }}</span></div>
                    <div class="info-row"><span class="k">communication</span><span>{{ agentImport.preview.expert.communication || '—' }}</span></div>
                    <div class="info-row"><span class="k">keyRules</span><span>{{ agentImport.preview.expert.keyRules || '—' }}</span></div>
                    <div class="info-row"><span class="k">boundaries</span><span>{{ agentImport.preview.expert.boundaries || '—' }}</span></div>
                    <div class="info-row"><span class="k">sampleStyle</span><span>{{ agentImport.preview.expert.sampleStyle || '—' }}</span></div>
                  </div>
                  <label class="empty" style="display:block;margin:6px 0 2px">渲染出的 AGENT.md（落盘前只读预览）</label>
                  <div class="gen-file"><textarea class="mono filetext" readonly :value="agentImport.preview.agentMarkdown"></textarea></div>
                </template>
              </div>
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
                <div class="info-actions" v-if="!agentDetail.editing">
                  <button class="btn" @click="startEditBasic">编辑基本信息</button>
                </div>
                <template v-if="agentDetail.editing">
                  <div class="info-row edit">
                    <label class="k">description</label>
                    <textarea v-model="editBasic.description" class="gen-input" rows="3" placeholder="这个 Agent 做什么"></textarea>
                  </div>
                  <div class="info-row edit">
                    <label class="k">provider</label>
                    <select v-model="editBasic.provider" class="gen-input" @change="onEditProviderChange">
                      <option value="">— 请选择 Provider —</option>
                      <option v-for="p in (createProviders.data || [])" :key="p.name" :value="p.name">{{ p.name }}</option>
                    </select>
                  </div>
                  <div class="info-row edit">
                    <label class="k">model</label>
                    <select v-model="editBasic.model" class="gen-input" :disabled="!editBasic.provider">
                      <option value="">— 请先选 Provider —</option>
                      <option v-for="m in (editModels.data || [])" :key="m" :value="m">{{ m }}</option>
                    </select>
                    <p v-if="editModels.loading" class="empty">加载模型列表…</p>
                    <p v-else-if="editModels.error" class="error">模型列表加载失败：{{ editModels.error }}</p>
                  </div>
                  <div class="info-actions">
                    <button class="btn btn-primary" :disabled="editSaving" @click="saveEditBasic">保存</button>
                    <button class="btn" :disabled="editSaving" @click="cancelEditBasic">取消</button>
                    <span v-if="editSaving" class="empty">保存中…</span>
                    <span v-if="editError" class="error">{{ editError }}</span>
                  </div>
                  <p class="empty">name 为 Agent 标识，不可改；正文/工具/定时等配置不受影响。</p>
                </template>
                <template v-else>
                  <div class="info-row"><span class="k">name</span><span class="mono">{{ agentDetail.agent.name }}</span></div>
                  <div class="info-row"><span class="k">description</span><span>{{ agentDetail.agent.description || '—' }}</span></div>
                  <div class="info-row"><span class="k">provider</span><span>{{ agentDetail.agent.provider || '—' }}</span></div>
                  <div class="info-row"><span class="k">model</span><span>{{ agentDetail.agent.model || '—' }}</span></div>
                  <div class="info-row"><span class="k">tools</span><span>{{ (agentDetail.agent.tools || []).join(', ') || '—' }}</span></div>
                  <div class="info-row"><span class="k">skills</span>
                    <div>
                      <div class="skill-picker">
                        <span v-if="!skills.data.length" class="empty">（暂无已安装 Skill）</span>
                        <template v-else>
                          <input class="gen-input skill-search" v-model="skillFilter.query" placeholder="按名称或描述筛选已安装 Skill" />
                          <div v-if="editSkillHiddenCount > 0" class="skill-hidden-hint">
                            <span>当前筛选隐藏了 {{ editSkillHiddenCount }} 项已选</span>
                            <button type="button" class="btn" @click="skillFilter.showHidden = true" v-if="!skillFilter.showHidden">纳入视野</button>
                            <button type="button" class="btn" @click="skillFilter.showHidden = false" v-else>恢复筛选</button>
                          </div>
                          <div class="skill-batch">
                            <button type="button" class="btn" @click="agentBinding.selected = selectAllVisible(editSkillVisible, agentBinding.selected); agentBinding.saved = false" :disabled="!editSkillVisible.length">全选当前</button>
                            <button type="button" class="btn" @click="agentBinding.selected = clearVisible(editSkillVisible, agentBinding.selected); agentBinding.saved = false" :disabled="!editSkillVisible.length">清空当前</button>
                          </div>
                          <label v-for="s in editSkillRender" :key="s.name" class="skill-opt" :class="{ 'skill-hidden': s.hidden }" :title="s.description">
                            <input type="checkbox" :value="s.name" v-model="agentBinding.selected" @change="agentBinding.saved = false" />
                            <span class="mono">{{ s.name }}</span>
                          </label>
                          <span v-if="!editSkillRender.length" class="empty">（无匹配 Skill）</span>
                        </template>
                      </div>
                      <div class="ops">
                        <button class="btn" :disabled="agentBinding.saving" @click="saveAgentBindings">{{ agentBinding.saving ? '保存中…' : '保存绑定' }}</button>
                        <span v-if="agentBinding.saved" class="ok">已保存，下一轮对话生效</span>
                      </div>
                      <p v-if="agentBinding.error" class="error">{{ agentBinding.error }}</p>
                      <p v-for="(issue, i) in agentBinding.issues" :key="i" class="error">{{ issue.type }}：{{ issue.message }}</p>
                    </div>
                  </div>
                  <div class="info-row"><span class="k">knowledge</span>
                    <div>
                      <div class="skill-picker">
                        <span v-if="!kb.data.length" class="empty">（暂无知识库）</span>
                        <label v-for="b in kb.data" :key="b.name" class="skill-opt" :title="b.description">
                          <input type="checkbox" :value="b.name" v-model="agentKb.selected" @change="agentKb.saved = false" />
                          <span class="mono">{{ b.name }}</span>
                        </label>
                      </div>
                      <div class="ops">
                        <button class="btn" :disabled="agentKb.saving" @click="saveAgentKnowledge">{{ agentKb.saving ? '保存中…' : '保存知识库绑定' }}</button>
                        <span v-if="agentKb.saved" class="ok">已保存，下一轮对话生效</span>
                      </div>
                      <p v-if="agentKb.error" class="error">{{ agentKb.error }}</p>
                      <p v-for="(issue, i) in agentKb.issues" :key="'kb'+i" class="error">{{ issue.type }}：{{ issue.message }}</p>
                    </div>
                  </div>
                  <div class="info-row"><span class="k">定时</span><span class="mono">{{ (agentDetail.agent.schedules || []).map((s) => s.cron + ' (' + s.zone + ')').join('；') || '—' }}</span></div>
                </template>
              </div>

              <!-- 025 人格卡：7 字段展示 + 编辑（PUT /agents/{name}/persona 落盘成 AGENT.md persona 段，随每轮注入 system prompt） -->
              <div v-if="agentDetail.tab === 'info'" style="margin-top:14px">
                <div class="sess-meta"><span>人格</span>
                  <button v-if="!personaEdit.open" class="btn" @click="startEditPersona">{{ agentDetail.agent.persona ? '编辑人格' : '设置人格' }}</button>
                </div>
                <template v-if="personaEdit.open">
                  <div class="gen-box">
                    <div class="info-row edit"><label class="k">name</label><input v-model="personaEdit.name" class="gen-input" placeholder="人格名（必填）" /></div>
                    <div class="info-row edit"><label class="k">role</label><input v-model="personaEdit.role" class="gen-input" placeholder="角色定位（必填）" /></div>
                    <div class="info-row edit"><label class="k">traits</label><textarea v-model="personaEdit.traits" class="gen-input" rows="2" placeholder="个性特征"></textarea></div>
                    <div class="info-row edit"><label class="k">tone</label><textarea v-model="personaEdit.tone" class="gen-input" rows="2" placeholder="说话语气"></textarea></div>
                    <div class="info-row edit"><label class="k">values</label><textarea v-model="personaEdit.values" class="gen-input" rows="2" placeholder="价值观"></textarea></div>
                    <div class="info-row edit"><label class="k">boundaries</label><textarea v-model="personaEdit.boundaries" class="gen-input" rows="2" placeholder="边界/原则"></textarea></div>
                    <div class="info-row edit"><label class="k">sampleStyle</label><textarea v-model="personaEdit.sampleStyle" class="gen-input" rows="3" placeholder="一句话风格示例"></textarea></div>
                    <div class="info-actions">
                      <button class="btn btn-primary" :disabled="personaEdit.saving || !personaEdit.name.trim() || !personaEdit.role.trim()" @click="savePersona">保存</button>
                      <button class="btn" :disabled="personaEdit.saving" @click="cancelEditPersona">取消</button>
                      <span v-if="personaEdit.saving" class="empty">保存中…</span>
                      <span v-if="personaEdit.error" class="error">{{ personaEdit.error }}</span>
                    </div>
                    <p class="empty">name/role 为必填；这 7 个字段会被写进 AGENT.md 的 persona 段，每轮对话固定注入 system prompt。</p>
                  </div>
                </template>
                <div v-else class="info-grid">
                  <template v-if="agentDetail.agent.persona">
                    <div class="info-row"><span class="k">name</span><span>{{ agentDetail.agent.persona.name }}</span></div>
                    <div class="info-row"><span class="k">role</span><span>{{ agentDetail.agent.persona.role }}</span></div>
                    <div class="info-row"><span class="k">traits</span><span>{{ agentDetail.agent.persona.traits || '—' }}</span></div>
                    <div class="info-row"><span class="k">tone</span><span>{{ agentDetail.agent.persona.tone || '—' }}</span></div>
                    <div class="info-row"><span class="k">values</span><span>{{ agentDetail.agent.persona.values || '—' }}</span></div>
                    <div class="info-row"><span class="k">boundaries</span><span>{{ agentDetail.agent.persona.boundaries || '—' }}</span></div>
                    <div class="info-row"><span class="k">sampleStyle</span><span>{{ agentDetail.agent.persona.sampleStyle || '—' }}</span></div>
                  </template>
                  <p v-else class="empty" style="padding:12px">未设置人格。点右上「设置人格」按 7 字段定义；或到「新建 Agent → 从人格库导入」从 12 个默认人格预设导入。</p>
                </div>
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
                      <span class="mono">{{ node.type === 'dir' ? '📁' : node.type === 'link' ? '🔗' : '📄' }} {{ node.name }}</span>
                      <span v-if="node.type === 'link'" class="empty"> → {{ node.linkTarget }} · {{ node.linkStatus }}</span>
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
                <p v-if="chat.loading && !chat.messages.length" class="empty">加载中…</p>
                <p v-else-if="chat.error" class="error">出错：{{ chat.error }}</p>
                <template v-else>
                  <p v-if="!chat.messages.length && !chat.sending" class="empty">（还没有对话，在下面发一条消息开始）</p>
                  <div v-else class="chat" ref="chatScrollEl">
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
                    <!-- 019：流式进行中的打字机气泡与工具状态（done 后由 loadChat 的正式历史替换） -->
                    <div v-if="chat.sending && (chat.stream || chat.toolHint)" class="turn">
                      <div v-if="chat.toolHint" class="msg tool">
                        <div class="msg-role">{{ roleLabel('tool') }}<span class="mono tool-name"> · {{ chat.toolHint }}</span></div>
                        <pre class="msg-body">调用中…</pre>
                      </div>
                      <div v-if="chat.stream" class="msg assistant answer">
                        <div class="msg-role">{{ roleLabel('assistant') }}</div>
                        <pre class="msg-body">{{ chat.stream }}</pre>
                      </div>
                    </div>
                  </div>
                </template>
                <div class="chat-input">
                  <textarea
                    v-model="chat.input"
                    class="gen-draft"
                    rows="3"
                    placeholder="给这个 Agent 发条消息…"
                    @keydown="onChatInputKeydown"
                  ></textarea>
                  <div class="chat-send-bar">
                    <div class="md-toggle send-mode-toggle">
                      <button
                        type="button"
                        :class="['md-seg', chatSendMode === 'modifier' && 'on']"
                        @click="setChatSendMode('modifier')"
                      >
                        Ctrl+Enter 发送
                      </button>
                      <button
                        type="button"
                        :class="['md-seg', chatSendMode === 'enter' && 'on']"
                        @click="setChatSendMode('enter')"
                      >
                        Enter 发送
                      </button>
                    </div>
                    <span class="empty chat-send-hint">{{ chatSendHint }}</span>
                  </div>
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
                  <thead><tr><th>状态</th><th>来源</th><th>开始时间</th><th>结束时间</th><th>时长</th><th>Trace</th><th>错误</th></tr></thead>
                  <tbody>
                    <tr v-if="!execHistory.data.length"><td colspan="7" class="empty">（还没有执行记录 · 点「立即触发」跑一次）</td></tr>
                    <tr v-for="e in execHistory.data" :key="e.id">
                      <td><span :class="['exec-badge', e.status.toLowerCase()]">{{ execStatusLabel(e.status) }}</span></td>
                      <td>{{ e.source === 'schedule' ? '定时' : '手动' }}</td>
                      <td class="mono">{{ fmtTime(e.startedAt) }}</td>
                      <td class="mono">{{ fmtTime(e.endedAt) }}</td>
                      <td class="mono">{{ fmtDuration(e.durationMs) }}</td>
                      <td class="mono trace-cell">{{ e.traceId || '—' }}</td>
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
                <thead><tr><th>name</th><th>description</th><th>provider</th><th>model</th><th>tools</th><th>定时</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-if="!agents.data.length"><td colspan="7" class="empty">（暂无 Agent · 点上面「新建 Agent」，或往 .oryxos/agents/ 丢一个目录）</td></tr>
                  <tr v-for="a in agents.data" :key="a.name">
                    <td class="mono">{{ a.name }}</td>
                    <td>{{ a.description || '—' }}</td>
                    <td>{{ a.provider }}</td>
                    <td>{{ a.model || '—' }}</td>
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

          <!-- 人格库（025）：copy-in 模板库独立成页。内置 12 只读 + 自定义 CRUD；Agent 新建「从人格库导入」只从这里选 -->
          <div v-else-if="active === 'personas'">
            <div class="toolbar">
              <button class="btn btn-primary" @click="openPersonaEditor()">+ 新建人格</button>
            </div>
            <p class="empty" style="margin:4px 0 14px">
              人格库 = copy-in 模板库：Agent 新建页「从人格库导入」选中某人时，源文件原文会被复制进 Agent 定义——之后改了库里的人格，
              不影响已导入的 Agent。内置 12 个随 jar 升级自动更新、永远只读；自定义人格保存在
              <span class="mono">.oryxos/personas/</span>，可改可删、跨重启持久。
            </p>
            <p v-if="personaPageError" class="error">{{ personaPageError }}</p>

            <!-- 新建 / 编辑 / 查看 弹框：源文件原文即库内容（frontmatter name/description/emoji 投影卡片 meta） -->
            <div v-if="personaForm.open" class="modal-overlay" @click.self="cancelPersonaForm()">
              <div class="modal-card">
                <div class="modal-head">
                  <h3>{{ personaForm.viewOnly ? '查看人格' : personaForm.editing ? '编辑人格' : '新建人格' }}</h3>
                  <button class="modal-x" @click="cancelPersonaForm()">✕</button>
                </div>
                <div class="modal-body">
                  <template v-if="!personaForm.viewOnly">
                    <label class="empty" style="display:block;margin:0 0 2px">从本地 .md 文件导入（读入下方文本框；新建时 key 从文件名派生，可改）</label>
                    <input type="file" accept=".md,text/markdown" class="gen-input" @change="onPersonaFile" />
                  </template>
                  <input v-model="personaForm.key" class="gen-input" :disabled="personaForm.editing || personaForm.viewOnly"
                         placeholder="key（字母/数字/下划线/连字符；也是导入时的建议 Agent 名）" />
                  <p v-if="personaForm.busy" class="empty">加载中…</p>
                  <textarea v-else v-model="personaForm.sourceContent" class="gen-draft mono" rows="14" :readonly="personaForm.viewOnly"
                            placeholder="---&#10;name: 你的专家名&#10;description: 一句话描述&#10;emoji: 🎯&#10;---&#10;# 你的专家&#10;你是**专家**…&#10;&#10;## 🧠 身份与记忆&#10;- **角色**：…&#10;- **性格**：…&#10;（agency-agents-zh 人格文件格式；身份段按 角色/性格 等行级关键字解析成 persona 7 字段）"></textarea>
                  <p v-if="personaForm.viewOnly" class="empty">内置人格只读，随 jar 升级自动更新；不能编辑或删除。</p>
                  <p v-else class="empty">导入该人格时，源文件原文会被复制进 Agent 定义（copy-in），改这里不影响已导入的 Agent。</p>
                  <p v-if="personaForm.error" class="error">{{ personaForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelPersonaForm">关闭</button>
                  <button v-if="!personaForm.viewOnly" class="btn btn-primary"
                          :disabled="personaForm.busy || !personaForm.sourceContent.trim() || (!personaForm.editing && !personaForm.key.trim())"
                          @click="savePersonaForm">{{ personaForm.editing ? '保存修改' : '创建' }}</button>
                </div>
              </div>
            </div>

            <p v-if="personaPresets.loading" class="empty">加载中…</p>
            <p v-else-if="personaPresets.error" class="error">出错：{{ personaPresets.error }}</p>
            <table v-else>
              <thead><tr><th>人格</th><th>类型</th><th>key</th><th>描述</th><th>来源</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!personaPresets.data.length"><td colspan="6" class="empty">（暂无自定义人格 · 点「+ 新建人格」开始）</td></tr>
                <tr v-for="p in personaPresets.data" :key="p.key">
                  <td>
                    <span class="persona-cell-name"><span class="preset-emoji">{{ p.emoji }}</span>{{ p.label }}</span>
                  </td>
                  <td class="persona-type">
                    <span class="preset-badge" :class="p.builtin ? 'b-in' : 'b-cu'">{{ p.builtin ? '内置' : '自定义' }}</span>
                  </td>
                  <td class="mono">{{ p.key }}</td>
                  <td>{{ p.description || '—' }}</td>
                  <td class="mono">{{ p.builtin ? (p.sourceFile || 'classpath personas/' + p.key + '.md') : '.oryxos/personas/' + p.key + '.md' }}</td>
                  <td class="ops">
                    <button class="btn" @click="viewPersona(p)">查看</button>
                    <button v-if="!p.builtin" class="btn" @click="editPersona(p)">编辑</button>
                    <button v-if="!p.builtin" class="btn" @click="deletePersona(p)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>

            <!-- 来源注记：放在列表下方作页面底部脚注——新建人格往列表里加的行永远在它上边，提示恒在页底 -->
            <p class="persona-src-foot">
              内置 12 个人格预设的提示词源自 MIT 许可的 agency-agents 中文社区版
              <a href="https://github.com/jnMetaCode/agency-agents-zh" target="_blank" rel="noopener">jnMetaCode/agency-agents-zh</a>
              （上游 <a href="https://github.com/msitarzewski/agency-agents" target="_blank" rel="noopener">msitarzewski/agency-agents</a>，MIT）。
            </p>
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
                    <option value="email">email</option>
                  </select>
                  <input v-if="nc.type !== 'email'" v-model="nc.url" class="gen-input" placeholder="Webhook URL" />
                  <template v-if="nc.type === 'email'">
                    <input v-model="nc.host" class="gen-input" placeholder="SMTP host（如 smtp.example.com）" />
                    <input v-model="nc.port" class="gen-input" placeholder="端口（465/587/25）" />
                    <input v-model="nc.from" class="gen-input" placeholder="发件人（from）" />
                    <input v-model="nc.to" class="gen-input" placeholder="收件人（to，逗号分隔）" />
                    <input v-model="nc.username" class="gen-input" placeholder="用户名（可选）" />
                    <input v-model="nc.password" class="gen-input" type="password" placeholder="密码（建议填 ${SMTP_PASSWORD}，无认证留空）" />
                    <input v-model="nc.subject" class="gen-input" placeholder="主题（可选）" />
                    <select v-model="nc.encryption" class="gen-input">
                      <option value="">加密方式（自动按端口推断）</option>
                      <option value="ssl">ssl</option>
                      <option value="starttls">starttls</option>
                      <option value="none">none</option>
                    </select>
                  </template>
                  <input v-model="nc.description" class="gen-input" placeholder="描述（可选）" />
                  <p class="empty">{{ nc.editing ? '编辑现有渠道，渠道名不可改。' : 'type 支持 feishu / wecom / dingtalk / webhook / email；email 填 SMTP 多字段（密码建议 ${SMTP_PASSWORD} 环境变量占位），其余填 Webhook URL。' }}</p>
                  <p v-if="nc.error" class="error">{{ nc.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelNc">取消</button>
                  <button class="btn btn-primary" :disabled="nc.busy || !nc.name || (nc.type === 'email' ? !(nc.host && nc.port && nc.from && nc.to) : !nc.url)" @click="saveNotifyChannel">{{ nc.editing ? '保存修改' : '创建' }}</button>
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
                  <td class="mono">{{ c.type === 'email' ? (c.config ? c.config.host + ':' + c.config.port : '—') : c.url }}</td>
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
              <thead><tr><th>name</th><th>apiKey</th><th>baseUrl</th><th>description</th><th>连通性</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!providers.data.length"><td colspan="6" class="empty">（暂无 Provider · 点上面「新建 Provider」）</td></tr>
                <tr v-for="p in providers.data" :key="p.name">
                  <td class="mono">{{ p.name }}</td>
                  <td class="mono">{{ p.apiKey || '—' }}</td>
                  <td class="mono">{{ p.baseUrl || '—' }}</td>
                  <td>{{ p.description || '—' }}</td>
                  <td>
                    <span v-if="providerTests[p.name]" :class="providerTests[p.name].ok === false ? 'error' : 'ok'">
                      {{ providerTests[p.name].message }}
                    </span>
                    <span v-else class="empty">未测试</span>
                  </td>
                  <td class="ops">
                    <button class="btn" :disabled="providerTests[p.name]?.loading" @click="testProvider(p.name)">
                      {{ providerTests[p.name]?.loading ? '测试中' : '测试连接' }}
                    </button>
                    <button class="btn" @click="editProvider(p)">编辑</button>
                    <button class="btn" @click="deleteProvider(p.name)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>

            <h3 class="sec">模型定价</h3>
            <div class="toolbar">
              <button class="btn btn-primary" @click="openPricingForm()">+ 新增定价</button>
            </div>
            <div v-if="pricingForm.open" class="modal-overlay" @click.self="cancelPricing()">
              <div class="modal-card">
                <div class="modal-head"><h3>{{ pricingForm.editing ? '编辑模型定价' : '新增模型定价' }}</h3><button class="modal-x" @click="cancelPricing()">✕</button></div>
                <div class="modal-body">
                  <input v-model="pricingForm.provider" class="gen-input" :disabled="!!pricingForm.editing" placeholder="provider 名，如 deepseek" />
                  <input v-model="pricingForm.model" class="gen-input" :disabled="!!pricingForm.editing" placeholder="模型名，如 deepseek-chat" />
                  <input v-model="pricingForm.promptPrice" class="gen-input" placeholder="输入单价（元/百万 token），如 1.0" />
                  <input v-model="pricingForm.completionPrice" class="gen-input" placeholder="输出单价（元/百万 token），如 2.0" />
                  <p class="empty">单价单位「元/百万 token」，留空=未定价（成本记「未计量」）。</p>
                  <p v-if="pricingForm.error" class="error">{{ pricingForm.error }}</p>
                </div>
                <div class="modal-foot">
                  <button class="btn" @click="cancelPricing">取消</button>
                  <button class="btn btn-primary" :disabled="pricingForm.busy || !pricingForm.provider || !pricingForm.model" @click="savePricing">保存</button>
                </div>
              </div>
            </div>
            <p v-if="pricing.loading" class="empty">加载中…</p>
            <p v-else-if="pricing.error" class="error">出错：{{ pricing.error }}</p>
            <table v-else>
              <thead><tr><th>provider</th><th>model</th><th>输入单价</th><th>输出单价</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-if="!pricing.data.length"><td colspan="5" class="empty">（暂无定价 · 点上面「新增定价」）</td></tr>
                <tr v-for="row in pricing.data" :key="row.id">
                  <td class="mono">{{ row.provider }}</td>
                  <td class="mono">{{ row.model }}</td>
                  <td class="mono">{{ row.promptPrice ?? '—' }}</td>
                  <td class="mono">{{ row.completionPrice ?? '—' }}</td>
                  <td class="ops">
                    <button class="btn" @click="openPricingForm(row)">编辑</button>
                    <button class="btn" @click="deletePricing(row.id)">删除</button>
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
                <div class="sess-meta"><span class="mono">{{ execDetail.scheduleId }}</span><span class="empty">执行记录（最近 100 条）</span></div>
                <p v-if="execDetail.loading" class="empty">加载中…</p>
                <p v-else-if="execDetail.error" class="error">出错：{{ execDetail.error }}</p>
                <template v-else-if="execDetail.data">
                  <p v-if="!execDetail.data.length" class="empty">（还没有执行记录 · 点"立即执行"或等 cron 到点）</p>
                  <table v-else>
                    <thead><tr><th>开始时间</th><th>结果</th><th>耗时(ms)</th><th>会话</th><th>错误</th></tr></thead>
                    <tbody>
                      <tr v-for="(e, i) in execDetail.data" :key="i">
                        <td class="mono">{{ e.startedAt }}<span v-if="e.legacyMigrated" class="tag">迁移前历史{{ e.legacyTaskKey ? `: ${e.legacyTaskKey}` : '' }}</span></td>
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
                  <tr v-for="row in state.schedules.data" :key="row.scheduleId">
                    <td v-for="c in cols('schedules')" :key="c" :class="{ mono: c === 'key' || c === 'cron' }">
                      <span v-if="c === 'enabled'" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span>
                      <template v-else>{{ row[c] ?? '—' }}</template>
                    </td>
                    <td class="ops">
                      <button class="btn" :disabled="busy === row.scheduleId" @click="runTask(row.scheduleId)">立即执行</button>
                      <button class="btn" :disabled="busy === row.scheduleId" @click="toggleTask(row)">{{ row.enabled ? '停用' : '启用' }}</button>
                      <button class="btn" @click="openExecutions(row.scheduleId)">执行记录</button>
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
                  <div v-else class="chat" ref="sessionDetailScrollEl">
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
/* trace ID 完整展示（021/023）：小号等宽不换行——截断会让复制变成折磨 */
.trace-cell { font-size: 11px; white-space: nowrap; }

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
.persona-src-foot {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-3);
  border-top: 1px dashed var(--border);
  margin: 16px 0 0;
  padding-top: 10px;
}
.persona-src-foot a { color: var(--brand); text-decoration: none; }
.persona-src-foot a:hover { text-decoration: underline; }
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
.bars { margin-bottom: 24px; }
.bar-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.bar-label { width: 160px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.bar-track { flex: 1; height: 14px; background: var(--bg-mute); border-radius: 7px; overflow: hidden; }
.bar-fill { height: 100%; background: var(--brand); border-radius: 7px; }
.bar-val { width: 140px; flex-shrink: 0; font-size: 12px; color: var(--text-2); }
.bar-row.clickable { cursor: pointer; }
.bar-row.clickable:hover { opacity: 0.85; }
.bar-row.on .bar-label { color: var(--brand); font-weight: 600; }
.filter-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 16px; padding: 8px 12px; background: var(--brand-soft); border-radius: 6px; }
.pager { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.pager select { background: var(--bg-soft); border: 1px solid var(--border); border-radius: 6px; color: var(--text-1); padding: 4px 8px; }
.pager .btn:disabled { opacity: 0.4; cursor: not-allowed; }
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
/* 键盘焦点可见性：全站交互控件统一的 focus-visible 描边（hover 已有，focus 不能缺） */
.btn:focus-visible, .md-seg:focus-visible, .gen-input:focus-visible, .gen-draft:focus-visible, .skill-opt:focus-within {
  outline: 1px solid var(--brand); outline-offset: 1px;
}
/* 绑定一致性告警：不常驻，仅变更后回检发现问题时展开，可关闭 */
.issue-banner { border: 1px solid var(--warn); background: var(--bg-soft); border-radius: var(--radius); padding: 10px 12px; margin: 0 0 14px; }
.issue-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.issue-title { color: var(--warn); font-size: 13px; font-weight: 600; }
.issue-list { list-style: none; margin: 0; padding: 0; }
.issue-list li { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; padding: 5px 0; border-top: 1px solid var(--border); font-size: 12px; }
.issue-list li:first-child { border-top: none; }
.issue-type { color: var(--warn); }
.skill-picker { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.skill-opt { display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px; border: 1px solid var(--border); border-radius: 6px; font-size: 12px; cursor: pointer; }
.skill-opt:hover { border-color: var(--brand); }
/* 028-agent-skill-filter：搜索框横铺、批量与隐藏提示整行 */
.skill-search { flex: 1 1 100%; margin-bottom: 2px; }
.skill-batch { flex: 1 1 100%; display: flex; gap: 8px; }
.skill-batch .btn { padding: 2px 8px; font-size: 12px; }
.skill-hidden-hint { flex: 1 1 100%; display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-dim, #888); }
.skill-hidden-hint .btn { padding: 1px 8px; font-size: 12px; }
.skill-opt.skill-hidden { opacity: 0.65; border-style: dashed; } /* 被筛选隐藏、临时纳入视野的已选项 */
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
.chat-send-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; margin-top: 8px; }
.send-mode-toggle { margin-bottom: 0; }
.chat-send-hint { font-size: 12px; }

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
/* 人格库（025）：预设卡片（emoji + label + 描述），Agent 导入页选中高亮；人格库页表格复用 emoji/badge */
.preset-opt { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 176px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-soft); color: var(--text-1); text-align: left; cursor: pointer; }
.preset-opt:hover { border-color: var(--brand); }
.preset-opt.on { border-color: var(--brand); background: color-mix(in srgb, var(--brand) 12%, var(--bg-soft)); color: var(--text-1); }
.preset-opt:disabled { opacity: .55; cursor: default; }
.preset-emoji { font-size: 18px; line-height: 1; }
.preset-label { font-size: 13px; font-weight: 600; }
.preset-desc { font-size: 11px; line-height: 1.4; color: var(--text-2); }
.preset-badge { margin-left: 4px; font-size: 10px; font-weight: 400; padding: 1px 6px; border-radius: 999px; vertical-align: 1px; white-space: nowrap; }
.preset-badge.b-in { color: var(--muted, #888); background: rgba(128, 128, 128, 0.15); }
.preset-badge.b-cu { color: var(--ok); background: rgba(34, 197, 94, 0.12); }
/* 人格库页「人格」列表格：人格列 = emoji+label 单行不拆词；类型列单独放「内置/自定义」徽标 */
.persona-cell-name { display: inline-flex; align-items: center; gap: 3px; white-space: nowrap; }
td.persona-type .preset-badge, .persona-cell-name .preset-badge { margin-left: 0; }
.preview-valid { margin-top: 6px; font-size: 12px; }
</style>
