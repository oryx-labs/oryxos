export async function listRuns(status, limit = 100) {
  const query = new URLSearchParams()
  if (status) query.set('status', status)
  if (limit) query.set('limit', String(limit))
  const suffix = query.toString() ? `?${query}` : ''
  return unwrap(await fetch(`/api/v1/runs${suffix}`))
}

export async function getRun(runId) {
  return unwrap(await fetch(`/api/v1/runs/${encodeURIComponent(runId)}`))
}

export async function listRunEvents(runId, after = 0, limit = 500) {
  const res = await fetch(
    `/api/v1/runs/${encodeURIComponent(runId)}/events?after=${after}&limit=${limit}`,
  )
  return unwrap(res)
}

export async function listAllRunEvents(runId, after = 0, limit = 500) {
  const events = []
  let cursor = after
  for (;;) {
    const page = await listRunEvents(runId, cursor, limit)
    const batch = page.events || []
    events.push(...batch)
    if (!page.hasMore) {
      return { events, nextAfter: page.nextAfter ?? cursor, hasMore: false }
    }
    const next = page.nextAfter ?? (batch.length ? batch[batch.length - 1].sequence : cursor)
    if (next === cursor) {
      return { events, nextAfter: next, hasMore: false }
    }
    cursor = next
  }
}

export async function createRun(agentName, content) {
  return unwrap(
    await fetch('/api/v1/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentName, content }),
    }),
  )
}

export async function cancelRun(runId) {
  return unwrap(
    await fetch(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }),
  )
}

export function openRunStream(runId, after, onEvent, onError) {
  const source = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/stream?after=${after}`)
  const types = [
    'RUN_STARTED',
    'STEP_STARTED',
    'MESSAGE_CONTENT',
    'TOOL_CALL_STARTED',
    'TOOL_CALL_FINISHED',
    'STEP_FINISHED',
    'RUN_FINISHED',
    'RUN_FAILED',
    'RUN_CANCELLING',
    'RUN_CANCELLED',
  ]
  for (const type of types) {
    source.addEventListener(type, (event) => {
      try {
        onEvent(JSON.parse(event.data))
      } catch (e) {
        onError?.(e)
      }
    })
  }
  source.onerror = (event) => onError?.(event)
  return source
}

async function unwrap(res) {
  const body = await res.json()
  if (body.code !== 0) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}
