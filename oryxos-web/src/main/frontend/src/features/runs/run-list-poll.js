import { shouldRefreshRunList } from './run-state.js'

export const RUN_LIST_POLL_MS = 3000

export function createRunListPoller(options) {
  const intervalMs = options.intervalMs ?? RUN_LIST_POLL_MS
  const setIntervalFn = options.setIntervalFn ?? setInterval
  const clearIntervalFn = options.clearIntervalFn ?? clearInterval
  let timer = null
  let inflight = false
  let stopped = false

  async function tick() {
    if (stopped || inflight) return
    if (!shouldRefreshRunList(options.getRows?.() || [], options.isPageVisible?.())) {
      return
    }
    inflight = true
    try {
      await options.refresh()
    } finally {
      inflight = false
    }
  }

  function start() {
    stop()
    stopped = false
    timer = setIntervalFn(() => {
      tick()
    }, intervalMs)
  }

  function stop() {
    stopped = true
    if (timer != null) {
      clearIntervalFn(timer)
      timer = null
    }
  }

  return { start, stop, tick }
}
