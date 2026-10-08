import assert from 'node:assert/strict'
import test from 'node:test'

import { providerApiKeyMissing } from './provider-form.js'

test('非 mock 缺 api-key 视为不完整', () => {
  assert.equal(providerApiKeyMissing('kimi', ''), true)
  assert.equal(providerApiKeyMissing('kimi', '   '), true)
  assert.equal(providerApiKeyMissing('kimi', null), true)
  assert.equal(providerApiKeyMissing('kimi', undefined), true)
})

test('非 mock 填了 api-key 视为完整', () => {
  assert.equal(providerApiKeyMissing('kimi', 'sk-real'), false)
  assert.equal(providerApiKeyMissing('kimi', '****alue'), false)
})

test('mock 免 api-key', () => {
  assert.equal(providerApiKeyMissing('mock', ''), false)
  assert.equal(providerApiKeyMissing('mock', null), false)
})

test('名与非 mock 的大小写/空白不做归一（与后端 MOCK.equals 一致）', () => {
  assert.equal(providerApiKeyMissing('Mock', ''), true)
  assert.equal(providerApiKeyMissing(' mock', ''), true)
})
