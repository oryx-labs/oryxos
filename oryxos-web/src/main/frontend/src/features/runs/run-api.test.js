import assert from 'node:assert/strict'
import test from 'node:test'

import { listAllRunEvents } from './run-api.js'

test('按 hasMore/nextAfter 循环补齐全部历史页', async () => {
  const pages = {
    0: {
      events: Array.from({ length: 500 }, (_, i) => ({ sequence: i + 1, type: 'MESSAGE_CONTENT' })),
      hasMore: true,
      nextAfter: 500,
    },
    500: {
      events: [{ sequence: 501, type: 'RUN_FINISHED' }],
      hasMore: false,
      nextAfter: 501,
    },
  }
  const seen = []
  const previous = globalThis.fetch
  globalThis.fetch = async (url) => {
    const after = Number(new URL(url, 'http://admin.local').searchParams.get('after'))
    seen.push(after)
    return {
      json: async () => ({ code: 0, data: pages[after] }),
    }
  }
  try {
    const page = await listAllRunEvents(9, 0, 500)
    assert.deepEqual(seen, [0, 500])
    assert.equal(page.events.length, 501)
    assert.equal(page.events[0].sequence, 1)
    assert.equal(page.events[500].sequence, 501)
    assert.equal(page.nextAfter, 501)
    assert.equal(page.hasMore, false)
  } finally {
    globalThis.fetch = previous
  }
})

test('单页历史一次取完后即可用 nextAfter 作为 SSE 游标', async () => {
  const previous = globalThis.fetch
  globalThis.fetch = async () => ({
    json: async () => ({
      code: 0,
      data: { events: [{ sequence: 1, type: 'RUN_STARTED' }], hasMore: false, nextAfter: 1 },
    }),
  })
  try {
    const page = await listAllRunEvents(3, 0)
    assert.equal(page.events.length, 1)
    assert.equal(page.nextAfter, 1)
  } finally {
    globalThis.fetch = previous
  }
})
