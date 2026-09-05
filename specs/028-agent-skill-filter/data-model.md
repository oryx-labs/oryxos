# Data Model: 新建 Agent 时的已安装 Skill 查询筛选

**Feature**: 028-agent-skill-filter | **Date**: 2026-09-01

**无新表、无新列、无新持久化实体**——本特性为前端展示层增强，全部数据来自既有 `/api/v1/skills`
响应；变更是「客户端瞬态 + 纯函数」，不落盘、不入库、不跨重启。

## 既有实体（只读消费，不改）

### Skill 列表项（`GET /api/v1/skills` 的 `data[]` 元素）

| 字段 | 用途 |
|------|------|
| `name` | Skill 名称（绑定键、列表显示、筛选匹配字段之一）|
| `description` | Skill 描述（列表 `title` 提示、筛选匹配字段之二）|

> 来源与字段集不变；本特性只读取这两字段做匹配，不要求后端增字段。`body`（SKILL.md 正文）按
> 渐进式披露（宪法 IV）在绑定阶段**不预载**，筛选更不碰它。

### Agent 的 Skill 绑定集合（既有）

- 新建页：`agentCreate.skills: string[]`（已勾选的 Skill name 数组）。
- 编辑页：`agentBinding.selected: string[]`（既有绑定的 Skill name 数组，`PUT /agents/{name}/skills` 落盘）。

落盘形态不变——仍存为 `agents/<name>/skills/<skill>` 相对软连接（012-agent-skill-links 实现）。

## 新增：客户端瞬态（`App.vue` 内 reactive，不落盘）

### `skillFilter`（新，reactive 对象）

| 字段 | 类型 | 语义 |
|------|------|------|
| `query` | `string` | 当前搜索关键词；空串 = 显示全部（行为与未加搜索前一致）|
| `showHidden` | `boolean` | 是否把「被当前筛选隐藏的已选项」临时纳入视野（FR-004a）|

生命周期：浏览器内瞬态，不持久、不跨视图切换保留语义保证（新建/编辑视图互斥，进入时是否重置
由实现定，但 `query` 清空后行为须与未加搜索一致——FR-005）。不落 SQLite、不入 `oryxos.db`。

## 新增：纯函数（旁挂，可单测，无 Vue 依赖）

### `filterSkills(list, query) → Skill[]`

- 输入：`list`（全部已安装 Skill）、`query`（关键词，可含首尾空格）。
- 输出：命中子集。
- 规则：`query` 去首尾空格；空串 → 原样返回 `list`（FR-005）；非空 → 保留 `name` 或 `description`
  不区分大小写包含 `query.trim()` 的项（FR-002）；`description` 缺失仅按 `name` 匹配，不报错（Edge）。

### `hiddenSelectedCount(visible, selected) → number`

- 输入：`visible`（`filterSkills` 输出）、`selected`（当前已勾选 name 数组）。
- 输出：`selected` 中不在 `visible.name` 集合内的数量（FR-004a 提示计数）。

### 批量动作（P2，操作既有 `selected` 数组）

- `selectAllVisible(visible, selected)`：`selected ∪ visible.name`（去重并入）。
- `clearVisible(visible, selected)`：`selected − visible.name`。
- 作用域 = 当前视野；视野外已选项不受影响（US3 场景 1/2）。

## 渲染集推导（模板内 computed，非持久）

```
visible      = filterSkills(skills.data, skillFilter.query)
hiddenCount  = hiddenSelectedCount(visible, selected)
renderSet    = skillFilter.showHidden
               ? dedupe(visible ∪ selected中已隐藏者, by name)
               : visible
```

`renderSet` 是渲染列表的来源；`v-model` 始终绑既有 `agentCreate.skills` / `agentBinding.selected`——
**选择集与显示集解耦**是 FR-004（隐藏不取消）的实现根。
