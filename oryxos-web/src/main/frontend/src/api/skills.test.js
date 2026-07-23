import { afterEach, describe, expect, it, vi } from 'vitest'

import { deleteSkill, getSkill, importSkill, listSkills, setSkillEnabled } from './skills.js'

function response(data, options = {}) {
  return {
    ok: options.ok ?? true,
    json: vi.fn().mockResolvedValue({
      code: options.code ?? 0,
      message: options.message ?? 'success',
      data,
    }),
  }
}

describe('Skill API client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists and reads details with encoded path segments', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response([{ directoryName: 'weather' }]))
      .mockResolvedValueOnce(response({ directoryName: 'a/b' }))
    vi.stubGlobal('fetch', fetch)

    await expect(listSkills('ops agent')).resolves.toEqual([{ directoryName: 'weather' }])
    await expect(getSkill('ops agent', 'a/b')).resolves.toEqual({ directoryName: 'a/b' })

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v1/agents/ops%20agent/skills', undefined)
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/v1/agents/ops%20agent/skills/a%2Fb', undefined)
  })

  it('uploads the file FormData part without setting multipart Content-Type', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ directoryName: 'weather' }))
    vi.stubGlobal('fetch', fetch)
    const file = new File(['zip'], 'weather.zip', { type: 'application/zip' })

    await importSkill('ops', file)

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/agents/ops/skills')
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
  })

  it('sends a strict JSON boolean when enabling or disabling', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ status: 'disabled' }))
    vi.stubGlobal('fetch', fetch)

    await setSkillEnabled('ops', 'weather skill', false)

    expect(fetch).toHaveBeenCalledWith('/api/v1/agents/ops/skills/weather%20skill', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: false }),
    })
  })

  it('deletes the encoded member and accepts a null response payload', async () => {
    const fetch = vi.fn().mockResolvedValue(response(null))
    vi.stubGlobal('fetch', fetch)

    await expect(deleteSkill('ops', 'weather?')).resolves.toBeNull()

    expect(fetch).toHaveBeenCalledWith('/api/v1/agents/ops/skills/weather%3F', {
      method: 'DELETE',
    })
  })

  it('surfaces the unified envelope message for HTTP and domain failures', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response(null, { ok: false, code: 413, message: '包过大' }))
      .mockResolvedValueOnce(response(null, { code: 409, message: '名称冲突' }))
    vi.stubGlobal('fetch', fetch)

    await expect(importSkill('ops', new File(['x'], 'x.zip'))).rejects.toThrow('包过大')
    await expect(listSkills('ops')).rejects.toThrow('名称冲突')
  })
})
