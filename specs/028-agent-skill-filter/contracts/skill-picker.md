# Contract: Skill-Picker 查询筛选行为（UI）

**Feature**: 028-agent-skill-filter | **Date**: 2026-09-01

本特性不暴露新 REST 端点、不暴露新 CLI 命令、不暴露新公共 Java 接口——**契约是管理台前端
skill-picker 的 UI 行为契约**，覆盖新建 Agent 视图与 Agent 详情 > 基本信息编辑绑定区两处。
消费的既有后端接口不变（`GET /api/v1/skills`、`PUT /api/v1/agents/{name}/skills`）。

## 复用接口（不变，仅列出以界定边界）

| 方法 | 路径 | 用途（本特性视角）|
|------|------|------------------|
| `GET` | `/api/v1/skills` | 既有；取已安装 Skill 列表（`name` + `description`），进入视图时已加载 |
| `PUT` | `/api/v1/agents/{name}/skills` | 既有；保存绑定（body `{skills: [...]}`）——本特性不改其请求/响应 |

> 筛选不产生任何网络调用；保存绑定仍走既有端点，落盘与启动恢复校验（dangling/escaped/
> invalid-target/name-mismatch/stale-reference，012 实现）不变。

## skill-picker 行为契约

### 输入

- `list`: 全部已安装 Skill（`name` + `description`，来自 `GET /api/v1/skills`）。
- `selected`: 当前已勾选的 Skill name 数组（新建页 `agentCreate.skills` / 编辑页 `agentBinding.selected`）。
- `query`: 搜索框文本（瞬态）。
- `showHidden`: 是否临时纳入被隐藏的已选项（瞬态，默认 `false`）。

### 输出 / 渲染

1. 搜索框上方/旁边渲染；下方渲染勾选列表。
2. `query` 为空 → 列表显示全部 `list`，行为与未加搜索前一致。
3. `query` 非空 → 列表收窄为 `name` 或 `description` 不区分大小写包含 `query.trim()` 的项；
   不命中项不渲染。
4. 结果为空 → 显示明确的「无匹配 Skill」提示，而非空白。
5. 被筛选隐藏的已选项**保持选中**（`v-model` 绑 `selected`，渲染集换成子集，不取消隐藏项）。
6. 当 `hiddenSelectedCount > 0` → 显示「当前筛选隐藏了 N 项已选」提示；点击置 `showHidden=true`，
   被隐藏的已选项纳入渲染集（选中态不变，不清空 `query`）。`hiddenCount` 随 `query`/`selected` 实时刷新。

### 批量动作（P2）

- 「全选当前」：把当前视野内所有项并入 `selected`；视野外已选项不变。
- 「清空当前」：从 `selected` 移除当前视野内所有项；视野外已选项不变。
- 当前视野为空时两动作禁用或无效果。

### 保存语义（不变）

- 新建页：提交创建时 `agentCreate.skills` 生效，含所有勾选项（无论筛选中是否可见）。
- 编辑页：`保存绑定` 走 `PUT /agents/{name}/skills`，body 含全部 `agentBinding.selected`（无论筛选中是否可见）。

## 不变量（契约核心）

- **筛选只影响显示，不影响 `selected`**：`query`/`showHidden` 的任意变化，不得增减 `selected` 中的元素。
- **后端契约零改动**：`GET /api/v1/skills` 与 `PUT /agents/{name}/skills` 的请求/响应 schema 不变。
- **渐进式披露不动**：筛选不读取、不预载 SKILL.md 正文（`body`）。
