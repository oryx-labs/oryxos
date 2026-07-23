<script setup>
import { onMounted, ref } from 'vue'

import {
  deleteGlobalSkill,
  getGlobalSkill,
  importGlobalSkill,
  listGlobalSkills,
  setGlobalSkillAgent,
  updateGlobalSkill,
} from '../api/globalSkills.js'

const rows = ref([])
const loading = ref(true)
const error = ref(null)
const success = ref(null)
const busy = ref(false)
const selectedFile = ref(null)
const fileInput = ref(null)
const detail = ref(null)
const editor = ref('')
const rowBusy = ref(null)
const agentBusy = ref(null)

function messageOf(reason, fallback) {
  return reason instanceof Error && reason.message ? reason.message : fallback
}

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = (await listGlobalSkills()) || []
  } catch (reason) {
    error.value = messageOf(reason, '公共 Skill 列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function chooseFile(event) {
  selectedFile.value = event.target.files?.[0] || null
  error.value = null
  success.value = null
}

async function upload() {
  if (busy.value || !selectedFile.value) return
  busy.value = true
  error.value = null
  success.value = null
  try {
    const created = await importGlobalSkill(selectedFile.value)
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    success.value = `公共 Skill「${created.skill.name}」已安装。`
    await load()
    await open(created.skill.directoryName)
  } catch (reason) {
    error.value = messageOf(reason, '公共 Skill 安装失败')
  } finally {
    busy.value = false
  }
}

async function open(skillName) {
  if (rowBusy.value) return
  rowBusy.value = skillName
  error.value = null
  try {
    detail.value = await getGlobalSkill(skillName)
    editor.value = detail.value.content || ''
  } catch (reason) {
    error.value = messageOf(reason, '公共 Skill 详情加载失败')
  } finally {
    rowBusy.value = null
  }
}

async function save() {
  if (!detail.value || busy.value) return
  busy.value = true
  error.value = null
  success.value = null
  try {
    detail.value = await updateGlobalSkill(detail.value.skill.directoryName, editor.value)
    editor.value = detail.value.content || ''
    success.value = `公共 Skill「${detail.value.skill.name}」已更新，关联 Agent 的下一次请求将使用新内容。`
    await load()
  } catch (reason) {
    error.value = messageOf(reason, '公共 Skill 保存失败')
  } finally {
    busy.value = false
  }
}

async function toggleAgent(agentName) {
  if (!detail.value || agentBusy.value) return
  const skillName = detail.value.skill.directoryName
  const associated = !detail.value.agentNames.includes(agentName)
  agentBusy.value = agentName
  error.value = null
  success.value = null
  try {
    detail.value = await setGlobalSkillAgent(skillName, agentName, associated)
    editor.value = detail.value.content || ''
    success.value = `已${associated ? '关联' : '解除关联'} Agent「${agentName}」。`
    await load()
  } catch (reason) {
    error.value = messageOf(reason, 'Agent 关联更新失败')
  } finally {
    agentBusy.value = null
  }
}

async function remove(row) {
  const skill = row.skill
  if (rowBusy.value || !window.confirm(`删除公共 Skill「${skill.name}」？该包会移入归档。`)) return
  rowBusy.value = skill.directoryName
  error.value = null
  success.value = null
  try {
    await deleteGlobalSkill(skill.directoryName)
    if (detail.value?.skill.directoryName === skill.directoryName) detail.value = null
    success.value = `公共 Skill「${skill.name}」已归档删除。`
    await load()
  } catch (reason) {
    error.value = messageOf(reason, '公共 Skill 删除失败')
  } finally {
    rowBusy.value = null
  }
}

function formatUpdatedAt(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
</script>

<template>
  <section class="page">
    <div class="head">
      <div>
        <h3>公共 Skill 管理</h3>
        <p class="subtle">公共 Skill 只保存一份，可关联给多个 Agent；Agent 私有 Skill 仍在 Agent 详情中管理。</p>
      </div>
      <button class="button" :disabled="loading || busy || rowBusy" @click="load">刷新</button>
    </div>

    <div class="trust-note"><strong>信任边界：</strong>Skill 等同代码，仅安装已审查来源。格式校验不代表内容可信。</div>

    <div class="upload-box">
      <label for="global-skill-package">安装公共 Skill ZIP 包</label>
      <div class="upload-actions">
        <input id="global-skill-package" ref="fileInput" type="file" accept=".zip,application/zip" :disabled="busy" @change="chooseFile" />
        <button class="button primary" :disabled="busy || !selectedFile" @click="upload">{{ busy ? '处理中…' : '安装' }}</button>
      </div>
      <p class="subtle">同名包不会覆盖。安装后可查看、编辑 SKILL.md，并关联到 Agent。</p>
    </div>

    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="success" class="feedback success" role="status">{{ success }}</p>
    <p v-if="loading" class="state">公共 Skill 列表加载中…</p>
    <div v-else-if="!error && !rows.length" class="state">暂无公共 Skill，可从上方安装已审查的 ZIP 包。</div>
    <div v-else class="table-scroll">
      <table>
        <thead><tr><th>名称 / 描述</th><th>状态</th><th>关联 Agent</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.skill.directoryName">
            <td><div class="name mono">{{ row.skill.name }}</div><div class="description">{{ row.skill.description || '（元数据不可用）' }}</div></td>
            <td><span :class="['status', `status-${row.skill.status}`]">{{ row.skill.status }}</span></td>
            <td><span v-if="!row.agentNames.length" class="subtle">未关联</span><span v-for="agent in row.agentNames" v-else :key="agent" class="tag mono">{{ agent }}</span></td>
            <td class="mono compact">{{ formatUpdatedAt(row.skill.updatedAt) }}</td>
            <td class="actions">
              <button class="button" :disabled="rowBusy" @click="open(row.skill.directoryName)">{{ rowBusy === row.skill.directoryName ? '加载中…' : '查看 / 编辑' }}</button>
              <button class="button danger" :disabled="rowBusy" @click="remove(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <section v-if="detail" class="detail-card">
      <div class="head">
        <div><h4>{{ detail.skill.name }}</h4><span class="mono subtle">skills/{{ detail.skill.directoryName }}/SKILL.md</span></div>
        <button class="button" @click="detail = null">关闭</button>
      </div>

      <div class="associations">
        <h5>关联 Agent</h5>
        <p v-if="!detail.availableAgents.length" class="subtle">暂无 Agent，请先创建 Agent。</p>
        <label v-for="agent in detail.availableAgents" v-else :key="agent" class="agent-option">
          <input type="checkbox" :checked="detail.agentNames.includes(agent)" :disabled="agentBusy" @change="toggleAgent(agent)" />
          <span class="mono">{{ agent }}</span>
          <span v-if="agentBusy === agent" class="subtle">保存中…</span>
        </label>
      </div>

      <div>
        <div class="editor-head"><h5>SKILL.md</h5><button class="button primary" :disabled="busy || editor === detail.content" @click="save">{{ busy ? '保存中…' : '保存' }}</button></div>
        <textarea v-model="editor" class="editor mono" spellcheck="false"></textarea>
      </div>
    </section>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.head, .upload-actions, .editor-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h3, h4, h5 { margin: 0; font-weight: 600; }
h3 { font-size: 16px; } h5 { color: var(--text-2); font-size: 12px; }
.subtle { color: var(--text-3); font-size: 12px; line-height: 1.5; margin: 4px 0 0; }
.trust-note, .upload-box, .detail-card, .state, .feedback { border: 1px solid var(--border); border-radius: var(--radius); padding: 14px 16px; background: var(--bg-soft); }
.trust-note { border-left: 3px solid var(--brand); color: var(--text-2); }
.trust-note strong { color: var(--brand); }
.upload-box label { display: block; margin-bottom: 10px; }
.upload-actions { justify-content: flex-start; flex-wrap: wrap; }
input[type='file'] { color: var(--text-2); font: inherit; max-width: 100%; }
.button { color: var(--text-1); background: var(--bg-mute); border: 1px solid var(--border); border-radius: var(--radius); padding: 5px 10px; font: inherit; font-size: 12px; cursor: pointer; white-space: nowrap; }
.button:hover:not(:disabled) { color: var(--brand); border-color: var(--brand); }
.button:disabled { opacity: .5; cursor: not-allowed; }
.button.primary { color: var(--brand); border-color: var(--brand); background: var(--brand-soft); }
.button.danger:hover:not(:disabled) { color: var(--err); border-color: var(--err); }
.feedback.error { color: var(--err); } .feedback.success { color: var(--ok); }
.state { color: var(--text-3); text-align: center; padding: 28px 16px; }
.table-scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius); }
table { width: 100%; min-width: 780px; border-collapse: collapse; background: var(--bg-soft); }
th, td { padding: 10px 12px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
tbody tr:last-child td { border-bottom: 0; } th { color: var(--text-2); font-size: 12px; font-weight: 500; }
.name { color: var(--text-1); font-weight: 600; } .description { color: var(--text-2); font-size: 12px; margin-top: 4px; max-width: 340px; }
.status { font-size: 12px; } .status-enabled { color: var(--ok); } .status-disabled { color: var(--text-3); } .status-invalid { color: var(--err); }
.tag { display: inline-block; color: var(--brand); background: var(--brand-soft); border-radius: var(--radius); padding: 3px 7px; margin: 0 5px 5px 0; font-size: 11px; }
.compact { color: var(--text-2); font-size: 12px; } .actions { white-space: nowrap; } .actions .button { margin-right: 5px; }
.detail-card { display: flex; flex-direction: column; gap: 16px; }
.associations { border: 1px solid var(--border); border-radius: var(--radius); padding: 12px; }
.agent-option { display: inline-flex; align-items: center; gap: 6px; margin: 10px 16px 0 0; color: var(--text-2); font-size: 12px; }
.editor-head { margin-bottom: 8px; }
.editor { width: 100%; min-height: 360px; resize: vertical; box-sizing: border-box; color: var(--text-1); background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius); padding: 12px; line-height: 1.6; }
.editor:focus { outline: none; border-color: var(--brand); }
.mono { font-family: var(--font-mono); }
@media (max-width: 640px) { .head { align-items: flex-start; } }
</style>
