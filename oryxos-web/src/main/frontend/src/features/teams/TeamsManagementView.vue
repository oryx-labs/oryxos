<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  TeamsApiDisabledError,
  addUserTeam,
  createOrg,
  createTeam,
  deleteOrg,
  deleteTeam,
  listOrgs,
  listTeams,
  listUserTeams,
  removeUserTeam,
  renameOrg,
  renameTeam,
  setParentOrg,
  setTeamOrg,
} from './teams-api.js'

const catalog = ref({ loading: true, error: null, disabled: false, data: [] })
const orgs = ref({ loading: true, error: null, disabled: false, data: [] })
const createForm = reactive({ teamId: '', displayName: '', busy: false, error: '' })
const renameForm = reactive({ teamId: '', displayName: '', busy: false, error: '' })
const setOrgForm = reactive({ teamId: '', orgId: '', busy: false, error: '' })
const orgCreate = reactive({ orgId: '', displayName: '', busy: false, error: '' })
const orgRename = reactive({ orgId: '', displayName: '', busy: false, error: '' })
const setParentForm = reactive({ orgId: '', parentOrgId: '', busy: false, error: '' })
const member = reactive({
  username: '',
  loadedFor: '',
  teamIds: [],
  loading: false,
  error: '',
  disabled: false,
  addTeamId: '',
  busy: false,
})

const canCreate = computed(() => createForm.teamId.trim().length > 0 && !createForm.busy)
const canRename = computed(() => renameForm.displayName.trim().length > 0 && !renameForm.busy)
const canSetOrg = computed(() => !!setOrgForm.teamId && !setOrgForm.busy)
const canCreateOrg = computed(() => orgCreate.orgId.trim().length > 0 && !orgCreate.busy)
const canRenameOrg = computed(() => orgRename.displayName.trim().length > 0 && !orgRename.busy)
const canSetParent = computed(() => !!setParentForm.orgId && !setParentForm.busy)
const canLoadMembers = computed(() => member.username.trim().length > 0 && !member.loading)
const canAddMember = computed(
  () => member.loadedFor && member.addTeamId.trim().length > 0 && !member.busy,
)

function applyDisabled(e) {
  if (e instanceof TeamsApiDisabledError) {
    catalog.value = { loading: false, error: null, disabled: true, data: [] }
    orgs.value = { loading: false, error: null, disabled: true, data: [] }
    return true
  }
  return false
}

async function loadOrgs() {
  orgs.value = { loading: true, error: null, disabled: false, data: orgs.value.data || [] }
  try {
    const data = await listOrgs()
    orgs.value = { loading: false, error: null, disabled: false, data: data || [] }
  } catch (e) {
    if (applyDisabled(e)) return
    orgs.value = { loading: false, error: e.message, disabled: false, data: [] }
  }
}

async function loadTeams() {
  catalog.value = { loading: true, error: null, disabled: false, data: catalog.value.data || [] }
  try {
    const data = await listTeams()
    catalog.value = { loading: false, error: null, disabled: false, data: data || [] }
  } catch (e) {
    if (applyDisabled(e)) return
    catalog.value = { loading: false, error: e.message, disabled: false, data: [] }
  }
}

async function load() {
  await Promise.all([loadOrgs(), loadTeams()])
}

async function onCreateOrg() {
  if (!canCreateOrg.value) return
  orgCreate.busy = true
  orgCreate.error = ''
  try {
    await createOrg(orgCreate.orgId.trim(), orgCreate.displayName.trim())
    orgCreate.orgId = ''
    orgCreate.displayName = ''
    await loadOrgs()
  } catch (e) {
    if (applyDisabled(e)) return
    orgCreate.error = e.message
  } finally {
    orgCreate.busy = false
  }
}

function startRenameOrg(row) {
  orgRename.orgId = row.orgId
  orgRename.displayName = row.displayName || ''
  orgRename.error = ''
}

function cancelRenameOrg() {
  orgRename.orgId = ''
  orgRename.displayName = ''
  orgRename.error = ''
  orgRename.busy = false
}

async function onRenameOrg() {
  if (!canRenameOrg.value || !orgRename.orgId) return
  orgRename.busy = true
  orgRename.error = ''
  try {
    await renameOrg(orgRename.orgId, orgRename.displayName.trim())
    cancelRenameOrg()
    await loadOrgs()
  } catch (e) {
    orgRename.error = e.message
  } finally {
    orgRename.busy = false
  }
}

async function onDeleteOrg(row) {
  if (!row?.orgId) return
  if (!confirm(`删除组织目录「${row.orgId}」？已绑定团队的 org_id 将按服务端规则清空。`)) return
  try {
    await deleteOrg(row.orgId)
    if (orgRename.orgId === row.orgId) cancelRenameOrg()
    if (setParentForm.orgId === row.orgId) cancelSetParent()
    await Promise.all([loadOrgs(), loadTeams()])
  } catch (e) {
    if (applyDisabled(e)) return
    orgs.value = { ...orgs.value, error: e.message }
  }
}

function startSetParent(row) {
  setParentForm.orgId = row.orgId
  setParentForm.parentOrgId = row.parentOrgId || ''
  setParentForm.error = ''
}

function cancelSetParent() {
  setParentForm.orgId = ''
  setParentForm.parentOrgId = ''
  setParentForm.error = ''
  setParentForm.busy = false
}

async function onSetParent() {
  if (!canSetParent.value) return
  setParentForm.busy = true
  setParentForm.error = ''
  try {
    await setParentOrg(setParentForm.orgId, setParentForm.parentOrgId)
    cancelSetParent()
    await loadOrgs()
  } catch (e) {
    setParentForm.error = e.message
  } finally {
    setParentForm.busy = false
  }
}

async function onClearParent(row) {
  if (!row?.orgId) return
  if (!confirm(`清空组织「${row.orgId}」的 parentOrgId？`)) return
  try {
    await setParentOrg(row.orgId, null)
    if (setParentForm.orgId === row.orgId) cancelSetParent()
    await loadOrgs()
  } catch (e) {
    if (applyDisabled(e)) return
    orgs.value = { ...orgs.value, error: e.message }
  }
}

async function onCreate() {
  if (!canCreate.value) return
  createForm.busy = true
  createForm.error = ''
  try {
    await createTeam(createForm.teamId.trim(), createForm.displayName.trim())
    createForm.teamId = ''
    createForm.displayName = ''
    await loadTeams()
  } catch (e) {
    if (applyDisabled(e)) return
    createForm.error = e.message
  } finally {
    createForm.busy = false
  }
}

function startRename(row) {
  renameForm.teamId = row.teamId
  renameForm.displayName = row.displayName || ''
  renameForm.error = ''
}

function cancelRename() {
  renameForm.teamId = ''
  renameForm.displayName = ''
  renameForm.error = ''
  renameForm.busy = false
}

async function onRename() {
  if (!canRename.value || !renameForm.teamId) return
  renameForm.busy = true
  renameForm.error = ''
  try {
    await renameTeam(renameForm.teamId, renameForm.displayName.trim())
    cancelRename()
    await loadTeams()
  } catch (e) {
    renameForm.error = e.message
  } finally {
    renameForm.busy = false
  }
}

function startSetOrg(row) {
  setOrgForm.teamId = row.teamId
  setOrgForm.orgId = row.orgId || ''
  setOrgForm.error = ''
}

function cancelSetOrg() {
  setOrgForm.teamId = ''
  setOrgForm.orgId = ''
  setOrgForm.error = ''
  setOrgForm.busy = false
}

async function onSetOrg() {
  if (!canSetOrg.value) return
  setOrgForm.busy = true
  setOrgForm.error = ''
  try {
    await setTeamOrg(setOrgForm.teamId, setOrgForm.orgId)
    cancelSetOrg()
    await loadTeams()
  } catch (e) {
    setOrgForm.error = e.message
  } finally {
    setOrgForm.busy = false
  }
}

async function onClearOrg(row) {
  if (!row?.teamId) return
  if (!confirm(`清空团队「${row.teamId}」的 org_id？`)) return
  try {
    await setTeamOrg(row.teamId, null)
    if (setOrgForm.teamId === row.teamId) cancelSetOrg()
    await loadTeams()
  } catch (e) {
    if (applyDisabled(e)) return
    catalog.value = { ...catalog.value, error: e.message }
  }
}

async function onDelete(row) {
  if (!row?.teamId) return
  if (!confirm(`删除团队目录「${row.teamId}」？成员关系不会随本页自动清理。`)) return
  try {
    await deleteTeam(row.teamId)
    if (renameForm.teamId === row.teamId) cancelRename()
    if (setOrgForm.teamId === row.teamId) cancelSetOrg()
    await loadTeams()
  } catch (e) {
    catalog.value = {
      ...catalog.value,
      error: e.message,
      disabled: e instanceof TeamsApiDisabledError,
    }
  }
}

async function onLoadMembers() {
  const username = member.username.trim()
  if (!username) return
  member.loading = true
  member.error = ''
  member.disabled = false
  try {
    const data = await listUserTeams(username)
    member.loadedFor = data?.username || username
    member.teamIds = data?.teamIds || []
  } catch (e) {
    member.loadedFor = ''
    member.teamIds = []
    if (e instanceof TeamsApiDisabledError) {
      member.disabled = true
      member.error = ''
    } else {
      member.error = e.message
    }
  } finally {
    member.loading = false
  }
}

async function onAddMember() {
  if (!canAddMember.value) return
  member.busy = true
  member.error = ''
  try {
    const data = await addUserTeam(member.loadedFor, member.addTeamId.trim())
    member.teamIds = data?.teamIds || []
    member.addTeamId = ''
  } catch (e) {
    member.error = e.message
  } finally {
    member.busy = false
  }
}

async function onRemoveMember(teamId) {
  if (!member.loadedFor || !teamId) return
  member.busy = true
  member.error = ''
  try {
    const data = await removeUserTeam(member.loadedFor, teamId)
    member.teamIds = data?.teamIds || []
  } catch (e) {
    member.error = e.message
  } finally {
    member.busy = false
  }
}

onMounted(() => {
  load()
})

defineExpose({ load })
</script>

<template>
  <div class="teams">
    <p class="lede">
      管理组织目录、团队目录与用户成员关系。依赖
      <span class="mono">oryxos.web.teams-api.enabled</span>（默认关 → API 404）。需 ADMIN /
      <span class="mono">MANAGE_MEMBERS</span>。无完整树 UI / 环检测 / OIDC→org JIT。
    </p>

    <p v-if="catalog.disabled || orgs.disabled" class="error">
      团队/组织 API 未启用：请在配置中打开
      <span class="mono">oryxos.web.teams-api.enabled=true</span> 后刷新。
    </p>
    <p v-else-if="catalog.loading && orgs.loading" class="empty">加载中…</p>
    <p v-else-if="catalog.error || orgs.error" class="error">
      出错：{{ catalog.error || orgs.error }}
    </p>

    <template v-if="!catalog.disabled && !orgs.disabled">
      <h3 class="sec">组织目录</h3>
      <div class="toolbar create-row">
        <input v-model="orgCreate.orgId" class="gen-input mono" placeholder="orgId（必填）" />
        <input v-model="orgCreate.displayName" class="gen-input" placeholder="displayName（可选）" />
        <button class="btn btn-primary" :disabled="!canCreateOrg" @click="onCreateOrg">创建</button>
      </div>
      <p v-if="orgCreate.error" class="error">{{ orgCreate.error }}</p>

      <div v-if="orgRename.orgId" class="toolbar create-row">
        <span class="mono">重命名 {{ orgRename.orgId }}</span>
        <input v-model="orgRename.displayName" class="gen-input" placeholder="新 displayName" />
        <button class="btn btn-primary" :disabled="!canRenameOrg" @click="onRenameOrg">保存</button>
        <button class="btn" :disabled="orgRename.busy" @click="cancelRenameOrg">取消</button>
      </div>
      <p v-if="orgRename.error" class="error">{{ orgRename.error }}</p>

      <div v-if="setParentForm.orgId" class="toolbar create-row">
        <span class="mono">设父组织 {{ setParentForm.orgId }}</span>
        <input
          v-model="setParentForm.parentOrgId"
          class="gen-input mono"
          list="parent-org-id-options"
          placeholder="parentOrgId（空=清空）"
        />
        <datalist id="parent-org-id-options">
          <option
            v-for="o in orgs.data.filter((x) => x.orgId !== setParentForm.orgId)"
            :key="o.orgId"
            :value="o.orgId"
          />
        </datalist>
        <button class="btn btn-primary" :disabled="!canSetParent" @click="onSetParent">保存</button>
        <button class="btn" :disabled="setParentForm.busy" @click="cancelSetParent">取消</button>
      </div>
      <p v-if="setParentForm.error" class="error">{{ setParentForm.error }}</p>

      <table v-if="!orgs.loading">
        <thead>
          <tr>
            <th>orgId</th>
            <th>displayName</th>
            <th>parentOrgId</th>
            <th style="width:240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!orgs.data.length">
            <td colspan="4" class="empty">（暂无组织目录）</td>
          </tr>
          <tr v-for="o in orgs.data" :key="o.orgId">
            <td class="mono">{{ o.orgId }}</td>
            <td>{{ o.displayName || '—' }}</td>
            <td class="mono">{{ o.parentOrgId || '—' }}</td>
            <td class="ops">
              <button class="btn" @click="startRenameOrg(o)">重命名</button>
              <button class="btn" @click="startSetParent(o)">设父级</button>
              <button v-if="o.parentOrgId" class="btn" @click="onClearParent(o)">清父级</button>
              <button class="btn" @click="onDeleteOrg(o)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <h3 class="sec" style="margin-top:24px">团队目录</h3>
      <div class="toolbar create-row">
        <input v-model="createForm.teamId" class="gen-input mono" placeholder="teamId（必填）" />
        <input v-model="createForm.displayName" class="gen-input" placeholder="displayName（可选）" />
        <button class="btn btn-primary" :disabled="!canCreate" @click="onCreate">创建</button>
      </div>
      <p v-if="createForm.error" class="error">{{ createForm.error }}</p>

      <div v-if="renameForm.teamId" class="toolbar create-row">
        <span class="mono">重命名 {{ renameForm.teamId }}</span>
        <input v-model="renameForm.displayName" class="gen-input" placeholder="新 displayName" />
        <button class="btn btn-primary" :disabled="!canRename" @click="onRename">保存</button>
        <button class="btn" :disabled="renameForm.busy" @click="cancelRename">取消</button>
      </div>
      <p v-if="renameForm.error" class="error">{{ renameForm.error }}</p>

      <div v-if="setOrgForm.teamId" class="toolbar create-row">
        <span class="mono">绑定组织 {{ setOrgForm.teamId }}</span>
        <input
          v-model="setOrgForm.orgId"
          class="gen-input mono"
          list="org-id-options"
          placeholder="orgId（空=清空）"
        />
        <datalist id="org-id-options">
          <option v-for="o in orgs.data" :key="o.orgId" :value="o.orgId" />
        </datalist>
        <button class="btn btn-primary" :disabled="!canSetOrg" @click="onSetOrg">保存</button>
        <button class="btn" :disabled="setOrgForm.busy" @click="cancelSetOrg">取消</button>
      </div>
      <p v-if="setOrgForm.error" class="error">{{ setOrgForm.error }}</p>

      <table v-if="!catalog.loading">
        <thead>
          <tr>
            <th>teamId</th>
            <th>displayName</th>
            <th>orgId</th>
            <th style="width:240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!catalog.data.length">
            <td colspan="4" class="empty">（暂无团队目录）</td>
          </tr>
          <tr v-for="t in catalog.data" :key="t.teamId">
            <td class="mono">{{ t.teamId }}</td>
            <td>{{ t.displayName || '—' }}</td>
            <td class="mono">{{ t.orgId || '—' }}</td>
            <td class="ops">
              <button class="btn" @click="startRename(t)">重命名</button>
              <button class="btn" @click="startSetOrg(t)">设组织</button>
              <button v-if="t.orgId" class="btn" @click="onClearOrg(t)">清组织</button>
              <button class="btn" @click="onDelete(t)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <h3 class="sec" style="margin-top:24px">用户成员</h3>
      <p class="empty">按用户查看/增删团队成员（API 为 user→teams；无按团队列成员端点）。</p>
      <div class="toolbar create-row">
        <input
          v-model="member.username"
          class="gen-input mono"
          placeholder="username"
          @keyup.enter="onLoadMembers"
        />
        <button class="btn" :disabled="!canLoadMembers" @click="onLoadMembers">加载成员</button>
      </div>
      <p v-if="member.disabled" class="error">团队 API 未启用。</p>
      <p v-else-if="member.error" class="error">{{ member.error }}</p>
      <p v-else-if="member.loading" class="empty">加载成员…</p>
      <template v-else-if="member.loadedFor">
        <div class="sess-meta">
          <span>用户</span>
          <span class="mono">{{ member.loadedFor }}</span>
        </div>
        <div class="toolbar create-row">
          <input v-model="member.addTeamId" class="gen-input mono" placeholder="要加入的 teamId" />
          <button class="btn btn-primary" :disabled="!canAddMember" @click="onAddMember">加入团队</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>teamId</th>
              <th style="width:90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!member.teamIds.length">
              <td colspan="2" class="empty">（该用户暂无团队成员）</td>
            </tr>
            <tr v-for="id in member.teamIds" :key="id">
              <td class="mono">{{ id }}</td>
              <td class="ops">
                <button class="btn" :disabled="member.busy" @click="onRemoveMember(id)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
  </div>
</template>

<style scoped>
.teams { max-width: 960px; }
.lede {
  margin: 0 0 14px;
  color: var(--text-2);
  line-height: 1.6;
  font-size: 13px;
}
.sec {
  margin: 8px 0 10px;
  font-size: 14px;
  font-weight: 600;
}
.create-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.create-row .gen-input {
  flex: 1 1 160px;
  min-width: 140px;
}
.ops { white-space: nowrap; }
</style>
