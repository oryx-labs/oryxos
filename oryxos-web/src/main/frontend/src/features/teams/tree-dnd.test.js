import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ORG_DND_MIME,
  TEAM_DND_MIME,
  applyTreeDrop,
  readDragId,
  resolveTreeDrop,
  writeDragId,
} from './tree-dnd.js'

function fakeDataTransfer(seed = {}) {
  const store = { ...seed }
  return {
    effectAllowed: 'none',
    setData(type, value) {
      store[type] = String(value)
    },
    getData(type) {
      return store[type] || ''
    },
  }
}

test('writeDragId / readDragId round-trip per mime', () => {
  const dt = fakeDataTransfer()
  writeDragId(dt, ORG_DND_MIME, 'acme')
  writeDragId(dt, TEAM_DND_MIME, 'eng')
  assert.equal(dt.effectAllowed, 'move')
  assert.equal(readDragId(dt, ORG_DND_MIME), 'acme')
  assert.equal(readDragId(dt, TEAM_DND_MIME), 'eng')
  assert.equal(readDragId(dt, ORG_DND_MIME + '-other'), '')
})

test('resolveTreeDrop: root clears parent', () => {
  assert.deepEqual(resolveTreeDrop({ draggedId: 'eng', mode: 'root' }), {
    skip: false,
    parentId: null,
  })
})

test('resolveTreeDrop: row sets parent; skips self / empty', () => {
  assert.deepEqual(resolveTreeDrop({ draggedId: 'child', targetId: 'parent', mode: 'row' }), {
    skip: false,
    parentId: 'parent',
  })
  assert.deepEqual(resolveTreeDrop({ draggedId: 'x', targetId: 'x', mode: 'row' }), { skip: true })
  assert.deepEqual(resolveTreeDrop({ draggedId: '', targetId: 'p', mode: 'row' }), { skip: true })
  assert.deepEqual(resolveTreeDrop({ draggedId: 'c', targetId: '  ', mode: 'row' }), { skip: true })
})

test('applyTreeDrop calls setParent with parent id then reload', async () => {
  const calls = []
  const result = await applyTreeDrop({
    draggedId: 'child',
    targetId: 'parent',
    mode: 'row',
    setParent: async (id, parentId) => {
      calls.push(['set', id, parentId])
    },
    reload: async () => {
      calls.push(['reload'])
    },
  })
  assert.deepEqual(result, { applied: true, parentId: 'parent' })
  assert.deepEqual(calls, [
    ['set', 'child', 'parent'],
    ['reload'],
  ])
})

test('applyTreeDrop root passes null parentId', async () => {
  const calls = []
  const result = await applyTreeDrop({
    draggedId: 'eng',
    mode: 'root',
    setParent: async (id, parentId) => {
      calls.push([id, parentId])
    },
  })
  assert.deepEqual(result, { applied: true, parentId: null })
  assert.deepEqual(calls, [['eng', null]])
})

test('applyTreeDrop skips no-op without calling setParent', async () => {
  let called = false
  const result = await applyTreeDrop({
    draggedId: 'same',
    targetId: 'same',
    mode: 'row',
    setParent: async () => {
      called = true
    },
  })
  assert.deepEqual(result, { applied: false })
  assert.equal(called, false)
})

test('applyTreeDrop propagates setParent errors (e.g. cycle 400)', async () => {
  await assert.rejects(
    () =>
      applyTreeDrop({
        draggedId: 'a',
        targetId: 'b',
        mode: 'row',
        setParent: async () => {
          throw new Error('cycle detected')
        },
      }),
    /cycle detected/,
  )
})
