const API_ROOT = '/api/v1/skills'

async function request(url, options) {
  const response = await fetch(url, options)
  let envelope
  try {
    envelope = await response.json()
  } catch {
    throw new Error('服务返回了无法解析的响应')
  }
  if (!response.ok || envelope?.code !== 0) {
    throw new Error(envelope?.message || '公共 Skill 请求失败')
  }
  return envelope.data
}

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
