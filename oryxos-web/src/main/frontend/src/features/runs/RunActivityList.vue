<script setup>
import { statusLabel } from './run-state.js'

defineProps({
  activities: { type: Array, default: () => [] },
})

const expanded = defineModel('expanded', { type: Object, default: () => ({}) })

function toggle(id) {
  expanded.value = { ...expanded.value, [id]: !expanded.value[id] }
}

function fmtDuration(ms) {
  if (ms == null) return ''
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

function typeLabel(type) {
  if (type === 'tool') return '工具'
  if (type === 'model') return '模型'
  if (type === 'answer') return '回答'
  return '系统'
}
</script>

<template>
  <ol class="acts">
    <li v-for="item in activities" :key="item.id" class="act">
      <div class="act-head">
        <span :class="['dot', item.status?.toLowerCase()]" />
        <span class="type">{{ typeLabel(item.type) }}</span>
        <span class="act-title">{{ item.title }}</span>
        <span class="act-status">{{ statusLabel(item.status) }}</span>
        <span v-if="item.durationMs != null" class="mono dim">{{ fmtDuration(item.durationMs) }}</span>
        <button
          v-if="item.type === 'tool' && (item.inputSummary || item.outputSummary || item.error)"
          class="btn tiny"
          @click="toggle(item.id)"
        >
          {{ expanded[item.id] ? '收起' : '详情' }}
        </button>
      </div>
      <div v-if="expanded[item.id]" class="act-body">
        <p v-if="item.inputSummary"><span class="dim">参数</span> <span class="mono">{{ item.inputSummary }}</span></p>
        <p v-if="item.outputSummary"><span class="dim">结果</span> <span class="mono">{{ item.outputSummary }}</span></p>
        <p v-if="item.error" class="error">{{ item.error }}</p>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.acts { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.act {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 10px 12px;
}
.act-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.act-title { color: var(--text-1); }
.act-status { color: var(--text-2); font-size: 12px; margin-left: auto; }
.type {
  font-size: 11px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0 7px;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-3); flex: none; }
.dot.running { background: var(--brand); }
.dot.success { background: var(--ok); }
.dot.failed { background: var(--err); }
.dot.cancelled, .dot.cancelling { background: var(--warn); }
.dim { color: var(--text-3); }
.error { color: var(--err); }
.act-body { margin-top: 8px; color: var(--text-2); font-size: 13px; }
.act-body p { margin: 0 0 6px; }
.tiny { padding: 2px 8px; font-size: 12px; margin-right: 0; }
</style>
