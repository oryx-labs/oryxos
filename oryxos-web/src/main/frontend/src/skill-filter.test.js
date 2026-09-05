import assert from 'node:assert/strict'
import test from 'node:test'

import {
  filterSkills,
  hiddenSelectedCount,
  selectAllVisible,
  clearVisible,
  renderSet,
} from './skill-filter.js'

const SKILLS = [
  { name: 'pr-digest', description: '汇总团队 PR' },
  { name: 'git-stats', description: 'Git 仓库统计' },
  { name: 'weather', description: '查天气穿衣' },
  { name: 'news', description: '' }, // 空描述
]

test('filterSkills：空 query 原样返回全部', () => {
  assert.equal(filterSkills(SKILLS, '').length, 4)
  assert.equal(filterSkills(SKILLS, '   ').length, 4)
})

test('filterSkills：去首尾空格 + 不区分大小写，按 name 或 description 命中', () => {
  const byName = filterSkills(SKILLS, 'PR')
  assert.deepEqual(byName.map(s => s.name), ['pr-digest'])
  const byDesc = filterSkills(SKILLS, '统计')
  assert.deepEqual(byDesc.map(s => s.name), ['git-stats'])
  const caseInsensitive = filterSkills(SKILLS, 'WEATHER')
  assert.deepEqual(caseInsensitive.map(s => s.name), ['weather'])
  const trimmed = filterSkills(SKILLS, '  pr  ')
  assert.deepEqual(trimmed.map(s => s.name), ['pr-digest'])
})

test('filterSkills：空 description 仅按 name 匹配不报错', () => {
  const r = filterSkills(SKILLS, 'news')
  assert.deepEqual(r.map(s => s.name), ['news'])
  // 'news' 不应因空 description 误命中其他项
  assert.equal(filterSkills(SKILLS, 'x').length, 0)
})

test('hiddenSelectedCount：统计被筛选隐藏的已选项数', () => {
  const visible = filterSkills(SKILLS, 'pr') // 仅 pr-digest
  assert.equal(hiddenSelectedCount(visible, ['pr-digest', 'git-stats', 'weather']), 2)
  assert.equal(hiddenSelectedCount(visible, ['pr-digest']), 0)
  assert.equal(hiddenSelectedCount(visible, []), 0)
})

test('selectAllVisible：并集去重，视野外已选项不变', () => {
  const visible = filterSkills(SKILLS, 'git') // git-stats
  const r = selectAllVisible(visible, ['weather'])
  assert.deepEqual(r.sort(), ['git-stats', 'weather'])
  // 去重：visible 内已选的不重复
  const r2 = selectAllVisible(visible, ['git-stats', 'weather'])
  assert.deepEqual(r2.sort(), ['git-stats', 'weather'])
})

test('clearVisible：仅移除视野内项，视野外已选项保留', () => {
  const visible = filterSkills(SKILLS, 'git') // git-stats
  const r = clearVisible(visible, ['git-stats', 'weather'])
  assert.deepEqual(r, ['weather'])
})

test('renderSet：showHidden=false 仅返回 visible', () => {
  const visible = filterSkills(SKILLS, 'pr')
  const r = renderSet(visible, SKILLS, ['pr-digest', 'weather'], false)
  assert.deepEqual(r.map(s => s.name), ['pr-digest'])
  assert.equal(r[0].hidden, false)
})

test('renderSet：showHidden=true 把被隐藏的已选项纳入视野并标 hidden', () => {
  const visible = filterSkills(SKILLS, 'pr') // pr-digest
  const r = renderSet(visible, SKILLS, ['pr-digest', 'weather'], true)
  const names = r.map(s => s.name).sort()
  assert.deepEqual(names, ['pr-digest', 'weather'])
  const weather = r.find(s => s.name === 'weather')
  assert.equal(weather.hidden, true)
  const pr = r.find(s => s.name === 'pr-digest')
  assert.equal(pr.hidden, false)
})
