// Provider 新建/编辑表单的前端预检：与后端 ProviderApiController.validate 同规则——
// mock 免校验，普通 provider 必须有非空 api-key。缺 key 时禁用提交按钮，避免"填了名字就能点、
// 点了才吃 400"。后端仍是权威校验，这里只挡最常见的一种。
export function providerApiKeyMissing(name, apiKey) {
  if (name === 'mock') {
    return false
  }
  return String(apiKey ?? '').trim() === ''
}
