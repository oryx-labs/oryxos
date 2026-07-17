<script setup>
import { ref, reactive, computed } from 'vue'
import logoUrl from './assets/logo.svg'

// 顶部概览 + 五个只读页。管理台只读，无任何写操作入口（26 节）。
const NAV = [
  { key: 'overview', label: '概览' },
  { key: 'profiles', label: 'Agent', path: '/api/v1/profiles' },
  {
    key: 'providers',
    label: 'Provider',
    path: '/api/v1/info',
    // /info 返回 {application, providers:[名字…]}，投影成列表行
    transform: (d) => (d?.providers ?? []).map((name) => ({ name, status: '已配置' })),
  },
  { key: 'tools', label: 'Tool', path: '/api/v1/tools' },
  { key: 'whitelist', label: 'Sandbox 白名单' },
  { key: 'memory', label: '长期记忆', path: '/api/v1/memory' },
  { key: 'info', label: '运行状态', path: '/api/v1/info' },
  { key: 'schedules', label: '定时任务', path: '/api/v1/schedules' },
  { key: 'sessions', label: '会话', path: '/api/v1/sessions' },
]

const active = ref('overview')
const state = reactive({}) // key -> {loading, error, data}
// 当前激活页（只渲染这一页，避免 v-show + v-for 的块补丁陷阱导致切不动）
const current = computed(() => NAV.find((n) => n.key === active.value) ?? NAV[0])

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

// 表格列定义（profiles / tools）。放在 setup 里，模板直接可用。
function cols(key) {
  if (key === 'profiles') return ['name', 'description', 'provider', 'model', 'tools', 'skills']
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
  if (NAV.find((n) => n.key === key)?.path && !state[key]) load(key)
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
</script>

<template>
  <div class="layout">
    <aside class="nav">
      <div class="brand">
        <img :src="logoUrl" alt="OryxOS" class="logo" />
      </div>
      <button
        v-for="n in NAV"
        :key="n.key"
        :class="['nav-item', { on: active === n.key }]"
        @click="select(n.key)"
      >
        {{ n.label }}
      </button>
      <div class="readonly">只读视图</div>
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

          <p class="note mono">当前为静态预览数据，将逐步接入实时端点（Agent/Tool/会话/Provider）。</p>
        </template>

        <template v-else>
          <h2>{{ current.label }}</h2>

          <!-- Sandbox 白名单：列表占位，内容暂空（待接入端点） -->
          <table v-if="active === 'whitelist'">
            <thead><tr><th>类别</th><th>规则</th></tr></thead>
            <tbody><tr><td colspan="2" class="empty">（暂无数据 · 待接入沙箱白名单端点）</td></tr></tbody>
          </table>
          <p v-else-if="!current.path" class="empty">{{ current.note }}</p>
          <template v-else>
            <p v-if="state[active]?.loading" class="empty">加载中…</p>
          <p v-else-if="state[active]?.error" class="error">出错：{{ state[active].error }}</p>
          <template v-else-if="state[active]?.data != null">
            <!-- memory：纯文本 -->
            <pre v-if="active === 'memory'" class="mono memtext">{{ state[active].data || '（暂无长期记忆）' }}</pre>
            <!-- info：应用 + provider -->
            <div v-else-if="active === 'info'">
              <p>应用：<b>{{ state[active].data.application }}</b></p>
              <p>Provider：
                <span v-for="p in state[active].data.providers" :key="p" class="tag">{{ p }}</span>
                <span v-if="!state[active].data.providers?.length" class="empty">（无）</span>
              </p>
            </div>
            <!-- schedules：定时任务，带管理动作（立即执行 / 启用停用 / 执行记录）——28 节，管理台可管 -->
            <template v-else-if="active === 'schedules'">
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
@media (max-width: 640px) { .layout { flex-direction: column; } .nav { width: auto; flex-direction: row; flex-wrap: wrap; } .readonly { display: none; } }
</style>
