import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AgentSkillsTab from './AgentSkillsTab.vue'
import * as api from '../api/skills.js'

vi.mock('../api/skills.js', () => ({
  listAgentSkills: vi.fn(),
  listPublicSkills: vi.fn(),
  associateAgentSkill: vi.fn(),
  unlinkAgentSkill: vi.fn(),
}))

const marketSkills = [
  { name: 'research', description: '资料检索', status: 'enabled' },
  { name: 'weather', description: '天气建议', status: 'enabled' },
]

const linked = [{
  skillName: 'weather',
  linkStatus: 'valid',
  discoverable: true,
  error: null,
}]

async function render(associations = linked, available = marketSkills) {
  api.listAgentSkills.mockResolvedValue(associations)
  api.listPublicSkills.mockResolvedValue(available)
  const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
  await flushPromises()
  return wrapper
}

describe('AgentSkillsTab', () => {
  beforeEach(() => vi.resetAllMocks())

  it('derives the UI from actual links and the public market', async () => {
    const wrapper = await render()

    expect(api.listAgentSkills).toHaveBeenCalledWith('ops-agent')
    expect(wrapper.text()).toContain('不读取或改写 AGENT.md')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('valid')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已进入下一请求 L1')
    expect(wrapper.get('[data-skill="research"]').text()).toContain('未关联')
  })

  it('creates and removes associations through the canonical endpoints', async () => {
    api.associateAgentSkill.mockResolvedValue({ skillName: 'research' })
    api.unlinkAgentSkill.mockResolvedValue(null)
    const wrapper = await render()

    await wrapper.get('[data-skill="research"] [data-action="association"]').trigger('click')
    await flushPromises()
    expect(api.associateAgentSkill).toHaveBeenCalledWith('ops-agent', 'research')

    await wrapper.get('[data-skill="weather"] [data-action="association"]').trigger('click')
    await flushPromises()
    expect(api.unlinkAgentSkill).toHaveBeenCalledWith('ops-agent', 'weather')
  })

  it('shows invalid links but disables unsafe mutation from the row', async () => {
    const wrapper = await render([{
      skillName: 'weather',
      linkStatus: 'invalid',
      discoverable: false,
      error: { code: 'INVALID_SKILL_LINK', message: '不是标准相对链接' },
    }])

    const row = wrapper.get('[data-skill="weather"]')
    expect(row.text()).toContain('INVALID_SKILL_LINK')
    expect(row.text()).toContain('不可发现')
    expect(row.get('[data-action="association"]').attributes('disabled')).toBeDefined()
  })

  it('reports collection errors and reloads when switching Agent', async () => {
    api.listAgentSkills.mockRejectedValueOnce(new Error('服务不可用'))
    api.listPublicSkills.mockResolvedValue([])
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('服务不可用')

    api.listAgentSkills.mockResolvedValue([])
    await wrapper.setProps({ agentName: 'finance-agent' })
    await flushPromises()
    expect(api.listAgentSkills).toHaveBeenLastCalledWith('finance-agent')
    expect(wrapper.text()).toContain('暂无可关联的公共 Skill')
  })
})
