# Research: 新建 Agent 时的已安装 Skill 查询筛选

**Feature**: 028-agent-skill-filter | **Date**: 2026-09-01

技术上下文无 NEEDS CLARIFICATION；本文记录关键技术裁决，全部基于实地摸底（`oryxos-web/src/main/frontend/src/App.vue`
的 `skills`/`agentCreate`/`agentBinding` reactive 结构、2 处 `skill-picker` 模板、`loadSkills()` 走
`GET /api/v1/skills`、`package.json` 的 Vue 3.5 + Vite 6 + `node --test` 现状、012-agent-skill-links
的软连接绑定与渐进式披露口径）。

## R1. 筛选为纯客户端 computed，不碰后端

**Decision**: 筛选只在前端做：`skills.data`（既有 `loadSkills()` 已在进入新建/编辑视图时加载完毕）
经一个 Vue `computed` 按 `skillFilter.query` 过滤后渲染；不新增、不调用任何后端接口。

**Rationale**: 已安装 Skill 全量已在内存（量级本就有限——本地 `.oryxos/skills/<name>/` 目录实体），
客户端 substring 过滤在 200 项下 <16ms（SC-003），上服务端检索既无必要也违背「数据已在手边」
的事实。零新接口 = 零新契约、零新鉴权面、零新审计点（宪法 V/VI 不波及）。

**Alternatives considered**: 新增 `GET /api/v1/skills?q=…` 服务端检索（否——为本地内存列表加网络
往返属过度设计，且 012 的 `/api/v1/skills` 契约已稳定，不动为上）。

## R2. 筛选态归属：单一共享 `skillFilter` reactive 对象 + 纯函数

**Decision**: 新增一个 reactive 对象 `skillFilter = reactive({ query: '', showHidden: false })`，
与既有 `skills`/`agentCreate`/`agentBinding` 同级；两个纯函数旁挂：
`filterSkills(list, query)`（去首尾空格、不区分大小写、按 name OR description 命中）与
`hiddenSelectedCount(filtered, selected)`（返回 `selected` 中不在 `filtered` 视野内的数量）。两处
skill-picker（新建 `agentCreate.skills`、编辑 `agentBinding.selected`）共用此态与函数。

**Rationale**: 新建视图与详情编辑视图在 UI 上互斥（同一面板不同状态，不能同时开），故单一共享
`skillFilter` 不会互相污染；但即便如此仍把「过滤算法」抽成纯函数，便于单测（R4）与未来复用
（如 KB 绑定选择器同型痛点可复用 `filterSkills`）。`showHidden` 布尔承载 FR-004a 的「点击临时纳入视野」。

**Alternatives considered**: 每处各一个 query ref（否——两份过滤逻辑重复，且 `filterSkills` 无法
单测复用）；抽取 Vue 组件 `<SkillPicker v-model="selected" :list="skills.data">`（否——现状是单文件
App.vue、无组件抽取先例，为小特性引入组件边界 + props/emit 得不偿失，与 chat-scroll 等既有
「纯函数旁挂」风格不一致）。

## R3. 「已选不丢」+ 隐藏计数：选择集合与显示集合解耦

**Decision**: 勾选状态始终存于既有 `agentCreate.skills` / `agentBinding.selected`（数组），与筛选
**完全解耦**。模板渲染 `filterSkills(skills.data, skillFilter.query)` 得到 `visible`；`v-model` 绑定
的仍是上述既有数组——故被筛选隐藏的已选项不会被取消（FR-004）。`hiddenSelectedCount(visible,
selected)` 驱动「N 项已选被当前筛选隐藏」提示；点提示置 `skillFilter.showHidden = true`，此时
渲染集改为「`visible` ∪ `selected 中被隐藏者`」，选中态仍由原数组承载（FR-004a）。改 query 或
勾选变化时 `showHidden` 不自动重置（让管理员显式掌控视野），但隐藏计数实时刷新（Edge「计数实时更新」）。

**Rationale**: 选择集与显示集解耦是「筛选不丢已选」的最小正确实现——只要 `v-model` 绑定不变、
渲染列表换成过滤后的子集，Vue 的 checkbox 语义自然保证「隐藏不等于取消」。`showHidden` 用并集
渲染而非「清空 query」——管理员要的是「看一眼我选了啥」而不丢当前关键词。

**Alternatives considered**: 隐藏时把已选项「钉顶」常驻可见（否——与 R2 的「收窄」语义冲突，且
列表项会既在视野又在钉顶区造成困惑，Clarifications Q2 已选 A 方案即「提示 + 纳入」非「钉顶」）。

## R4. 测试：纯函数单测 + 手测端到端，不引 @vue/test-utils

**Decision**: 为 `filterSkills` / `hiddenSelectedCount` / 批量勾选纯函数（R2/R3）补
`src/skill-filter.test.js`，沿用既有 `node --test`（`chat-scroll.test.js` 同模式，vanilla JS、
不依赖 Vue 运行时）。UI 交互（输入收窄、提示点击、批量按钮）走 quickstart 手测——与现状一致
（现有前端也无组件测试框架）。

**Rationale**: 过滤/计数/批量是纯逻辑，单测覆盖最高 ROI；Vue 模板绑定行为（`v-model` 不丢已选）
由 quickstart 端到端验收兜底。引入 `@vue/test-utils` + `jsdom` 是为一个小特性加一整套 dev 依赖
与构建期开销，不值。

**Alternatives considered**: 用 Playwright 对 `/admin/` 做浏览器自动化验收（否——仓库尚无此基建，
引入成本远超特性本身；手测 quickstart 足够覆盖 SC-001~004）。

## R5. 批量「全选当前 / 清空当前」作用域 = 当前视野

**Decision**: P2 的「全选当前」把 `visible`（当前筛选视野内）全部并入 `selected`；「清空当前」从
`selected` 移除所有在 `visible` 内的项。视野外已选项不受影响（US3 场景 1/2）。空结果时两按钮
禁用（US3 场景 3）。

**Rationale**: 「当前」=用户当前能看到的集合，是唯一直觉一致的作用域；「全选全部已安装」会让
已选集膨胀到不可控，「全选所有」与渐进式披露的「按需绑定」精神相悖（宪法 IV——Agent 只绑它
要用的 Skill）。

**Alternatives considered**: 「全选全部已安装」（否，如上）；批量动作延后不做（否——P2 已纳入，
且实现成本在纯函数层级很低）。
