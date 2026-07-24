const SKILLS_ROOT = '/api/v1/skills'
const AGENTS_ROOT = '/api/v1/agents'

export class ApiError extends Error {
  constructor(message, status, code, data) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.data = data
  }
}

export async function request(url, options) {
  const response = await fetch(url, options)
  let envelope
  try {
    envelope = await response.json()
  } catch {
    throw new ApiError('服务返回了无法解析的响应', response.status, null, null)
  }
  if (!response.ok || envelope?.code !== 0) {
    throw new ApiError(
      envelope?.message || 'Skill 请求失败',
      response.status,
      envelope?.code,
      envelope?.data,
    )
  }
  return envelope.data
}

export const listPublicSkills = () => request(SKILLS_ROOT)
export const getPublicSkill = (skillName) => request(`${SKILLS_ROOT}/${encodeURIComponent(skillName)}`)

export function importPublicSkill(file) {
  const body = new FormData()
  body.append('file', file)
  return request(SKILLS_ROOT, { method: 'POST', body })
}

export function setPublicSkillEnabled(skillName, enabled) {
  return request(`${SKILLS_ROOT}/${encodeURIComponent(skillName)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
}

export function deletePublicSkill(skillName, force = false) {
  const query = force ? '?force=true' : ''
  return request(`${SKILLS_ROOT}/${encodeURIComponent(skillName)}${query}`, { method: 'DELETE' })
}

function associationsRoot(agentName) {
  return `${AGENTS_ROOT}/${encodeURIComponent(agentName)}/skills`
}

export const listAgentSkills = (agentName) => request(associationsRoot(agentName))
export const associateAgentSkill = (agentName, skillName) =>
  request(`${associationsRoot(agentName)}/${encodeURIComponent(skillName)}`, { method: 'PUT' })
export const unlinkAgentSkill = (agentName, skillName) =>
  request(`${associationsRoot(agentName)}/${encodeURIComponent(skillName)}`, { method: 'DELETE' })

// Compatibility aliases used by older focused tests.
export const listSkills = listAgentSkills
export const getSkill = (_agentName, skillName) => getPublicSkill(skillName)
export const importSkill = (_agentName, file) => importPublicSkill(file)
export const setSkillEnabled = (_agentName, skillName, enabled) => setPublicSkillEnabled(skillName, enabled)
export const deleteSkill = (_agentName, skillName) => unlinkAgentSkill(_agentName, skillName)
