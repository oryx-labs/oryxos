import assert from 'node:assert/strict'
import test from 'node:test'

import {
  applyEvent,
  applyEvents,
  applySnapshot,
  createWorkbenchState,
  elapsedMs,
  isTerminalStatus,
  setConnection,
  shouldRefreshRunList,
} from './run-state.js'

test('快照只建立任务元数据，不改连接状态为失败', () => {
  const state = applySnapshot(createWorkbenchState(), {
    id: 9,
    status: 'RUNNING',
    agentName: 'ops',
    lastSequence: 0,
  })
  assert.equal(state.run.id, 9)
  assert.equal(state.loading, false)
  assert.equal(state.connection, 'CONNECTING')
})

test('快照 lastSequence 只是上界，不充当已应用游标', () => {
  const snapped = applySnapshot(createWorkbenchState(), {
    id: 4,
    status: 'RUNNING',
    lastSequence: 500,
  })
  assert.equal(snapped.snapshotLastSequence, 500)
  assert.equal(snapped.lastAppliedSequence, 0)
  const restored = applyEvent(snapped, {
    sequence: 1,
    type: 'MESSAGE_CONTENT',
    payload: { delta: '你好' },
  })
  assert.equal(restored.answer, '你好')
  assert.equal(restored.lastAppliedSequence, 1)
  assert.equal(restored.snapshotLastSequence, 500)
})

test('重复 sequence 不会追加文本或活动', () => {
  const first = applyEvent(createWorkbenchState(), {
    sequence: 1,
    type: 'MESSAGE_CONTENT',
    payload: { delta: '你好' },
  })
  const second = applyEvent(first, {
    sequence: 1,
    type: 'MESSAGE_CONTENT',
    payload: { delta: '你好' },
  })
  assert.equal(second.answer, '你好')
  assert.equal(second.activities.length, first.activities.length)
})

test('文本按顺序追加，工具开始与结束合并为同一活动', () => {
  const state = applyEvents(createWorkbenchState(), [
    { sequence: 1, type: 'RUN_STARTED', runId: 3, payload: {} },
    { sequence: 2, type: 'MESSAGE_CONTENT', payload: { delta: '正在查' } },
    { sequence: 3, type: 'MESSAGE_CONTENT', payload: { delta: '天气' } },
    {
      sequence: 4,
      type: 'TOOL_CALL_STARTED',
      payload: { toolCallId: 'c1', toolName: 'http_get' },
    },
    {
      sequence: 5,
      type: 'TOOL_CALL_FINISHED',
      payload: { toolCallId: 'c1', toolName: 'http_get', success: true, durationMs: 12 },
    },
    { sequence: 6, type: 'RUN_FINISHED', payload: { status: 'SUCCESS', durationMs: 40 } },
  ])
  assert.equal(state.answer, '正在查天气')
  const tools = state.activities.filter((a) => a.type === 'tool')
  assert.equal(tools.length, 1)
  assert.equal(tools[0].status, 'SUCCESS')
  assert.equal(state.run.status, 'SUCCESS')
  assert.equal(isTerminalStatus(state.run.status), true)
})

test('工具结束事件缺字段时保留开始事件里的输入摘要', () => {
  const state = applyEvents(createWorkbenchState(), [
    {
      sequence: 1,
      type: 'TOOL_CALL_STARTED',
      payload: { toolCallId: 'c1', toolName: 'http_get', inputSummary: 'url=https://example' },
    },
    {
      sequence: 2,
      type: 'TOOL_CALL_FINISHED',
      payload: { toolCallId: 'c1', toolName: 'http_get', success: true, durationMs: 9 },
    },
  ])
  assert.equal(state.activities[0].inputSummary, 'url=https://example')
  assert.equal(state.activities[0].status, 'SUCCESS')
})

test('终态耗时冻结为服务端 durationMs，不再随墙钟增长', () => {
  const later = Date.parse('2026-08-23T05:00:00Z')
  assert.equal(
    elapsedMs(
      {
        status: 'FAILED',
        durationMs: 1800,
        startedAt: '2026-08-23T04:00:00Z',
        endedAt: '2026-08-23T04:00:01.800Z',
      },
      later,
    ),
    1800,
  )
  assert.equal(elapsedMs({ status: 'SUCCESS', durationMs: 40 }, later), 40)
})

test('迟到的取消中过渡事件不能把终态回退为 CANCELLING', () => {
  const terminal = applyEvents(createWorkbenchState(), [
    { sequence: 1, runId: 7, type: 'RUN_FINISHED', payload: { durationMs: 12 } },
    { sequence: 2, runId: 7, type: 'RUN_CANCELLING', payload: {} },
  ])

  assert.equal(terminal.run.status, 'SUCCESS')
  assert.equal(terminal.lastAppliedSequence, 2)
})

test('连接状态变化不改写 Run 业务状态', () => {
  const running = applySnapshot(createWorkbenchState(), { id: 1, status: 'RUNNING', lastSequence: 0 })
  const disconnected = setConnection(running, 'DISCONNECTED', '网络中断')
  assert.equal(disconnected.run.status, 'RUNNING')
  assert.equal(disconnected.connection, 'DISCONNECTED')
})

test('乱序重复事件最终保持一致', () => {
  const once = applyEvents(createWorkbenchState(), [
    { sequence: 2, type: 'MESSAGE_CONTENT', payload: { delta: 'B' } },
    { sequence: 1, type: 'MESSAGE_CONTENT', payload: { delta: 'A' } },
    { sequence: 2, type: 'MESSAGE_CONTENT', payload: { delta: 'B' } },
  ])
  assert.equal(once.answer, 'B')
  assert.equal(once.lastSequence, 2)
  assert.equal(once.lastAppliedSequence, 2)
})

test('仅页面可见且存在非终态 Run 时才刷新列表', () => {
  assert.equal(shouldRefreshRunList([{ status: 'RUNNING' }], true), true)
  assert.equal(shouldRefreshRunList([{ status: 'SUCCESS' }], true), false)
  assert.equal(shouldRefreshRunList([{ status: 'QUEUED' }], false), false)
})
