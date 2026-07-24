import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SkillManagementPanel from './SkillManagementPanel.vue'
import * as api from '../api/skills.js'

vi.mock('../api/skills.js', () => ({
  ApiError: class ApiError extends Error {
    constructor(message, status, code, data) {
      super(message)
      this.status = status
      this.code = code
      this.data = data
    }
  },
  listPublicSkills: vi.fn(),
  getPublicSkill: vi.fn(),
  importPublicSkill: vi.fn(),
  setPublicSkillEnabled: vi.fn(),
  deletePublicSkill: vi.fn(),
}))

const weather = {
  name: 'weather',
  description: '天气建议',
  status: 'enabled',
  configuredEnabled: true,
  source: 'upload',
  linkedAgents: ['ops-agent'],
}

async function render(items = [weather]) {
  api.listPublicSkills.mockResolvedValue(items)
  const wrapper = mount(SkillManagementPanel)
  await flushPromises()
  return wrapper
}

describe('SkillManagementPanel', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows the public market, trust warning and global state controls', async () => {
    api.setPublicSkillEnabled.mockResolvedValue({ ...weather, configuredEnabled: false })
    const wrapper = await render()

    expect(wrapper.text()).toContain('公共 Skill 市场')
    expect(wrapper.text()).toContain('导入是管理员的显式信任动作')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('ops-agent')

    await wrapper.get('[data-skill="weather"] [data-action="toggle"]').trigger('click')
    await flushPromises()
    expect(api.setPublicSkillEnabled).toHaveBeenCalledWith('weather', false)
  })

  it('imports a reviewed ZIP into the public market', async () => {
    api.importPublicSkill.mockResolvedValue({ name: 'research' })
    const wrapper = await render([])
    const file = new File(['zip'], 'research.zip', { type: 'application/zip' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await wrapper.get('[data-action="import"]').trigger('click')
    await flushPromises()

    expect(api.importPublicSkill).toHaveBeenCalledWith(file)
    expect(api.listPublicSkills).toHaveBeenCalledTimes(2)
  })

  it('tries normal delete first, then shows linked Agents and force deletes only after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const conflict = {
      reasonCode: 'SKILL_IN_USE',
      skillName: 'weather',
      linkedAgents: ['finance-agent', 'ops-agent'],
    }
    api.deletePublicSkill
      .mockRejectedValueOnce(new api.ApiError('Skill 仍有关联', 409, 409, conflict))
      .mockResolvedValueOnce({ skillName: 'weather', forced: true })
    const wrapper = await render()

    await wrapper.get('[data-skill="weather"] [data-action="delete"]').trigger('click')
    await flushPromises()

    expect(api.deletePublicSkill).toHaveBeenNthCalledWith(1, 'weather', false)
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).toContain('finance-agent')
    expect(dialog.text()).toContain('ops-agent')

    await dialog.get('[data-action="force-delete"]').trigger('click')
    await flushPromises()
    expect(api.deletePublicSkill).toHaveBeenNthCalledWith(2, 'weather', true)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
