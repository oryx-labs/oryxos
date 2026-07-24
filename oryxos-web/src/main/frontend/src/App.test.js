import { describe, expect, it } from 'vitest'

import appSource from './App.vue?raw'

describe('Skill management wiring in App', () => {
  it('exposes the public market and the real Agent association tab', () => {
    expect(appSource).toContain("import SkillManagementPanel from './components/SkillManagementPanel.vue'")
    expect(appSource).toContain('<SkillManagementPanel />')
    expect(appSource).toContain('<AgentSkillsTab')
  })

  it('sends selected public Skill names on both direct create and generated-file save', () => {
    expect(appSource).toContain('skills: agentCreate.skills')
    expect(appSource).toContain('创建真实关联软链接')
    expect(appSource).toContain('未勾选不会自动关联')
  })
})
