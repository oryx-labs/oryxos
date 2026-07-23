import { request } from './skills.js'

const API_ROOT = '/api/v1/skills'

function memberUrl(skillName) {
  return `${API_ROOT}/${encodeURIComponent(skillName)}`
}

export function listGlobalSkills() {
  return request(API_ROOT)
}

export function getGlobalSkill(skillName) {
  return request(memberUrl(skillName))
}

export function importGlobalSkill(file) {
  const body = new FormData()
  body.append('file', file)
  return request(API_ROOT, { method: 'POST', body })
}

export function updateGlobalSkill(skillName, content) {
  return request(memberUrl(skillName), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
}

export function deleteGlobalSkill(skillName) {
  return request(memberUrl(skillName), { method: 'DELETE' })
}

export function setGlobalSkillAgent(skillName, agentName, associated) {
  const url = `${memberUrl(skillName)}/agents/${encodeURIComponent(agentName)}`
  return request(url, { method: associated ? 'PUT' : 'DELETE' })
}
