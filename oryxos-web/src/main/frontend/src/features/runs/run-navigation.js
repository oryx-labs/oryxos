export function runListHash() {
  return '#/runs'
}

export function runHash(runId) {
  return `#/runs/${runId}`
}

export function parseRunNav(hash) {
  const value = String(hash || '')
  const match = value.match(/^#\/runs(?:\/(\d+))?\/?$/)
  if (!match) return null
  return { page: 'runs', runId: match[1] ? Number(match[1]) : null }
}

export function applyRunNav(hash, current = {}) {
  const parsed = parseRunNav(hash)
  if (!parsed) {
    return { ...current, changed: false }
  }
  return {
    page: 'runs',
    runId: parsed.runId,
    changed: current.page !== 'runs' || current.runId !== parsed.runId,
  }
}
