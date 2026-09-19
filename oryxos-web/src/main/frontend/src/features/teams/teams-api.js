/**
 * Teams / Orgs Admin HTTP helpers (#548 / #556 / #570): catalog + memberships + org bind
 * + org set-parent via /api/v1/teams and /api/v1/orgs.
 * Flag oryxos.web.teams-api.enabled default off → 404 (TeamsApiDisabledError).
 */

export class TeamsApiDisabledError extends Error {
  constructor(message = 'teams api disabled') {
    super(message)
    this.name = 'TeamsApiDisabledError'
  }
}

export async function listTeams() {
  return unwrap(await fetch('/api/v1/teams'))
}

export async function createTeam(teamId, displayName) {
  return unwrap(
    await fetch('/api/v1/teams', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ teamId, displayName: displayName || null }),
    }),
  )
}

export async function renameTeam(teamId, displayName) {
  return unwrap(
    await fetch(`/api/v1/teams/${encodeURIComponent(teamId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName }),
    }),
  )
}

export async function deleteTeam(teamId) {
  return unwrap(
    await fetch(`/api/v1/teams/${encodeURIComponent(teamId)}`, { method: 'DELETE' }),
  )
}

/** Bind team to org; pass null/empty orgId to clear. */
export async function setTeamOrg(teamId, orgId) {
  const normalized = orgId == null || String(orgId).trim() === '' ? null : String(orgId).trim()
  return unwrap(
    await fetch(`/api/v1/teams/${encodeURIComponent(teamId)}/org`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orgId: normalized }),
    }),
  )
}

export async function listOrgs() {
  return unwrap(await fetch('/api/v1/orgs'))
}

export async function createOrg(orgId, displayName) {
  return unwrap(
    await fetch('/api/v1/orgs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orgId, displayName: displayName || null }),
    }),
  )
}

export async function renameOrg(orgId, displayName) {
  return unwrap(
    await fetch(`/api/v1/orgs/${encodeURIComponent(orgId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName }),
    }),
  )
}

export async function deleteOrg(orgId) {
  return unwrap(
    await fetch(`/api/v1/orgs/${encodeURIComponent(orgId)}`, { method: 'DELETE' }),
  )
}

/** Set org parent; pass null/empty parentOrgId to clear. */
export async function setParentOrg(orgId, parentOrgId) {
  const normalized =
    parentOrgId == null || String(parentOrgId).trim() === '' ? null : String(parentOrgId).trim()
  return unwrap(
    await fetch(`/api/v1/orgs/${encodeURIComponent(orgId)}/parent`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentOrgId: normalized }),
    }),
  )
}

export async function listUserTeams(username) {
  return unwrap(await fetch(`/api/v1/users/${encodeURIComponent(username)}/teams`))
}

export async function addUserTeam(username, teamId) {
  return unwrap(
    await fetch(
      `/api/v1/users/${encodeURIComponent(username)}/teams/${encodeURIComponent(teamId)}`,
      { method: 'PUT' },
    ),
  )
}

export async function removeUserTeam(username, teamId) {
  return unwrap(
    await fetch(
      `/api/v1/users/${encodeURIComponent(username)}/teams/${encodeURIComponent(teamId)}`,
      { method: 'DELETE' },
    ),
  )
}

async function unwrap(res) {
  let body
  try {
    body = await res.json()
  } catch {
    body = null
  }
  if (res.status === 404) {
    const msg = body?.message || 'teams api disabled'
    throw new TeamsApiDisabledError(msg)
  }
  if (!body || body.code !== 0) {
    throw new Error(body?.message || `请求失败 (${res.status})`)
  }
  return body.data
}
