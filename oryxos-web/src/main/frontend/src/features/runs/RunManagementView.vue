<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import RunWorkbenchView from './RunWorkbenchView.vue'
import { listRuns } from './run-api.js'
import { createRunListPoller } from './run-list-poll.js'
import { isLiveStatus, statusLabel } from './run-state.js'

const props = defineProps({
  selectedId: { type: [Number, String], default: null },
})
const emit = defineEmits(['open', 'close', 'go-agents'])

const list = ref({ loading: true, error: null, data: [] })
const openId = ref(props.selectedId)
const pageVisible = ref(typeof document === 'undefined' || document.visibilityState !== 'hidden')
let inFlight = false

watch(() => props.selectedId, (id) => { openId.value = id })

const poller = createRunListPoller({
  getRows: () => list.value.data,
  isPageVisible: () => pageVisible.value && !openId.value,
  refresh: () => load({ silent: true }),
})

function onVisibility() {
  pageVisible.value = document.visibilityState !== 'hidden'
}

const sorted = computed(() => {
  const rows = [...(list.value.data || [])]
  const rank = (status) => {
    if (isLiveStatus(status)) return 0
    if (status === 'FAILED') return 1
    return 2
  }
  return rows.sort((a, b) => {
    const d = rank(a.status) - rank(b.status)
    if (d !== 0) return d
    return String(b.startedAt || '').localeCompare(String(a.startedAt || ''))
  })
})

async function load(options = {}) {
  if (inFlight) return
  inFlight = true
  if (!options.silent) {
    list.value = { loading: true, error: null, data: list.value.data || [] }
  }
  try {
    const data = await listRuns()
    list.value = { loading: false, error: null, data: data || [] }
  } catch (e) {
    list.value = { loading: false, error: e.message, data: options.silent ? list.value.data || [] : [] }
  } finally {
    inFlight = false
  }
}

function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

function fmtDuration(row) {
  if (row.durationMs != null) {
    if (row.durationMs < 1000) return `${row.durationMs} ms`
    const s = row.durationMs / 1000
    return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)} 分 ${Math.round(s % 60)} 秒`
  }
  if (!row.startedAt || row.endedAt) return '—'
  const ms = Date.now() - new Date(row.startedAt).getTime()
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

function preview(text) {
  if (!text) return '—'
  return text.length > 48 ? `${text.slice(0, 48)}…` : text
}

function open(id) {
  openId.value = id
  emit('open', id)
}

function onRowKey(event, id) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    open(id)
  }
}

function back() {
  openId.value = null
  emit('close')
  load()
}

onMounted(() => {
  load()
  poller.start()
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  poller.stop()
  document.removeEventListener('visibilitychange', onVisibility)
})
defineExpose({ load })
</script>

<template>
  <div class="runs">
    <RunWorkbenchView v-if="openId" :run-id="openId" @back="back" />
    <template v-else>
      <p class="lede">查看 Agent 任务的实时进度、回答和工具活动。</p>
      <p v-if="list.loading" class="empty">加载中…</p>
      <p v-else-if="list.error" class="error">出错：{{ list.error }}</p>
      <div v-else-if="!list.data.length" class="empty-card">
        <p class="empty-title">还没有任务</p>
        <p class="empty-desc">从 Agent 列表发起一次任务后，会显示在这里。</p>
        <button class="btn btn-primary" @click="emit('go-agents')">前往 Agent 列表</button>
      </div>
      <div v-else class="table-wrap">
        <table class="data">
          <thead>
            <tr>
              <th>状态</th>
              <th>Run ID</th>
              <th>Agent</th>
              <th>来源</th>
              <th>输入</th>
              <th>开始时间</th>
              <th>时长</th>
              <th>最近更新</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in sorted"
              :key="row.id"
              class="clickable"
              tabindex="0"
              @click="open(row.id)"
              @keydown="onRowKey($event, row.id)"
            >
              <td>
                <span :class="['exec-badge', row.status?.toLowerCase()]">
                  <i v-if="isLiveStatus(row.status)" class="pulse" />
                  {{ statusLabel(row.status) }}
                </span>
              </td>
              <td class="mono">{{ row.id }}</td>
              <td>{{ row.agentName }}</td>
              <td>{{ row.source === 'schedule' ? '定时' : '手动' }}</td>
              <td class="preview">{{ preview(row.inputPreview) }}</td>
              <td class="mono">{{ fmtTime(row.startedAt) }}</td>
              <td class="mono">{{ fmtDuration(row) }}</td>
              <td class="mono">{{ fmtTime(row.updatedAt) }}</td>
              <td class="ops"><button class="btn" @click.stop="open(row.id)">打开</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.lede {
  margin: -4px 0 18px;
  color: var(--text-2);
  font-size: 13px;
}
.empty-card {
  max-width: 420px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 28px 24px;
}
.empty-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 600;
}
.empty-desc {
  margin: 0 0 18px;
  color: var(--text-2);
  line-height: 1.6;
}
.table-wrap { overflow-x: auto; }
.preview {
  max-width: 220px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ops { white-space: nowrap; }
</style>
