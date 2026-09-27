import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createContext, runInContext } from 'node:vm'
import { normalizeMcpRequestTimeout } from './mcp-timeout.js'
import { compile, createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

// Execute the real form submission boundary; only its HTTP request and UI refresh are replaced.
const component = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')
const mapStart = component.indexOf('function textToMap(')
const mapParser = component.slice(mapStart, component.indexOf('// 新建/编辑表单', mapStart))
const submit = component.slice(component.indexOf('async function saveMcp('), component.indexOf('async function deleteMcp('))

async function submitForm(transport, editing = null) {
  const requests = []
  const form = {
    name: 'fixture', editing, transport, command: 'node fixture.js',
    url: 'https://example.invalid/team/mcp?tenant=x%2Fy',
    requestTimeoutSeconds: 120,
    envText: 'TOKEN=${FIXTURE_TOKEN}', headersText: 'Authorization=Bearer ${FIXTURE_TOKEN}',
  }
  const context = createContext({
    mcpForm: form, normalizeMcpRequestTimeout,
    cancelMcp() {}, async loadMcp() {},
    async fetch(url, options) {
      requests.push({ url, method: options.method, body: JSON.parse(options.body) })
      return { async json() { return { code: 0 } } }
    },
  })
  runInContext(`${mapParser}\n${submit}\nthis.submitMcp = saveMcp`, context)
  await context.submitMcp()
  assert.equal(form.error, null)
  assert.equal(form.busy, false)
  assert.equal(requests.length, 1)
  return requests[0]
}

for (const transport of ['http', 'sse', 'streamable', 'auto']) {
  for (const editing of [null, 'fixture']) {
    test(`MCP ${transport} ${editing ? '编辑' : '新建'}保留完整 URL、请求头和超时`, async () => {
      const request = await submitForm(transport, editing)
      assert.equal(request.url, editing ? '/api/v1/mcp-servers/fixture' : '/api/v1/mcp-servers')
      assert.equal(request.method, editing ? 'PUT' : 'POST')
      assert.deepEqual(request.body, {
        name: 'fixture', transport, command: null,
        url: 'https://example.invalid/team/mcp?tenant=x%2Fy', requestTimeoutSeconds: 120,
        env: { TOKEN: '${FIXTURE_TOKEN}' }, headers: { Authorization: 'Bearer ${FIXTURE_TOKEN}' },
      })
    })
  }
}

test('MCP stdio 表单保留 command，忽略远程 URL', async () => {
  const request = await submitForm('stdio')
  assert.equal(request.body.command, 'node fixture.js')
  assert.equal(request.body.url, null)
})

test('MCP 管理台渲染五种已支持的传输选项', async () => {
  const start = component.indexOf('<select v-model="mcpForm.transport"')
  const select = component.slice(start, component.indexOf('</select>', start) + '</select>'.length)
  const app = createSSRApp({
    data: () => ({ mcpForm: { transport: 'streamable' } }),
    render: compile(select),
  })
  const html = await renderToString(app)
  assert.deepEqual([...html.matchAll(/<option value="([^"]+)"/g)].map(match => match[1]),
    ['stdio', 'http', 'sse', 'streamable', 'auto'])
})
