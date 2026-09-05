// 028-agent-skill-filter：Skill 选择器的纯函数（无 Vue 依赖，可 node --test 单测）。
// 选择集（selected）与显示集（visible）解耦——筛选只影响显示、不影响已勾选状态。

// 按 query 过滤已安装 Skill 列表。
// - query 去首尾空格；空串原样返回 list（FR-005：空搜索=显示全部）
// - 非 empty：保留 name OR description 不区分大小写包含 query 的项（FR-002）
// - description 缺失仅按 name 匹配，不报错（Edge）
export function filterSkills(list, query) {
  const q = (query || '').trim().toLowerCase()
  if (!q) return list || []
  const arr = list || []
  return arr.filter(s =>
    (s.name && String(s.name).toLowerCase().includes(q)) ||
    (s.description && String(s.description).toLowerCase().includes(q))
  )
}

// 已选但被当前筛选视野隐藏的数量（FR-004a 提示计数）。
// visible = filterSkills 输出；selected = 已勾选 name 数组。
export function hiddenSelectedCount(visible, selected) {
  const visNames = new Set((visible || []).map(s => s && s.name))
  return (selected || []).filter(name => !visNames.has(name)).length
}

// 全选当前视野：selected ∪ visible.name（去重并入）。视野外已选项不变（US3）。
export function selectAllVisible(visible, selected) {
  const visNames = (visible || []).map(s => s && s.name)
  return Array.from(new Set([...(selected || []), ...visNames]))
}

// 清空当前视野：selected − visible.name。视野外已选项不变（US3）。
export function clearVisible(visible, selected) {
  const visNames = new Set((visible || []).map(s => s && s.name))
  return (selected || []).filter(name => !visNames.has(name))
}

// 渲染集：showHidden=true 时把被隐藏的已选项临时纳入视野（选中态不变、不清 query）。
// 返回 [{name, description, hidden}]——hidden 标记该项原本被筛选隐藏，供 UI 样式/排序用。
export function renderSet(visible, list, selected, showHidden) {
  const visNames = new Set((visible || []).map(s => s && s.name))
  const selSet = new Set(selected || [])
  if (!showHidden) return (visible || []).map(s => ({ ...s, hidden: false }))
  // 并集：visible + selected 中被隐藏者（按 name 去重）
  const seen = new Set()
  const out = []
  for (const s of visible || []) {
    if (seen.has(s.name)) continue
    seen.add(s.name); out.push({ ...s, hidden: false })
  }
  for (const s of list || []) {
    if (seen.has(s.name)) continue
    if (!selSet.has(s.name)) continue // 仅纳入「被隐藏的已选项」
    seen.add(s.name); out.push({ ...s, hidden: true })
  }
  return out
}
