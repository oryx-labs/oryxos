# Data Model: Agent Skill 绑定与渐进式加载

## InstalledSkill

`.oryxos/skills/<name>/` 中唯一的本机内容实体；公共/私有不产生不同副本。

| Field | Type | Rules |
|---|---|---|
| `name` | String | `[A-Za-z0-9_-]+`；等于目录名与 frontmatter `name` |
| `description` | String | 非空、单行目录说明；Level 1 prompt 可见 |
| `body` | String | 非空；仅 Level 2 `read_file` 可见 |
| `directory` | Path | `.oryxos/skills/<name>`；实体目录本身不得是越界链接 |
| `entrypoint` | Path | `<directory>/SKILL.md`，普通可读文件 |
| `resources` | files | 可选 references/templates/scripts；Level 3 按需读取 |

### State transitions

```text
ABSENT ── create/import ──> INSTALLED_VALID
                              │
                              ├─ external/manual damage ─> INSTALLED_INVALID
                              │
                              └─ archive(no references) ─> ARCHIVED
```

- `INSTALLED_VALID`: 可进入 catalog 的 installed 交集、可绑定。
- `INSTALLED_INVALID`: 可诊断，不可新绑定、不进入 prompt。
- `ARCHIVED`: 位于 `.oryxos/archive/skills/<name>-<timestamp>/`；不进入安装扫描、catalog 交集或 prompt。

## SkillCatalogEntry

外部候选列表返回的瞬时元数据，不是安装实体或绑定。

| Field | Type | Rules |
|---|---|---|
| `name` | String | 全工作区候选名称；同名公共/私有项视为冲突 |
| `description` | String | 作者模型选择依据 |
| `visibility` | Enum | `PUBLIC` / `PRIVATE`；仅标签，本阶段不执行 ACL |
| `source` | String | 外部列表提供的来源标识 |
| `installed` | boolean | 当前是否存在同名合法 InstalledSkill |

关系：只有 `SkillCatalogEntry.name == InstalledSkill.name && installed=true` 的项才能成为生成候选；访问
过滤由 catalog adapter 在返回前完成。

## AgentSkillBinding

Agent 对 InstalledSkill 的唯一持久绑定事实。

| Field | Type | Rules |
|---|---|---|
| `agentName` | String | 安全目录段；活跃或归档 Agent 目录必须有效 |
| `agentState` | Enum | `ACTIVE` / `ARCHIVED` |
| `skillName` | String | 安全目录段，等于链接名 |
| `linkPath` | Path | 活跃时 `.oryxos/agents/<agent>/skills/<skill>` |
| `linkTarget` | Path | 精确相对路径 `../../../skills/<skill>` |
| `installedSkill` | reference | 必须指向同名 `INSTALLED_VALID` 实体 |

### Operations

- `bind(agent, skill)`: 先验证 Agent、catalog/安装状态、目标槽位；同一合法链接幂等。
- `unbind(agent, skill)`: 只删除受控链接；不存在幂等；普通文件/目录冲突时拒绝代删。
- `replace(agent, desired)`: 全量预校验后原子同步；失败回滚本次增删。
- `inspect(agent)`: 返回有效 binding + issues，按 skillName 排序。
- `references(skill)`: 返回所有 ACTIVE/ARCHIVED 引用；归档 Agent 链接仍是有效引用。

## BoundSkillDescriptor

Level 1 system prompt 的最小目录项。

| Field | Type | Visible to model |
|---|---|---|
| `name` | String | yes |
| `description` | String | yes |
| `linkPath` | lexical absolute Path | 间接；用于资源基准目录 |
| `skillFile` | lexical absolute Path | yes，必须保留 Agent 本地链接路径 |
| `body` | absent | no |
| `resources` | absent | no |

路径校验使用 realpath，但暴露给模型的是 Agent 本地 lexical absolute path，使后续相对资源仍位于该
Agent 的能力视图中。

## BindingInspection

一次无副作用扫描快照。

| Field | Type | Rules |
|---|---|---|
| `bindings` | List&lt;BoundSkillDescriptor&gt; | 仅有效项，按名称稳定排序 |
| `issues` | List&lt;SkillBindingIssue&gt; | 无效项，稳定排序 |

没有有效绑定时，ContextLoader 不输出 Skill 标题。

## SkillBindingIssue

| Field | Type | Meaning |
|---|---|---|
| `agentName` | String | 所属 Agent 或归档目录标识 |
| `agentState` | Enum | `ACTIVE` / `ARCHIVED` / `INVALID` |
| `entryName` | String | `skills/` 条目或 legacy 字段标识 |
| `path` | absolute Path | 问题位置；不得包含文件内容 |
| `type` | Enum | 五类之一 |
| `message` | String | 可展示、去 CR/LF 的原因 |

### Classification

- `DANGLING`: 相对链接的目标不存在。
- `ESCAPED`: 绝对链接、词法目标越过安装根、链式解析后的真实目标越过安装根。
- `INVALID_TARGET`: 非软连接、替代相对目标、目标不是目录、缺/不可读 `SKILL.md`、缺描述或空正文。
- `NAME_MISMATCH`: 链接名、安装目录名、frontmatter name 任意不一致。
- `STALE_REFERENCE`: 未迁移的 top-level `skills:`，或没有有效 `AGENT.md` 的活跃目录残留绑定。

归档 Agent 中仍能解析到已安装 Skill 的链接不是 stale。

## SkillReference

Skill 归档冲突的结构化返回项。

| Field | Type | Rules |
|---|---|---|
| `agentName` | String | Agent 定义中的名称 |
| `state` | Enum | `ACTIVE` / `ARCHIVED` |
| `directoryName` | String | 实际目录名，保留时间戳后缀定位 |
| `linkPath` | absolute Path | 引用链接位置 |

## SkillArchive

| Field | Type | Rules |
|---|---|---|
| `name` | String | 原安装名称 |
| `archivedPath` | Path | `.oryxos/archive/skills/<name>-<timestamp>/` |
| `archivedAt` | Instant | 归档提交时间 |

状态迁移必须在引用列表为空时发生；移动整个目录，不覆盖既有归档。成功后从安装 Registry 移除。

## GeneratedAgentDraft

生成阶段的瞬时 sidecar，不写入 Agent 目录。

| Field | Type | Rules |
|---|---|---|
| `files` | Map&lt;relativePath,String&gt; | 必须含 AGENT.md；禁止 `skills:` 与 `skills/**` |
| `requiredSkills` | List&lt;String&gt; | 用户明确选择，结果必须全部保留 |
| `suggestedSkills` | List&lt;String&gt; | 作者模型从本次可选 catalog 交集中补充 |
| `bindingSkills` | List&lt;String&gt; | required ∪ suggested，去重稳定排序 |

保存时 `bindingSkills` 必须重新验证，不能信任草稿或浏览器状态；保存成功后该 DTO 可丢弃。

## LegacySkillMigration

单个 Agent 的启动迁移事务。

| Field | Type | Rules |
|---|---|---|
| `agentDirectory` | Path | 当前 Agent 目录 |
| `legacySkills` | List&lt;String&gt; | 原 top-level frontmatter 值，必须是字符串列表 |
| `preexistingBindings` | Set&lt;String&gt; | 已存在合法链接，迁移保留 |
| `createdBindings` | Set&lt;Path&gt; | 本次新增，用于异常回滚 |
| `status` | Enum | `NOT_NEEDED`, `MIGRATED`, `FAILED` |
| `message` | String | 失败原因或迁移摘要 |

### Commit order

1. 读取原始字节并全量校验。
2. 写临时 AGENT.md，创建/验证临时链接。
3. 原子移动新增链接到最终名。
4. 最后原子替换 AGENT.md。
5. 异常时删除本次临时项/新增链接，原 AGENT.md 保持字节一致。

## RealPathProjection

真实路径边界校验的内部值。

| Field | Type | Rules |
|---|---|---|
| `input` | Path | 调用者原始目标 |
| `absoluteNormalized` | Path | 词法绝对规范路径 |
| `existingAncestorReal` | Path | 最近存在节点的 `toRealPath()` |
| `unresolvedSuffix` | Path segments | 尚不存在的普通尾段 |
| `projectedReal` | Path | ancestor real + suffix，用于边界比较 |

dangling link、link cycle 或祖先无法解析时不产生 projection，直接拒绝。

## Persistence and ownership

- 不新增数据库表。
- `SkillRegistry` 仅为已安装实体 CRUD/管理视图，可重建；不保存 Agent 绑定。
- `SkillCatalogEntry` 是外部查询结果，不持久化 owner/scope/ACL。
- AgentSkillBinding 只由软连接持久化。
- Tool 读取正文继续使用现有 `tool_invocations` 审计；无专用 Skill 使用表。
