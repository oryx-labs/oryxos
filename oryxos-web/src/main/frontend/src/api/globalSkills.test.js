import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  deleteGlobalSkill,
  getGlobalSkill,
  importGlobalSkill,
  listGlobalSkills,
  setGlobalSkillAgent,
  updateGlobalSkill,
} from './globalSkills.js'

function response(data) {
  return { ok: true, json: vi.fn().mockResolvedValue({ code: 0, message: 'success', data }) }
}

describe('public Skill API client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('covers list, detail, edit, delete and Agent association endpoints', async () => {
    const fetch = vi.fn().mockResolvedValue(response({}))
    vi.stubGlobal('fetch', fetch)

    await listGlobalSkills()
    await getGlobalSkill('weather skill')
    await updateGlobalSkill('weather', 'updated')
    await setGlobalSkillAgent('weather', 'ops agent', true)
    await setGlobalSkillAgent('weather', 'ops agent', false)
    await deleteGlobalSkill('weather')

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v1/skills', undefined)
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/v1/skills/weather%20skill', undefined)
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/v1/skills/weather', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: 'updated' }),
    })
    expect(fetch).toHaveBeenNthCalledWith(4, '/api/v1/skills/weather/agents/ops%20agent', {
      method: 'PUT',
    })
    expect(fetch).toHaveBeenNthCalledWith(5, '/api/v1/skills/weather/agents/ops%20agent', {
      method: 'DELETE',
    })
    expect(fetch).toHaveBeenNthCalledWith(6, '/api/v1/skills/weather', { method: 'DELETE' })
  })

  it('uploads a ZIP as multipart FormData', async () => {
    const fetch = vi.fn().mockResolvedValue(response({}))
    vi.stubGlobal('fetch', fetch)
    const file = new File(['zip'], 'weather.zip', { type: 'application/zip' })

    await importGlobalSkill(file)

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/skills')
    expect(options.method).toBe('POST')
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
  })
})
