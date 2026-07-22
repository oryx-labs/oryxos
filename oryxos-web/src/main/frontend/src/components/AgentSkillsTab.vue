<script setup>
import { ref, watch } from 'vue'

import {
  deleteSkill,
  getSkill,
  importSkill,
  listSkills,
  setSkillEnabled,
} from '../api/skills.js'

const props = defineProps({
  agentName: { type: String, required: true },
})

const skills = ref([])
const loading = ref(false)
const collectionBusy = ref(false)
const error = ref(null)
const success = ref(null)
const selectedFile = ref(null)
const fileInput = ref(null)
const rowBusy = ref(new Set())
const detail = ref(null)
const detailBusy = ref(null)
let requestVersion = 0
let listRequestVersion = 0
let detailRequestVersion = 0

const STATUS_LABELS = {
  enabled: '已启用',
  disabled: '已禁用',
  invalid: '校验失败',
}

const SOURCE_LABELS = {
  upload: '上传导入',
  workspace: '工作区',
}

function messageOf(reason, fallback) {
  return reason instanceof Error && reason.message ? reason.message : fallback
}

function isRowBusy(directoryName) {
  return rowBusy.value.has(directoryName)
}

function setRowBusy(directoryName, busy) {
  const next = new Set(rowBusy.value)
  if (busy) next.add(directoryName)
  else next.delete(directoryName)
  rowBusy.value = next
}

function invalidateDetail() {
  detailRequestVersion += 1
  detail.value = null
  detailBusy.value = null
}

function compareDirectoryName(left, right) {
  const a = left.directoryName || ''
  const b = right.directoryName || ''
  return a < b ? -1 : a > b ? 1 : 0
}

function upsertSkill(updated) {
  const existing = skills.value.find((skill) => skill.directoryName === updated.directoryName)
  const next = { ...existing, ...updated }
  skills.value = [
    ...skills.value.filter((skill) => skill.directoryName !== updated.directoryName),
    next,
  ].sort(compareDirectoryName)
}

function resetState() {
  skills.value = []
  loading.value = true
  collectionBusy.value = false
  error.value = null
  success.value = null
  selectedFile.value = null
  rowBusy.value = new Set()
  invalidateDetail()
  if (fileInput.value) fileInput.value.value = ''
}

async function refresh(version = requestVersion) {
  invalidateDetail()
  const listVersion = ++listRequestVersion
  loading.value = true
  error.value = null
  try {
    const result = await listSkills(props.agentName)
    if (version === requestVersion && listVersion === listRequestVersion) skills.value = result || []
  } catch (reason) {
    if (version === requestVersion && listVersion === listRequestVersion) {
      error.value = messageOf(reason, 'Skill 列表加载失败')
    }
  } finally {
    if (version === requestVersion && listVersion === listRequestVersion) loading.value = false
  }
}

watch(
  () => props.agentName,
  () => {
    const version = ++requestVersion
    resetState()
    void refresh(version)
  },
  { immediate: true },
)

function selectFile(event) {
  selectedFile.value = event.target.files?.[0] || null
  error.value = null
  success.value = null
}

async function upload() {
  if (collectionBusy.value || !selectedFile.value) return
  invalidateDetail()
  collectionBusy.value = true
  error.value = null
  success.value = null
  const version = requestVersion
  try {
    const uploaded = await importSkill(props.agentName, selectedFile.value)
    if (version !== requestVersion) return
    upsertSkill(uploaded)
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    success.value = `Skill「${uploaded.name || uploaded.directoryName}」已导入并启用，将从下一次请求生效。`
    await refresh(version)
  } catch (reason) {
    if (version === requestVersion) error.value = messageOf(reason, 'Skill 导入失败')
  } finally {
    if (version === requestVersion) collectionBusy.value = false
  }
}

async function showDetail(skill) {
  if (detailBusy.value || isRowBusy(skill.directoryName)) return
  const detailVersion = ++detailRequestVersion
  detailBusy.value = skill.directoryName
  error.value = null
  const version = requestVersion
  try {
    const result = await getSkill(props.agentName, skill.directoryName)
    if (version === requestVersion && detailVersion === detailRequestVersion) {
      detail.value = result
    }
  } catch (reason) {
    if (version === requestVersion && detailVersion === detailRequestVersion) {
      error.value = messageOf(reason, 'Skill 详情加载失败')
    }
  } finally {
    if (version === requestVersion && detailVersion === detailRequestVersion) {
      detailBusy.value = null
    }
  }
}

async function toggle(skill) {
  if (isRowBusy(skill.directoryName)) return
  invalidateDetail()
  setRowBusy(skill.directoryName, true)
  error.value = null
  success.value = null
  const version = requestVersion
  const enabled = !skill.configuredEnabled
  try {
    const updated = await setSkillEnabled(props.agentName, skill.directoryName, enabled)
    if (version !== requestVersion) return
    upsertSkill(updated)
    success.value = `Skill「${skill.name}」已${enabled ? '启用' : '禁用'}，将从下一次请求生效。`
    await refresh(version)
  } catch (reason) {
    if (version === requestVersion) error.value = messageOf(reason, 'Skill 状态更新失败')
  } finally {
    if (version === requestVersion) setRowBusy(skill.directoryName, false)
  }
}

async function remove(skill) {
  if (isRowBusy(skill.directoryName)) return
  if (!window.confirm(`删除 Skill「${skill.name}」？完整目录会移入可追溯归档。`)) return
  invalidateDetail()
  setRowBusy(skill.directoryName, true)
  error.value = null
  success.value = null
  const version = requestVersion
  try {
    await deleteSkill(props.agentName, skill.directoryName)
    if (version !== requestVersion) return
    skills.value = skills.value.filter((candidate) => candidate.directoryName !== skill.directoryName)
    success.value = `Skill「${skill.name}」已归档删除，将从下一次请求消失。`
    await refresh(version)
  } catch (reason) {
    if (version === requestVersion) error.value = messageOf(reason, 'Skill 删除失败')
  } finally {
    if (version === requestVersion) setRowBusy(skill.directoryName, false)
  }
}

function formatUpdatedAt(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function formatBytes(value) {
  return Number(value || 0).toLocaleString()
}
</script>

<template>
  <section class="skills-panel" aria-labelledby="skills-title">
    <div class="skills-head">
      <div>
        <h3 id="skills-title">Skill 管理</h3>
        <p class="subtle">Skill 仅属于当前 Agent；启停、导入和删除从下一次顶层请求生效。</p>
      </div>
      <button class="button" data-action="refresh" :disabled="loading || collectionBusy || rowBusy.size > 0" @click="refresh()">刷新</button>
    </div>

    <div class="trust-note">
      <strong>信任边界：</strong>Skill 等同代码，仅导入已审查来源。格式校验不代表其指令、脚本或引用内容可信。
    </div>

    <div class="upload-box">
      <label for="skill-package">导入单个 Skill ZIP 包</label>
      <div class="upload-actions">
        <input id="skill-package" ref="fileInput" type="file" accept=".zip,application/zip" :disabled="collectionBusy" @change="selectFile" />
        <button class="button primary" data-action="import" :disabled="collectionBusy || !selectedFile" @click="upload">
          {{ collectionBusy ? '导入中…' : '导入并启用' }}
        </button>
      </div>
      <p class="subtle">支持根目录直接包含 SKILL.md，或仅含一个同名顶层目录；同名包不会覆盖。</p>
    </div>

    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="success" class="feedback success" role="status">{{ success }}</p>

    <p v-if="loading" data-testid="skills-loading" class="state">Skill 列表加载中…</p>
    <div v-else-if="!error && !skills.length" data-testid="skills-empty" class="state">暂无 Skill，可从上方导入已审查的 ZIP 包。</div>
    <div v-else class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>名称 / 描述</th>
            <th>状态</th>
            <th>来源</th>
            <th>更新时间</th>
            <th>目录</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.directoryName" :data-skill="skill.directoryName">
            <td>
              <div class="skill-name mono">{{ skill.name }}</div>
              <div class="description">{{ skill.description || '（元数据不可用）' }}</div>
              <div v-if="skill.validationError" class="validation-error">
                <span class="mono">{{ skill.validationError.code }}</span> · {{ skill.validationError.message }}
              </div>
            </td>
            <td>
              <span :class="['status', `status-${skill.status}`]">{{ STATUS_LABELS[skill.status] || skill.status }}</span>
              <div :class="['catalog', { included: skill.catalogIncluded }]">
                {{ skill.catalogIncluded ? '已进入 L1' : '未进入 L1' }}
              </div>
            </td>
            <td>{{ SOURCE_LABELS[skill.source] || skill.source }}</td>
            <td class="mono compact">{{ formatUpdatedAt(skill.updatedAt) }}</td>
            <td class="mono compact">{{ skill.directoryName }}</td>
            <td class="actions">
              <button class="button" data-action="detail" :disabled="isRowBusy(skill.directoryName) || detailBusy === skill.directoryName" @click="showDetail(skill)">
                {{ detailBusy === skill.directoryName ? '加载中…' : '详情' }}
              </button>
              <button class="button" data-action="toggle" :disabled="isRowBusy(skill.directoryName)" @click="toggle(skill)">
                {{ isRowBusy(skill.directoryName) ? '处理中…' : (skill.configuredEnabled ? '禁用' : '启用') }}
              </button>
              <button class="button danger" data-action="delete" :disabled="isRowBusy(skill.directoryName)" @click="remove(skill)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <section v-if="detail" data-testid="skill-detail" class="detail-card">
      <div class="detail-head">
        <div>
          <h4>{{ detail.name }}</h4>
          <span class="mono subtle">{{ detail.entrypoint || '入口不可用' }}</span>
        </div>
        <button class="button" @click="detail = null">关闭</button>
      </div>
      <dl class="detail-grid">
        <div><dt>license</dt><dd class="mono">{{ detail.license || '—' }}</dd></div>
        <div><dt>compatibility</dt><dd>{{ detail.compatibility || '—' }}</dd></div>
        <div><dt>allowed-tools</dt><dd class="mono">{{ detail.allowedTools || '—' }}</dd></div>
        <div><dt>内容统计</dt><dd class="mono">{{ detail.fileCount }} 个文件 · {{ formatBytes(detail.totalBytes) }} bytes</dd></div>
      </dl>
      <div v-if="Object.keys(detail.metadata || {}).length" class="detail-section">
        <h5>Metadata</h5>
        <span v-for="(value, key) in detail.metadata" :key="key" class="metadata mono">{{ key }}={{ value }}</span>
      </div>
      <div class="detail-section">
        <h5>资源</h5>
        <p v-if="!detail.resources?.length" class="subtle">（没有可展示的内容文件）</p>
        <ul v-else class="resources mono">
          <li v-for="resource in detail.resources" :key="resource">{{ resource }}</li>
        </ul>
      </div>
    </section>
  </section>
</template>

<style scoped>
.skills-panel { display: flex; flex-direction: column; gap: 16px; }
.skills-head, .detail-head, .upload-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h3, h4, h5 { margin: 0; font-weight: 600; }
h3 { font-size: 16px; }
h4 { margin-bottom: 4px; }
h5 { color: var(--text-2); font-size: 12px; margin-bottom: 8px; }
.subtle { color: var(--text-3); font-size: 12px; line-height: 1.5; margin: 4px 0 0; }
.trust-note, .upload-box, .detail-card, .state, .feedback {
  border: 1px solid var(--border); border-radius: var(--radius); padding: 14px 16px; background: var(--bg-soft);
}
.trust-note { border-left: 3px solid var(--brand); color: var(--text-2); line-height: 1.6; }
.trust-note strong { color: var(--brand); }
.upload-box label { display: block; color: var(--text-1); font-weight: 500; margin-bottom: 10px; }
.upload-actions { justify-content: flex-start; flex-wrap: wrap; }
input[type='file'] { color: var(--text-2); font: inherit; max-width: 100%; }
.button {
  color: var(--text-1); background: var(--bg-mute); border: 1px solid var(--border); border-radius: var(--radius);
  padding: 5px 10px; font: inherit; font-size: 12px; cursor: pointer; white-space: nowrap;
}
.button:hover:not(:disabled) { color: var(--brand); border-color: var(--brand); }
.button:disabled { opacity: .5; cursor: not-allowed; }
.button.primary { color: var(--brand); border-color: var(--brand); background: var(--brand-soft); }
.button.danger:hover:not(:disabled) { color: var(--err); border-color: var(--err); }
.feedback.error { color: var(--err); }
.feedback.success { color: var(--ok); }
.state { color: var(--text-3); text-align: center; padding: 28px 16px; }
.table-scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius); }
table { width: 100%; min-width: 880px; border-collapse: collapse; background: var(--bg-soft); }
th, td { padding: 10px 12px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
tbody tr:last-child td { border-bottom: 0; }
th { color: var(--text-2); font-size: 12px; font-weight: 500; }
.skill-name { color: var(--text-1); font-weight: 600; }
.description { color: var(--text-2); font-size: 12px; margin-top: 4px; max-width: 320px; }
.validation-error { color: var(--err); font-size: 12px; margin-top: 6px; max-width: 360px; }
.status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.status::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.status-enabled { color: var(--ok); }
.status-disabled { color: var(--text-3); }
.status-invalid { color: var(--err); }
.catalog { color: var(--text-3); font-size: 11px; margin-top: 5px; }
.catalog.included { color: var(--brand); }
.compact { color: var(--text-2); font-size: 12px; }
.actions { white-space: nowrap; }
.actions .button { margin-right: 5px; }
.detail-card { display: flex; flex-direction: column; gap: 14px; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0; border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; }
.detail-grid > div { padding: 10px 12px; border-bottom: 1px solid var(--border); }
.detail-grid dt { color: var(--text-3); font-size: 11px; margin-bottom: 4px; }
.detail-grid dd { margin: 0; color: var(--text-2); overflow-wrap: anywhere; }
.metadata { display: inline-block; color: var(--brand); background: var(--brand-soft); border-radius: var(--radius); padding: 3px 7px; margin: 0 6px 6px 0; font-size: 12px; }
.resources { color: var(--text-2); font-size: 12px; line-height: 1.8; margin: 0; padding-left: 20px; }
.mono { font-family: var(--font-mono); }
@media (max-width: 640px) {
  .skills-head, .detail-head { align-items: flex-start; }
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
