<script setup>
import { ref } from 'vue'
import {
  ApiError,
  deletePublicSkill,
  getPublicSkill,
  importPublicSkill,
  listPublicSkills,
  setPublicSkillEnabled,
} from '../api/skills.js'

const skills = ref([])
const loading = ref(false)
const error = ref('')
const selectedFile = ref(null)
const importing = ref(false)
const busy = ref(new Set())
const detail = ref(null)
const forceConflict = ref(null)

function setBusy(name, value) {
  const next = new Set(busy.value)
  if (value) next.add(name)
  else next.delete(name)
  busy.value = next
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    skills.value = (await listPublicSkills()) || []
  } catch (reason) {
    error.value = reason?.message || 'Skill 列表加载失败'
  } finally {
    loading.value = false
  }
}

async function upload() {
  if (!selectedFile.value || importing.value) return
  importing.value = true
  error.value = ''
  try {
    await importPublicSkill(selectedFile.value)
    selectedFile.value = null
    await refresh()
  } catch (reason) {
    error.value = reason?.message || 'Skill 导入失败'
  } finally {
    importing.value = false
  }
}

async function showDetail(name) {
  setBusy(name, true)
  error.value = ''
  try {
    detail.value = await getPublicSkill(name)
  } catch (reason) {
    error.value = reason?.message || 'Skill 详情加载失败'
  } finally {
    setBusy(name, false)
  }
}

async function toggle(skill) {
  setBusy(skill.name, true)
  error.value = ''
  try {
    await setPublicSkillEnabled(skill.name, !skill.configuredEnabled)
    await refresh()
  } catch (reason) {
    error.value = reason?.message || 'Skill 状态更新失败'
  } finally {
    setBusy(skill.name, false)
  }
}

async function remove(skill) {
  if (!window.confirm(`删除 Skill「${skill.name}」并归档公共包？`)) return
  setBusy(skill.name, true)
  error.value = ''
  try {
    await deletePublicSkill(skill.name, false)
    await refresh()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 409 && reason.data?.reasonCode === 'SKILL_IN_USE') {
      forceConflict.value = reason.data
    } else {
      error.value = reason?.message || 'Skill 删除失败'
    }
  } finally {
    setBusy(skill.name, false)
  }
}

async function forceDelete() {
  const conflict = forceConflict.value
  if (!conflict || busy.value.has(conflict.skillName)) return
  setBusy(conflict.skillName, true)
  error.value = ''
  try {
    await deletePublicSkill(conflict.skillName, true)
    forceConflict.value = null
    detail.value = null
    await refresh()
  } catch (reason) {
    error.value = reason?.message || '强制删除失败'
  } finally {
    setBusy(conflict.skillName, false)
  }
}

refresh()
</script>

<template>
  <section class="panel">
    <div class="head">
      <div><h3>公共 Skill 市场</h3><p>公共内容只存一份；Agent 通过标准相对软链接关联。</p></div>
      <button class="btn" data-action="refresh" :disabled="loading || importing || busy.size" @click="refresh">刷新</button>
    </div>
    <aside class="trust"><strong>信任提示：</strong>导入是管理员的显式信任动作。结构校验不证明指令、引用或脚本善意，请像代码一样审查来源。</aside>
    <div class="upload">
      <input type="file" accept=".zip,application/zip" :disabled="importing" @change="selectedFile = $event.target.files?.[0] || null" />
      <button class="btn primary" data-action="import" :disabled="!selectedFile || importing" @click="upload">{{ importing ? '导入中…' : '导入 ZIP' }}</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty">加载中…</p>
    <table v-else>
      <thead><tr><th>名称 / 描述</th><th>状态</th><th>关联 Agent</th><th>来源</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-if="!skills.length"><td colspan="5" class="empty">暂无公共 Skill，请导入一个已审查的 ZIP。</td></tr>
        <tr v-for="skill in skills" :key="skill.name" :data-skill="skill.name">
          <td><strong class="mono">{{ skill.name }}</strong><small>{{ skill.description || skill.validationError?.message || '元数据不可用' }}</small></td>
          <td><span :class="['status', skill.status]">{{ skill.status }}</span></td>
          <td>{{ skill.linkedAgents?.join(', ') || '—' }}</td>
          <td>{{ skill.source }}</td>
          <td class="actions">
            <button class="btn" data-action="detail" :disabled="busy.has(skill.name)" @click="showDetail(skill.name)">详情</button>
            <button class="btn" data-action="toggle" :disabled="busy.has(skill.name) || skill.status === 'invalid'" @click="toggle(skill)">{{ skill.configuredEnabled ? '禁用' : '启用' }}</button>
            <button class="btn danger" data-action="delete" :disabled="busy.has(skill.name)" @click="remove(skill)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <section v-if="detail" class="detail">
      <button class="btn close" @click="detail = null">关闭</button>
      <h3 class="mono">{{ detail.name }}</h3>
      <p>{{ detail.description }}</p>
      <dl><div><dt>入口</dt><dd class="mono">{{ detail.entrypoint || '—' }}</dd></div><div><dt>版本</dt><dd>{{ detail.version || '—' }}</dd></div><div><dt>文件</dt><dd>{{ detail.fileCount }} / {{ detail.totalBytes }} bytes</dd></div></dl>
      <ul><li v-for="resource in detail.resources" :key="resource" class="mono">{{ resource }}</li></ul>
    </section>

    <div v-if="forceConflict" class="modal" role="dialog" aria-modal="true" aria-labelledby="force-title">
      <div class="dialog">
        <h3 id="force-title">强制删除会解除 Agent 关联</h3>
        <p>Skill「{{ forceConflict.skillName }}」仍关联以下 Agent。继续会先解除链接，再归档公共包：</p>
        <ul><li v-for="agent in forceConflict.linkedAgents" :key="agent" class="mono">{{ agent }}</li></ul>
        <div class="actions"><button class="btn" data-action="cancel-force" @click="forceConflict = null">取消</button><button class="btn danger" data-action="force-delete" :disabled="busy.has(forceConflict.skillName)" @click="forceDelete">确认强制删除</button></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel { display: flex; flex-direction: column; gap: 14px; }
.head, .upload, .actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
h3, p { margin: 0; } .head p, small { color: var(--text-3); font-size: 12px; } small { display:block; margin-top:4px; }
.trust, .upload, .detail, .empty, .error { border: 1px solid var(--border); border-radius: 10px; padding: 14px; background: var(--bg-soft); }
.trust { border-left: 3px solid var(--brand); } table { width:100%; border-collapse:collapse; } th,td { padding:12px; border-bottom:1px solid var(--border); text-align:left; vertical-align:top; } th { color:var(--text-3); font-size:11px; }
.btn { border:1px solid var(--border); border-radius:7px; padding:7px 10px; background:var(--bg-soft); color:var(--text-1); cursor:pointer; }.btn:disabled{opacity:.5}.primary{background:var(--brand);color:#18120e}.danger{color:#ff806a}.mono{font-family:var(--font-mono)}
.status { font-family:var(--font-mono); font-size:12px }.enabled{color:#55c98b}.disabled{color:#d5a84f}.invalid,.error{color:#ff806a}.detail{position:relative}.close{position:absolute;right:14px;top:14px}dl{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}dt{color:var(--text-3);font-size:11px}dd{margin:4px 0 0}.modal{position:fixed;inset:0;background:#000a;display:grid;place-items:center;z-index:100}.dialog{width:min(520px,90vw);background:var(--bg);border:1px solid var(--border);border-radius:12px;padding:22px;display:flex;flex-direction:column;gap:14px}.dialog .actions{justify-content:flex-end}
</style>
