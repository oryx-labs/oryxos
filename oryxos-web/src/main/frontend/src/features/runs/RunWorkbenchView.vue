<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { isNearBottom } from '../../chat-scroll.js'
import RunActivityList from './RunActivityList.vue'
import { cancelRun, getRun, listAllRunEvents, openRunStream } from './run-api.js'
import {
  applyEvent,
  applyEvents,
  applySnapshot,
  connectionLabel,
  createWorkbenchState,
  elapsedMs,
  isLiveStatus,
  isTerminalStatus,
  markUnseen,
  setConnection,
  statusLabel,
} from './run-state.js'

const props = defineProps({
  runId: { type: [Number, String], required: true },
})
const emit = defineEmits(['back'])

const state = ref(createWorkbenchState())
const scrollEl = ref(null)
const expanded = ref({})
const elapsed = ref('—')
let source = null
let timer = null
let reconnects = 0

const renderedAnswer = computed(() => {
  const text = state.value.answer
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text))
})

function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

function fmtDuration(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  const s = ms / 1000
  return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)} 分 ${Math.round(s % 60)} 秒`
}

function updateElapsed() {
  elapsed.value = fmtDuration(elapsedMs(state.value.run))
}

async function followIfNeeded(shouldFollow) {
  await nextTick()
  const el = scrollEl.value
  if (el && shouldFollow) {
    el.scrollTop = el.scrollHeight
    state.value = markUnseen(state.value, false)
  } else if (el) {
    state.value = markUnseen(state.value, true)
  }
}

function jumpToLatest() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
  state.value = markUnseen(state.value, false)
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

function startStream() {
  closeStream()
  state.value = setConnection(state.value, 'CONNECTING')
  source = openRunStream(
    props.runId,
    state.value.lastAppliedSequence ?? state.value.lastSequence,
    (event) => {
      reconnects = 0
      const shouldFollow = isNearBottom(scrollEl.value)
      state.value = setConnection(applyEvent(state.value, event), 'CONNECTED')
      followIfNeeded(shouldFollow)
      if (isTerminalStatus(state.value.run?.status)) {
        closeStream()
        state.value = setConnection(state.value, 'CLOSED')
      }
    },
    () => {
      if (isTerminalStatus(state.value.run?.status)) {
        closeStream()
        state.value = setConnection(state.value, 'CLOSED')
        return
      }
      reconnects += 1
      if (reconnects >= 5) {
        closeStream()
        state.value = setConnection(state.value, 'DISCONNECTED', '实时连接中断，可手动重试')
        return
      }
      state.value = setConnection(state.value, 'RECONNECTING')
    },
  )
}

async function load() {
  state.value = { ...createWorkbenchState(), loading: true }
  try {
    const snapshot = await getRun(props.runId)
    let next = applySnapshot(createWorkbenchState(), snapshot)
    const page = await listAllRunEvents(props.runId, 0)
    next = applyEvents(next, page.events || [])
    state.value = setConnection(next, 'CONNECTING')
    if (!isTerminalStatus(next.run?.status)) {
      startStream()
    } else {
      state.value = setConnection(state.value, 'CLOSED')
    }
  } catch (e) {
    state.value = { ...state.value, loading: false, error: e.message }
  }
}

async function stop() {
  if (!confirm('停止后不会撤销已经完成的外部操作。确定停止？')) return
  try {
    const snapshot = await cancelRun(props.runId)
    state.value = applySnapshot(state.value, snapshot)
  } catch (e) {
    state.value = { ...state.value, error: e.message }
  }
}

onMounted(() => {
  load()
  timer = setInterval(updateElapsed, 1000)
})
onBeforeUnmount(() => {
  closeStream()
  if (timer) clearInterval(timer)
})
watch(() => props.runId, () => load())
watch(state, updateElapsed, { deep: true })
</script>

<template>
  <div class="wb">
    <div class="toolbar">
      <button class="btn" @click="emit('back')">← 返回列表</button>
      <div class="spacer" />
      <button class="btn" @click="load()">重新加载</button>
      <button
        v-if="state.run?.cancellable"
        class="btn btn-danger"
        @click="stop"
      >停止任务</button>
    </div>

    <p v-if="state.loading" class="empty">加载中…</p>
    <p v-else-if="state.error && !state.run" class="error">出错：{{ state.error }}</p>
    <template v-else-if="state.run">
      <header class="meta">
        <div class="meta-top">
          <span :class="['exec-badge', state.run.status?.toLowerCase()]">
            <i v-if="isLiveStatus(state.run.status)" class="pulse" />
            {{ statusLabel(state.run.status) }}
          </span>
          <strong>{{ state.run.agentName }}</strong>
          <span class="mono dim">#{{ state.run.id }}</span>
          <span :class="['conn', state.connection?.toLowerCase()]">
            {{ connectionLabel(state.connection) }}
          </span>
        </div>
        <dl class="meta-grid">
          <div><dt>来源</dt><dd>{{ state.run.source === 'schedule' ? '定时' : '手动' }}</dd></div>
          <div><dt>开始</dt><dd class="mono">{{ fmtTime(state.run.startedAt) }}</dd></div>
          <div><dt>持续</dt><dd class="mono">{{ elapsed }}</dd></div>
          <div><dt>更新</dt><dd class="mono">{{ fmtTime(state.run.updatedAt) }}</dd></div>
        </dl>
        <p v-if="state.run.inputPreview" class="preview">{{ state.run.inputPreview }}</p>
        <p v-if="state.run.stopReason" class="preview">原因：{{ state.run.stopReason }}</p>
      </header>
      <p v-if="state.error" class="error">{{ state.error }}</p>
      <p v-if="state.run.errorMessage" class="error">{{ state.run.errorMessage }}</p>

      <section class="panel">
        <h3>回答</h3>
        <div class="answer-wrap">
          <div ref="scrollEl" class="answer-body">
            <div v-if="renderedAnswer" class="md" v-html="renderedAnswer" />
            <p v-else class="empty">等待 Agent 回答…</p>
          </div>
          <button v-if="state.hasUnseenUpdates" class="btn btn-primary jump" @click="jumpToLatest">
            有新进展
          </button>
        </div>
      </section>

      <section class="panel">
        <h3>活动</h3>
        <p v-if="!state.activities.length" class="empty">暂无活动</p>
        <RunActivityList v-else v-model:expanded="expanded" :activities="state.activities" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.wb { display: flex; flex-direction: column; gap: 18px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.spacer { flex: 1; }
.meta {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px 16px;
}
.meta-top { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.meta-top strong { font-weight: 600; }
.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px 16px;
  margin: 12px 0 0;
}
.meta-grid dt { color: var(--text-3); font-size: 12px; margin-bottom: 2px; }
.meta-grid dd { margin: 0; color: var(--text-1); }
.preview { margin: 12px 0 0; color: var(--text-2); line-height: 1.5; }
.dim { color: var(--text-3); }
.conn {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}
.conn.connected { color: var(--ok); }
.conn.connecting, .conn.reconnecting { color: var(--warn); }
.conn.disconnected { color: var(--err); }
.panel h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--text-2);
  font-weight: 600;
}
.answer-wrap { position: relative; }
.answer-body {
  max-height: 420px;
  overflow: auto;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px 20px;
  line-height: 1.7;
}
.jump {
  position: sticky;
  bottom: 12px;
  left: 50%;
  transform: translateX(-50%);
  margin: 0;
}
.md :deep(> :first-child) { margin-top: 0; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { color: var(--text-1); font-weight: 600; }
.md :deep(a) { color: var(--brand); }
.md :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.88em;
  background: var(--bg-mute);
  padding: 1px 5px;
  border-radius: 4px;
}
.md :deep(pre) {
  overflow: auto;
  font-family: var(--font-mono);
  background: var(--bg-mute);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px 14px;
}
@media (max-width: 640px) {
  .conn { margin-left: 0; }
}
</style>
