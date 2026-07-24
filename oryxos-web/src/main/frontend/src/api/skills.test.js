import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  associateAgentSkill,
  deletePublicSkill,
  getPublicSkill,
  importPublicSkill,
  listAgentSkills,
  listPublicSkills,
  setPublicSkillEnabled,
  unlinkAgentSkill,
} from './skills.js'

function response(data, options = {}) {
  return {
    ok: options.ok ?? true,
    status: options.status ?? 200,
    json: vi.fn().mockResolvedValue({
      code: options.code ?? 0,
      message: options.message ?? 'success',
      data,
    }),
  }
}

describe('Skill API client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('uses the public market endpoints and encodes Skill names', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response([{ name: 'weather' }]))
      .mockResolvedValueOnce(response({ name: 'a/b' }))
    vi.stubGlobal('fetch', fetch)

    await expect(listPublicSkills()).resolves.toEqual([{ name: 'weather' }])
    await expect(getPublicSkill('a/b')).resolves.toEqual({ name: 'a/b' })

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v1/skills', undefined)
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/v1/skills/a%2Fb', undefined)
  })

  it('uploads one ZIP using FormData without forcing a multipart Content-Type', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ name: 'weather' }))
    vi.stubGlobal('fetch', fetch)
    const file = new File(['zip'], 'weather.zip', { type: 'application/zip' })

    await importPublicSkill(file)

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/skills')
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
  })

  it('changes global state and supports normal-then-force deletion', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ name: 'weather' }))
    vi.stubGlobal('fetch', fetch)

    await setPublicSkillEnabled('weather skill', false)
    await deletePublicSkill('weather skill')
    await deletePublicSkill('weather skill', true)

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v1/skills/weather%20skill', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: false }),
    })
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/v1/skills/weather%20skill', { method: 'DELETE' })
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/v1/skills/weather%20skill?force=true', { method: 'DELETE' })
  })

  it('uses canonical per-Agent association endpoints', async () => {
    const fetch = vi.fn().mockResolvedValue(response([]))
    vi.stubGlobal('fetch', fetch)

    await listAgentSkills('ops agent')
    await associateAgentSkill('ops agent', 'weather?')
    await unlinkAgentSkill('ops agent', 'weather?')

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v1/agents/ops%20agent/skills', undefined)
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/v1/agents/ops%20agent/skills/weather%3F', { method: 'PUT' })
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/v1/agents/ops%20agent/skills/weather%3F', { method: 'DELETE' })
  })

  it('preserves typed 409 conflict data for the force-delete dialog', async () => {
    const data = { reasonCode: 'SKILL_IN_USE', skillName: 'weather', linkedAgents: ['ops'] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data, {
      ok: false,
      status: 409,
      code: 409,
      message: 'Skill 仍有关联',
    })))

    await expect(deletePublicSkill('weather')).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 409,
      data,
    })
    await expect(deletePublicSkill('weather')).rejects.toBeInstanceOf(ApiError)
  })
})
