import assert from 'node:assert/strict'
import test from 'node:test'

import { createRunListPoller } from './run-list-poll.js'

test('页面不可见或全是终态时不发刷新请求', async () => {
  let calls = 0
  const poller = createRunListPoller({
    getRows: () => [{ status: 'RUNNING' }],
    isPageVisible: () => false,
    refresh: async () => {
      calls += 1
    },
  })
  await poller.tick()
  assert.equal(calls, 0)

  const idle = createRunListPoller({
    getRows: () => [{ status: 'SUCCESS' }, { status: 'FAILED' }],
    isPageVisible: () => true,
    refresh: async () => {
      calls += 1
    },
  })
  await idle.tick()
  assert.equal(calls, 0)
})

test('存在非终态且页面可见时刷新，进行中请求不会并发叠加', async () => {
  let inflight = 0
  let maxInflight = 0
  let calls = 0
  let release
  const gate = new Promise((resolve) => {
    release = resolve
  })
  const poller = createRunListPoller({
    getRows: () => [{ status: 'QUEUED' }],
    isPageVisible: () => true,
    refresh: async () => {
      calls += 1
      inflight += 1
      maxInflight = Math.max(maxInflight, inflight)
      await gate
      inflight -= 1
    },
  })
  const first = poller.tick()
  const second = poller.tick()
  release()
  await Promise.all([first, second])
  assert.equal(calls, 1)
  assert.equal(maxInflight, 1)
})

test('stop 会清掉定时器且不再触发刷新', async () => {
  const timers = new Map()
  let nextId = 1
  let calls = 0
  const poller = createRunListPoller({
    intervalMs: 10,
    setIntervalFn: (fn) => {
      const id = nextId
      nextId += 1
      timers.set(id, fn)
      return id
    },
    clearIntervalFn: (id) => {
      timers.delete(id)
    },
    getRows: () => [{ status: 'RUNNING' }],
    isPageVisible: () => true,
    refresh: async () => {
      calls += 1
    },
  })
  poller.start()
  assert.equal(timers.size, 1)
  poller.stop()
  assert.equal(timers.size, 0)
  await poller.tick()
  assert.equal(calls, 0)
})
