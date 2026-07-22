let csrfToken = null
let csrfHeader = 'X-CSRF-TOKEN'
let unauthorizedHandler = () => {}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = typeof handler === 'function' ? handler : () => {}
}

export async function authStatus() {
  return request('/api/v1/auth/status', { skipUnauthorized: true })
}

export async function login(credentials) {
  return apiSend('/api/v1/auth/login', {
    body: credentials,
    skipUnauthorized: true,
  })
}

export async function logout() {
  try {
    return await apiSend('/api/v1/auth/logout', { skipUnauthorized: true })
  } finally {
    clearCsrfToken()
  }
}

export async function apiGet(path, options = {}) {
  return request(path, { ...options, method: 'GET' })
}

export async function apiSend(path, options = {}) {
  await ensureCsrf()
  return request(path, {
    ...options,
    method: options.method || 'POST',
    headers: {
      'Content-Type': 'application/json',
      [csrfHeader]: csrfToken,
      ...(options.headers || {}),
    },
    body: options.body == null ? undefined : JSON.stringify(options.body),
  })
}

async function ensureCsrf() {
  if (csrfToken) return
  const data = await request('/api/v1/auth/csrf', { skipUnauthorized: true })
  csrfHeader = data.headerName || csrfHeader
  csrfToken = data.token
}

function clearCsrfToken() {
  csrfToken = null
  csrfHeader = 'X-CSRF-TOKEN'
}

async function request(path, options = {}) {
  const res = await fetch(path, {
    credentials: 'same-origin',
    ...options,
  })
  const body = await parseBody(res)
  const code = body?.code ?? res.status
  if (!res.ok || code !== 0) {
    const error = new Error(body?.message || `请求失败（${res.status}）`)
    error.status = res.status
    error.code = code
    if (!options.skipUnauthorized && (res.status === 401 || code === 401)) {
      clearCsrfToken()
      unauthorizedHandler(error)
    }
    throw error
  }
  return body.data
}

async function parseBody(res) {
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch (e) {
    return { code: res.status, message: text }
  }
}
