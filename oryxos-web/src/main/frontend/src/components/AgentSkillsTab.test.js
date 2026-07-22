import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AgentSkillsTab from './AgentSkillsTab.vue'
import * as api from '../api/skills.js'

vi.mock('../api/skills.js', () => ({
  listSkills: vi.fn(),
  getSkill: vi.fn(),
  importSkill: vi.fn(),
  setSkillEnabled: vi.fn(),
  deleteSkill: vi.fn(),
}))

const enabled = {
  name: 'weather',
  directoryName: 'weather',
  description: '天气建议',
  status: 'enabled',
  configuredEnabled: true,
  catalogIncluded: true,
  source: 'upload',
  updatedAt: '2026-07-22T10:30:00Z',
  validationError: null,
}

const invalid = {
  name: 'broken',
  directoryName: 'broken',
  description: null,
  status: 'invalid',
  configuredEnabled: false,
  catalogIncluded: false,
  source: 'workspace',
  updatedAt: '2026-07-22T10:31:00Z',
  validationError: { code: 'INVALID_YAML', message: 'frontmatter 无效' },
}

const disabled = {
  ...enabled,
  name: 'research',
  directoryName: 'research',
  description: '资料检索',
  status: 'disabled',
  configuredEnabled: false,
  catalogIncluded: false,
  source: 'workspace',
}

const disabledWeather = {
  ...enabled,
  status: 'disabled',
  configuredEnabled: false,
  catalogIncluded: false,
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

async function render(items = [enabled, disabled, invalid]) {
  api.listSkills.mockResolvedValue(items)
  const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
  await flushPromises()
  return wrapper
}

describe('AgentSkillsTab', () => {
  beforeEach(() => vi.resetAllMocks())

  it('renders loading, empty and collection error states without a blank screen', async () => {
    let resolve
    api.listSkills.mockReturnValue(new Promise((done) => { resolve = done }))
    const loading = mount(AgentSkillsTab, { props: { agentName: 'ops' } })
    expect(loading.get('[data-testid="skills-loading"]').text()).toContain('加载中')
    resolve([])
    await flushPromises()
    expect(loading.get('[data-testid="skills-empty"]').text()).toContain('暂无 Skill')

    api.listSkills.mockRejectedValue(new Error('服务不可用'))
    const failed = mount(AgentSkillsTab, { props: { agentName: 'other' } })
    await flushPromises()
    expect(failed.get('[role="alert"]').text()).toContain('服务不可用')
    expect(failed.find('[data-testid="skills-empty"]').exists()).toBe(false)
  })

  it('shows all three states, source, catalog inclusion and validation errors', async () => {
    const wrapper = await render()

    expect(wrapper.text()).toContain('Skill 等同代码，仅导入已审查来源')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已启用')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('上传导入')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已进入 L1')
    expect(wrapper.get('[data-skill="research"]').text()).toContain('已禁用')
    expect(wrapper.get('[data-skill="research"]').text()).toContain('工作区')
    expect(wrapper.get('[data-skill="broken"]').text()).toContain('校验失败')
    expect(wrapper.get('[data-skill="broken"]').text()).toContain('frontmatter 无效')
  })

  it('loads and renders details and resource statistics on demand', async () => {
    api.getSkill.mockResolvedValue({
      ...enabled,
      entrypoint: 'skills/weather/SKILL.md',
      license: 'Apache-2.0',
      compatibility: 'Requires read_file',
      metadata: { author: 'team' },
      allowedTools: 'read_file shell',
      resources: ['SKILL.md', 'references/api.md'],
      fileCount: 2,
      totalBytes: 4812,
    })
    const wrapper = await render([enabled])

    await wrapper.get('[data-action="detail"]').trigger('click')
    await flushPromises()

    const detail = wrapper.get('[data-testid="skill-detail"]')
    expect(api.getSkill).toHaveBeenCalledWith('ops-agent', 'weather')
    expect(detail.text()).toContain('skills/weather/SKILL.md')
    expect(detail.text()).toContain('Apache-2.0')
    expect(detail.text()).toContain('references/api.md')
    expect(detail.text()).toContain('2 个文件')
    expect(detail.text()).toContain('4,812 bytes')
  })

  it('blocks duplicate uploads, refreshes only after success and preserves rows on failure', async () => {
    let finish
    api.importSkill.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = await render([enabled])
    const file = new File(['zip'], 'new.zip', { type: 'application/zip' })
    Object.defineProperty(wrapper.get('input[type="file"]').element, 'files', { value: [file] })
    await wrapper.get('input[type="file"]').trigger('change')

    const upload = wrapper.get('[data-action="import"]')
    await upload.trigger('click')
    await upload.trigger('click')
    expect(api.importSkill).toHaveBeenCalledTimes(1)
    expect(upload.attributes('disabled')).toBeDefined()
    finish({ directoryName: 'new' })
    await flushPromises()
    expect(api.listSkills).toHaveBeenCalledTimes(2)

    api.importSkill.mockRejectedValue(new Error('包格式错误'))
    await wrapper.get('input[type="file"]').trigger('change')
    await upload.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('包格式错误')
    expect(wrapper.get('[data-skill="weather"]').exists()).toBe(true)
  })

  it('uses per-row busy state and never changes status optimistically', async () => {
    let finish
    api.setSkillEnabled.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = await render([enabled])
    const toggle = wrapper.get('[data-action="toggle"]')

    await toggle.trigger('click')
    await toggle.trigger('click')
    expect(api.setSkillEnabled).toHaveBeenCalledTimes(1)
    expect(api.setSkillEnabled).toHaveBeenCalledWith('ops-agent', 'weather', false)
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已启用')
    expect(toggle.attributes('disabled')).toBeDefined()

    finish({ ...enabled, status: 'disabled' })
    await flushPromises()
    expect(api.listSkills).toHaveBeenCalledTimes(2)
  })

  it('preserves the row and shows the server message when a toggle fails', async () => {
    api.setSkillEnabled.mockRejectedValue(new Error('Skill 已损坏'))
    const wrapper = await render([enabled])

    await wrapper.get('[data-action="toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Skill 已损坏')
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已启用')
  })

  it('keeps the confirmed toggle result when the reconciliation refresh fails', async () => {
    api.listSkills
      .mockResolvedValueOnce([enabled])
      .mockRejectedValueOnce(new Error('刷新失败'))
    api.setSkillEnabled.mockResolvedValue(disabledWeather)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()

    await wrapper.get('[data-action="toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已禁用')
    expect(wrapper.get('[role="alert"]').text()).toContain('刷新失败')
  })

  it('keeps a confirmed import when the reconciliation refresh fails', async () => {
    const imported = { ...enabled, name: 'new-skill', directoryName: 'new-skill' }
    api.listSkills.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('刷新失败'))
    api.importSkill.mockResolvedValue(imported)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()
    const file = new File(['zip'], 'new-skill.zip', { type: 'application/zip' })
    Object.defineProperty(wrapper.get('input[type="file"]').element, 'files', { value: [file] })
    await wrapper.get('input[type="file"]').trigger('change')

    await wrapper.get('[data-action="import"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-skill="new-skill"]').text()).toContain('已启用')
    expect(wrapper.get('[role="alert"]').text()).toContain('刷新失败')
  })

  it('requires delete confirmation, waits for success and preserves the row on failure', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    let finish
    api.deleteSkill.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = await render([enabled])
    const remove = wrapper.get('[data-action="delete"]')

    await remove.trigger('click')
    expect(api.deleteSkill).not.toHaveBeenCalled()
    await remove.trigger('click')
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('归档'))
    expect(wrapper.get('[data-skill="weather"]').exists()).toBe(true)
    finish(null)
    await flushPromises()
    expect(api.listSkills).toHaveBeenCalledTimes(2)

    confirm.mockReturnValue(true)
    api.deleteSkill.mockRejectedValue(new Error('归档失败'))
    await wrapper.get('[data-action="delete"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('归档失败')
    expect(wrapper.get('[data-skill="weather"]').exists()).toBe(true)
  })

  it('keeps a confirmed deletion removed when the reconciliation refresh fails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.listSkills
      .mockResolvedValueOnce([enabled])
      .mockRejectedValueOnce(new Error('刷新失败'))
    api.deleteSkill.mockResolvedValue(null)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()

    await wrapper.get('[data-action="delete"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-skill="weather"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('刷新失败')
  })

  it('does not resurrect a stale detail after a mutation', async () => {
    const pendingDetail = deferred()
    api.listSkills.mockResolvedValueOnce([enabled]).mockResolvedValueOnce([disabledWeather])
    api.getSkill.mockReturnValue(pendingDetail.promise)
    api.setSkillEnabled.mockResolvedValue(disabledWeather)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()

    await wrapper.get('[data-action="detail"]').trigger('click')
    await wrapper.get('[data-action="toggle"]').trigger('click')
    await flushPromises()
    pendingDetail.resolve({ ...enabled, entrypoint: 'skills/weather/SKILL.md', resources: [] })
    await flushPromises()

    expect(wrapper.find('[data-testid="skill-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-skill="weather"]').text()).toContain('已禁用')
  })

  it('does not resurrect a stale detail after a manual refresh', async () => {
    const pendingDetail = deferred()
    api.listSkills.mockResolvedValueOnce([enabled]).mockResolvedValueOnce([enabled])
    api.getSkill.mockReturnValue(pendingDetail.promise)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()

    await wrapper.get('[data-action="detail"]').trigger('click')
    await wrapper.get('[data-action="refresh"]').trigger('click')
    await flushPromises()
    pendingDetail.resolve({ ...enabled, entrypoint: 'skills/weather/SKILL.md', resources: [] })
    await flushPromises()

    expect(wrapper.find('[data-testid="skill-detail"]').exists()).toBe(false)
  })

  it('clears stale state and reloads when switching Agent', async () => {
    const wrapper = await render([enabled])
    api.listSkills.mockResolvedValue([])

    await wrapper.setProps({ agentName: 'finance-agent' })
    expect(wrapper.find('[data-skill="weather"]').exists()).toBe(false)
    await flushPromises()

    expect(api.listSkills).toHaveBeenLastCalledWith('finance-agent')
    expect(wrapper.get('[data-testid="skills-empty"]').exists()).toBe(true)
  })

  it('does not resurrect an old Agent detail after switching Agent', async () => {
    const pendingDetail = deferred()
    api.listSkills.mockResolvedValueOnce([enabled]).mockResolvedValueOnce([])
    api.getSkill.mockReturnValue(pendingDetail.promise)
    const wrapper = mount(AgentSkillsTab, { props: { agentName: 'ops-agent' } })
    await flushPromises()

    await wrapper.get('[data-action="detail"]').trigger('click')
    await wrapper.setProps({ agentName: 'finance-agent' })
    await flushPromises()
    pendingDetail.resolve({ ...enabled, entrypoint: 'skills/weather/SKILL.md', resources: [] })
    await flushPromises()

    expect(wrapper.find('[data-testid="skill-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="skills-empty"]').exists()).toBe(true)
  })
})
