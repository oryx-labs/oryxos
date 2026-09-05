import assert from 'node:assert/strict'
import test from 'node:test'

import { applyRunNav, parseRunNav, runHash, runListHash } from './run-navigation.js'

test('解析列表与 Run 详情 hash', () => {
  assert.deepEqual(parseRunNav('#/runs'), { page: 'runs', runId: null })
  assert.deepEqual(parseRunNav('#/runs/12'), { page: 'runs', runId: 12 })
  assert.equal(parseRunNav('#/agents'), null)
  assert.equal(parseRunNav(''), null)
})

test('hash 初始化进入流式管理列表', () => {
  const next = applyRunNav('#/runs', { page: 'overview', runId: null })
  assert.equal(next.page, 'runs')
  assert.equal(next.runId, null)
  assert.equal(next.changed, true)
})

test('hashchange 打开同一 Run，后退回到列表', () => {
  const opened = applyRunNav('#/runs/8', { page: 'runs', runId: null })
  assert.equal(opened.runId, 8)
  assert.equal(opened.changed, true)
  const back = applyRunNav('#/runs', { page: 'runs', runId: 8 })
  assert.equal(back.runId, null)
  assert.equal(back.changed, true)
  const forward = applyRunNav('#/runs/8', { page: 'runs', runId: null })
  assert.equal(forward.runId, 8)
  assert.equal(runHash(8), '#/runs/8')
  assert.equal(runListHash(), '#/runs')
})

test('重复应用同一 hash 不产生变更', () => {
  const next = applyRunNav('#/runs/3', { page: 'runs', runId: 3 })
  assert.equal(next.changed, false)
  assert.equal(next.runId, 3)
})
