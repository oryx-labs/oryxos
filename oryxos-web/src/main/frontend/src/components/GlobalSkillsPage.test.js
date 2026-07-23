import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import GlobalSkillsPage from './GlobalSkillsPage.vue'
import * as api from '../api/globalSkills.js'

vi.mock('../api/globalSkills.js', () => ({
  listGlobalSkills: vi.fn(),
  getGlobalSkill: vi.fn(),
  importGlobalSkill: vi.fn(),
  updateGlobalSkill: vi.fn(),
  deleteGlobalSkill: vi.fn(),
  setGlobalSkillAgent: vi.fn(),
}))

const skill = {
  name: 'weather',
  directoryName: 'weather',
  description: '天气建议',
  status: 'enabled',
  updatedAt: '2026-07-23T00:00:00Z',
}

const detail = {
  skill,
  content: 'original',
  agentNames: [],
  availableAgents: ['ops'],
}

describe('GlobalSkillsPage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('renders empty and error states', async () => {
    api.listGlobalSkills.mockResolvedValue([])
    const empty = mount(GlobalSkillsPage)
    await flushPromises()
    expect(empty.text()).toContain('暂无公共 Skill')

    api.listGlobalSkills.mockRejectedValue(new Error('服务不可用'))
    const failed = mount(GlobalSkillsPage)
    await flushPromises()
    expect(failed.get('[role="alert"]').text()).toContain('服务不可用')
  })

  it('loads, edits and associates a public Skill', async () => {
    api.listGlobalSkills.mockResolvedValue([{ skill, agentNames: [] }])
    api.getGlobalSkill.mockResolvedValue(detail)
    api.updateGlobalSkill.mockResolvedValue({ ...detail, content: 'updated' })
    api.setGlobalSkillAgent.mockResolvedValue({ ...detail, agentNames: ['ops'] })
    const wrapper = mount(GlobalSkillsPage)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('查看')).trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('updated')
    await wrapper.findAll('button').find((button) => button.text() === '保存').trigger('click')
    await flushPromises()
    expect(api.updateGlobalSkill).toHaveBeenCalledWith('weather', 'updated')

    await wrapper.get('input[type="checkbox"]').trigger('change')
    await flushPromises()
    expect(api.setGlobalSkillAgent).toHaveBeenCalledWith('weather', 'ops', true)
  })
})
