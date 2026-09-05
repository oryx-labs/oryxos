export const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'CANCELLED'])

export const STATUS_LABELS = {
  QUEUED: '正在启动',
  RUNNING: '运行中',
  CANCELLING: '正在停止',
  SUCCESS: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const CONNECTION_LABELS = {
  CONNECTING: '连接中',
  CONNECTED: '已连接',
  RECONNECTING: '重连中',
  DISCONNECTED: '已断开',
  CLOSED: '已结束',
}

export function createWorkbenchState() {
  return {
    run: null,
    answer: '',
    activities: [],
    lastSequence: 0,
    lastAppliedSequence: 0,
    snapshotLastSequence: 0,
    connection: 'CONNECTING',
    hasUnseenUpdates: false,
    loading: true,
    error: null,
    empty: false,
  }
}

export function applySnapshot(state, snapshot) {
  const next = { ...state, loading: false, error: null, empty: false }
  if (!snapshot) {
    next.empty = true
    return next
  }
  next.run = snapshot
  next.snapshotLastSequence = snapshot.lastSequence ?? 0
  return next
}

export function applyEvent(state, event) {
  if (!event || event.sequence == null) {
    return state
  }
  const applied = state.lastAppliedSequence ?? state.lastSequence ?? 0
  if (event.sequence <= applied) {
    return state
  }
  const next = {
    ...state,
    lastAppliedSequence: event.sequence,
    lastSequence: event.sequence,
    activities: [...state.activities],
  }
  const payload = event.payload || {}
  switch (event.type) {
    case 'MESSAGE_CONTENT':
      next.answer = `${state.answer || ''}${payload.delta || ''}`
      upsertActivity(next.activities, {
        id: `message:${payload.messageId || 'run-answer'}`,
        type: 'answer',
        sequence: event.sequence,
        title: '回答',
        status: 'RUNNING',
        createdAt: event.createdAt,
      })
      break
    case 'STEP_STARTED':
      upsertActivity(next.activities, {
        id: `step:${payload.step || payload.iteration || event.sequence}`,
        type: 'model',
        sequence: event.sequence,
        title: `模型处理 · 第 ${payload.iteration || payload.step || '?'} 轮`,
        status: 'RUNNING',
        createdAt: event.createdAt,
      })
      break
    case 'STEP_FINISHED':
      upsertActivity(next.activities, {
        id: `step:${payload.step || payload.iteration || event.sequence}`,
        type: 'model',
        sequence: event.sequence,
        title: `模型处理 · 第 ${payload.iteration || payload.step || '?'} 轮`,
        status: 'SUCCESS',
        durationMs: payload.durationMs,
        createdAt: event.createdAt,
      })
      break
    case 'TOOL_CALL_STARTED':
      upsertActivity(next.activities, {
        id: `tool:${payload.toolCallId || event.sequence}`,
        type: 'tool',
        sequence: event.sequence,
        title: payload.toolName || '工具',
        status: 'RUNNING',
        inputSummary: payload.inputSummary,
        createdAt: event.createdAt,
      })
      break
    case 'TOOL_CALL_FINISHED':
      upsertActivity(next.activities, {
        id: `tool:${payload.toolCallId || event.sequence}`,
        type: 'tool',
        sequence: event.sequence,
        title: payload.toolName || '工具',
        status: payload.success ? 'SUCCESS' : 'FAILED',
        inputSummary: payload.inputSummary,
        outputSummary: payload.outputSummary,
        error: payload.error,
        durationMs: payload.durationMs,
        createdAt: event.createdAt,
      })
      break
    case 'RUN_STARTED':
      upsertActivity(next.activities, {
        id: `run:${event.runId}:started`,
        type: 'system',
        sequence: event.sequence,
        title: '任务已开始',
        status: 'RUNNING',
        createdAt: event.createdAt,
      })
      next.run = { ...(next.run || {}), status: 'RUNNING', id: event.runId }
      break
    case 'RUN_FINISHED':
    case 'RUN_FAILED':
    case 'RUN_CANCELLED':
    case 'RUN_CANCELLING': {
      const status =
        event.type === 'RUN_FINISHED'
          ? payload.status || 'SUCCESS'
          : event.type === 'RUN_FAILED'
            ? 'FAILED'
            : event.type === 'RUN_CANCELLED'
              ? 'CANCELLED'
              : 'CANCELLING'
      if (event.type === 'RUN_CANCELLING' && isTerminalStatus(next.run?.status)) {
        break
      }
      upsertActivity(next.activities, {
        id: `run:${event.runId}:${event.type}`,
        type: 'system',
        sequence: event.sequence,
        title: STATUS_LABELS[status] || status,
        status,
        error: payload.error,
        durationMs: payload.durationMs,
        createdAt: event.createdAt,
      })
      next.run = {
        ...(next.run || {}),
        status,
        durationMs: payload.durationMs ?? next.run?.durationMs,
        endedAt: payload.endedAt ?? next.run?.endedAt,
        errorMessage: payload.error ?? next.run?.errorMessage,
        stopReason: payload.stopReason ?? next.run?.stopReason,
        cancellable: status === 'QUEUED' || status === 'RUNNING',
      }
      break
    }
    default:
      upsertActivity(next.activities, {
        id: `evt:${event.sequence}`,
        type: 'system',
        sequence: event.sequence,
        title: event.type,
        status: 'RUNNING',
        createdAt: event.createdAt,
      })
  }
  next.activities.sort((a, b) => a.sequence - b.sequence)
  return next
}

export function applyEvents(state, events) {
  let next = state
  for (const event of events || []) {
    next = applyEvent(next, event)
  }
  return next
}

export function setConnection(state, connection, error = null) {
  return { ...state, connection, error }
}

export function markUnseen(state, hasUnseenUpdates) {
  return { ...state, hasUnseenUpdates }
}

export function isTerminalStatus(status) {
  return TERMINAL_STATUSES.has(status)
}

export function statusLabel(status) {
  return STATUS_LABELS[status] || status || '未知'
}

export function connectionLabel(connection) {
  return CONNECTION_LABELS[connection] || connection || '未知'
}

export function isLiveStatus(status) {
  return status === 'RUNNING' || status === 'QUEUED' || status === 'CANCELLING'
}

export function hasLiveRuns(rows) {
  return (rows || []).some((row) => isLiveStatus(row.status))
}

export function shouldRefreshRunList(rows, pageVisible) {
  return Boolean(pageVisible) && hasLiveRuns(rows)
}

export function elapsedMs(run, now = Date.now()) {
  if (!run) return null
  if (isTerminalStatus(run.status) && run.durationMs != null) return run.durationMs
  if (!run.startedAt) return null
  const end = run.endedAt ? new Date(run.endedAt).getTime() : now
  return end - new Date(run.startedAt).getTime()
}

function definedFields(item) {
  const next = {}
  for (const [key, value] of Object.entries(item || {})) {
    if (value !== undefined) next[key] = value
  }
  return next
}

function upsertActivity(activities, item) {
  const index = activities.findIndex((row) => row.id === item.id)
  if (index < 0) {
    activities.push(item)
    return
  }
  activities[index] = { ...activities[index], ...definedFields(item) }
}
