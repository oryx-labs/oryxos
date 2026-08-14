import assert from 'node:assert/strict'
import test from 'node:test'

import { CHAT_NEAR_BOTTOM_THRESHOLD_PX, isNearBottom } from './chat-scroll.js'

test('没有滚动容器时视为需要滚到底部', () => {
  assert.equal(isNearBottom(null), true)
})

test('距离底部不超过阈值时允许自动滚动', () => {
  const atThreshold = {
    scrollHeight: 1000,
    scrollTop: 720,
    clientHeight: 200,
  }

  assert.equal(isNearBottom(atThreshold), true)
  assert.equal(CHAT_NEAR_BOTTOM_THRESHOLD_PX, 80)
})

test('用户离开底部超过阈值时保留阅读位置', () => {
  const aboveThreshold = {
    scrollHeight: 1000,
    scrollTop: 719,
    clientHeight: 200,
  }

  assert.equal(isNearBottom(aboveThreshold), false)
})
