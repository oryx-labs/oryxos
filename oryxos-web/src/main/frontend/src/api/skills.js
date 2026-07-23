const API_ROOT = '/api/v1/agents'

export async function request(url, options) {
  const response = await fetch(url, options)
  let envelope
  try {
    envelope = await response.json()
  } catch {
    throw new Error('服务返回了无法解析的响应')
  }

  if (!response.ok || envelope?.code !== 0) {
    throw new Error(envelope?.message || 'Skill 请求失败')
  }
  return envelope.data
}

function collectionUrl(agentName) {
  return `${API_ROOT}/${encodeURIComponent(agentName)}/skills`
}

function memberUrl(agentName, directoryName) {
  return `${collectionUrl(agentName)}/${encodeURIComponent(directoryName)}`
}

export function listSkills(agentName) {
  return request(collectionUrl(agentName), undefined)
}

export function getSkill(agentName, directoryName) {
  return request(memberUrl(agentName, directoryName), undefined)
}

export function importSkill(agentName, file) {
  const body = new FormData()
  body.append('file', file)
  return request(collectionUrl(agentName), { method: 'POST', body })
}

export function setSkillEnabled(agentName, directoryName, enabled) {
  return request(memberUrl(agentName, directoryName), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
}

export function deleteSkill(agentName, directoryName) {
  return request(memberUrl(agentName, directoryName), { method: 'DELETE' })
}
