<script setup>
import { computed, ref, watch } from 'vue'
import {
  associateAgentSkill,
  listAgentSkills,
  listPublicSkills,
  unlinkAgentSkill,
} from '../api/skills.js'

const props = defineProps({
  agentName: { type: String, required: true },
  associatedSkills: { type: Array, default: () => [] },
})

const associations = ref([])
const publicSkills = ref([])
const loading = ref(false)
const error = ref('')
const busy = ref(new Set())

const rows = computed(() => {
  const linked = new Map(associations.value.map((item) => [item.skillName, item]))
  const publicByName = new Map(publicSkills.value.map((item) => [item.name, item]))
  const names = [...new Set([...linked.keys(), ...publicByName.keys()])].sort()
  return names.map((name) => ({
    name,
    association: linked.get(name) || null,
    skill: publicByName.get(name) || null,
  }))
})

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
    const [linked, available] = await Promise.all([
      listAgentSkills(props.agentName),
      listPublicSkills(),
    ])
    associations.value = linked || []
    publicSkills.value = available || []
  } catch (reason) {
    error.value = reason?.message || 'Skill 关联加载失败'
  } finally {
    loading.value = false
  }
}

async function toggle(row) {
  if (busy.value.has(row.name)) return
  setBusy(row.name, true)
  error.value = ''
  try {
    if (row.association) await unlinkAgentSkill(props.agentName, row.name)
    else await associateAgentSkill(props.agentName, row.name)
    await refresh()
  } catch (reason) {
    error.value = reason?.message || 'Skill 关联更新失败'
  } finally {
    setBusy(row.name, false)
  }
}

watch(() => props.agentName, refresh, { immediate: true })
</script>

<template>
  <section class="skills-panel" aria-labelledby="agent-skills-title">
    <div class="head">
      <div>
        <h3 id="agent-skills-title">公共 Skill 关联</h3>
        <p>关联由 <code>agents/&lt;agent&gt;/skills/</code> 下的标准相对软链接决定，不读取或改写 AGENT.md。</p>
      </div>
      <button class="button" data-action="refresh" :disabled="loading || busy.size" @click="refresh">刷新</button>
    </div>

    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="loading" class="state">加载中…</p>
    <p v-else-if="!rows.length" class="state">暂无可关联的公共 Skill。</p>
    <div v-else class="table-scroll">
      <table>
        <thead><tr><th>Skill</th><th>公共状态</th><th>链接状态</th><th>运行时</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.name" :data-skill="row.name">
            <td><strong class="mono">{{ row.name }}</strong><small>{{ row.skill?.description || '公共包不可用' }}</small></td>
            <td>{{ row.skill?.status || 'missing' }}</td>
            <td :class="{ invalid: row.association?.linkStatus === 'invalid' }">
              {{ row.association?.linkStatus || '未关联' }}
              <small v-if="row.association?.error">{{ row.association.error.code }} · {{ row.association.error.message }}</small>
            </td>
            <td>{{ row.association?.discoverable ? '已进入下一请求 L1' : '不可发现' }}</td>
            <td>
              <button
                class="button"
                :class="{ danger: row.association }"
                data-action="association"
                :disabled="busy.has(row.name) || row.association?.linkStatus === 'invalid' || (!row.association && row.skill?.status === 'invalid')"
                @click="toggle(row)"
              >{{ busy.has(row.name) ? '处理中…' : (row.association ? '解除关联' : '关联') }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.skills-panel { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
h3 { margin: 0; font-size: 16px; }
p, small { color: var(--text-3); font-size: 12px; line-height: 1.5; }
small { display: block; margin-top: 4px; }
.state, .feedback { border: 1px solid var(--border); border-radius: var(--radius); padding: 14px; background: var(--bg-soft); }
.error, .invalid { color: var(--danger, #ff6b6b); }
.table-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--text-3); font-size: 11px; text-transform: uppercase; }
.button { border: 1px solid var(--border); border-radius: 7px; padding: 7px 10px; background: var(--bg-soft); color: var(--text-1); cursor: pointer; }
.button:disabled { opacity: .5; cursor: not-allowed; }
.danger { color: #ff8b78; }
.mono { font-family: var(--font-mono); }
</style>
