# Data Model: Agent 内 Skill 渐进式加载与生命周期管理

本特性以文件系统为唯一真相源，不新增数据库表。以下对象分为持久化目录、派生领域对象和请求期并发对象。

## 1. 文件系统布局

```text
.oryxos/
├── agents/
│   └── <agent>/
│       ├── AGENT.md
│       └── skills/
│           ├── legacy-note.md              # 旧版，unmanaged，不进入本特性
│           └── <skill>/                    # 受管包；目录名必须等于 metadata.name
│               ├── SKILL.md                # 必填
│               ├── .oryxos-disabled        # 可选，零字节保留 marker
│               ├── .oryxos-origin.yml      # 可选，系统导入来源
│               ├── scripts/                # 可选 L3
│               ├── references/             # 可选 L3
│               └── assets/                 # 可选 L3
├── .staging/
│   └── skill-import/
│       └── <uuid>/
│           ├── upload.zip
│           └── unpacked/
└── archive/
    └── .skills/
        └── <agent>/<UTC>-<uuid>/
            ├── archive.yml
            └── package/                    # 从活动目录原子移入
```

保留文件 `.oryxos-disabled`、`.oryxos-origin.yml` 只能由 OryxOS 创建；上传包包含它们时拒绝。disabled marker 必须是零字节普通文件；origin 必须是至多 4 KiB 的安全 YAML 普通文件，任一保留文件为链接、特殊文件或格式损坏都使候选 invalid。直接子目录只有在包含 `SKILL.md` 或任一保留 marker 时才是受管候选；其他目录按 legacy/unmanaged 忽略。归档和 staging 目录不参与运行时发现。

## 2. AgentName（所属键）

- `value`: `[A-Za-z0-9_-]+` 的原始 Agent 名；必须同时等于 `.oryxos/agents/<value>` basename、`AGENT.md` frontmatter `name` 和 `Profile.name`。
- `lockKey`: `value.toLowerCase(Locale.ROOT)`；只用于锁表，使大小写不敏感文件系统上的别名串行化，不改变展示名或目录解析。
- 所有目录解析先通过 `AgentName.parse`，再 resolve + parent/NOFOLLOW 校验；不得各模块复制 regex。

## 3. SkillMetadata

从单个 `SKILL.md` frontmatter 派生的不可变值对象。

| 字段 | 类型 | 规则 |
|---|---|---|
| `name` | String | 必填；1–64；`^[a-z0-9]+(?:-[a-z0-9]+)*$`；与父目录名一致 |
| `description` | String | 必填；trim 后 1–1024 字符 |
| `license` | String? | 可选；仅展示/保留 |
| `compatibility` | String? | 可选；最多 500 字符 |
| `metadata` | Map<String,String> | 可选；不进入 L1 之外的执行决策 |
| `allowedTools` | String? | 可选、实验性；仅展示，不能扩展 Profile.tools |
| `entryPath` | Path | 内部真实路径；只在 prompt/tool 参数内使用，不直接出 REST |
| `relativeEntry` | String | Agent 相对路径 `skills/<name>/SKILL.md` |

不变量：构建成功即表示 frontmatter 本身合法；解析必须在闭合 frontmatter 后停止，不读取正文。

## 4. SkillDescriptor

管理面的单个受管包视图，允许描述 invalid 包。

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentName` | String | 规范化后的所属 Agent |
| `directoryName` | String | `skills/` 下直接子目录名，也是管理 member 的稳定键；valid 时等于 metadata.name |
| `metadata` | SkillMetadata? | invalid（含受管候选缺入口）时可能为空 |
| `status` | SkillStatus | `ENABLED` / `DISABLED` / `INVALID` |
| `configuredEnabled` | boolean | `.oryxos-disabled` 不存在为 true；与内容是否合法分离 |
| `source` | SkillSource | `UPLOAD` / `WORKSPACE` |
| `updatedAt` | Instant | 包内最近修改时间的安全汇总 |
| `validationError` | SkillValidationError? | 稳定 reason code + 不含绝对路径的消息 |
| `relativeEntrypoint` | String? | Agent 相对路径 |
| `resources` | List<String> | 包内内容文件的相对路径，字典序；排除 `.oryxos-*` 保留状态文件 |
| `fileCount` | int | 内容普通文件数，不含保留状态文件 |
| `totalBytes` | long | 内容普通文件实际总字节数，不含保留状态文件 |
| `catalogIncluded` | boolean | 当前聚合预算内是否会进入 L1 |

状态推导：

1. 包结构或内容校验失败 → `INVALID`；
2. 校验通过且 marker 存在 → `DISABLED`；
3. 校验通过且 marker 不存在 → `ENABLED`。

只有第 3 类且 `catalogIncluded=true` 的项进入运行时快照。一个 invalid 包不会使集合扫描失败。

## 5. SkillSnapshot

一次顶层请求的不可变 L1 集合。

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentName` | String | 所属 Agent |
| `capturedAt` | Instant | 快照创建时间，仅用于诊断 |
| `skills` | List<SkillMetadata> | 只含 enabled + budget included，按 name 升序 |
| `renderedChars` | int | L1 渲染字符数 |
| `omittedCount` | int | 仅人工超限时可能大于 0 |

不变量：列表不可修改；同一 `AgentService.process` 的所有 ReAct 轮次引用同一个对象；不含正文、resource 内容或脚本输出。

## 6. SkillLease

请求期的 `AutoCloseable` 读租约。

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentName` | String | 锁键 |
| `snapshot` | SkillSnapshot | 在取得读锁后构建 |
| `closed` | boolean | 防重复释放 |

`AgentSkillLockRegistry` 为每个规范化 Agent 名持有一把 fair `ReentrantReadWriteLock`：

- `openRequest(agent)`：取得读锁、扫描 snapshot，返回 `SkillLease`；
- `withWriteLock(agent, operation)`：管理变更的最终重检和原子切换；
- 锁对象不从 registry 删除，避免同名 Agent 的旧持有者与新锁并存。

这是一致性锁，不是分布式锁。进程外直接改文件不受协调；下一次扫描仍必须检测变化。

## 7. SkillLimits

从 `oryxos.skills.*` 配置转换出的 core 纯 Java值对象。

| 字段 | 默认值 |
|---|---:|
| `maxArchiveBytes` | 10 MiB |
| `maxExpandedBytes` | 25 MiB |
| `maxFileBytes` | 5 MiB |
| `maxSkillMarkdownBytes` | 256 KiB |
| `maxFrontmatterBytes` | 64 KiB |
| `maxYamlNestingDepth` | 8 |
| `maxEntries` | 128 |
| `maxDepth` | 8 |
| `maxPathChars` | 512 |
| `maxExpansionRatio` | 100 |
| `maxSkillsPerAgent` | 64 |
| `maxCandidatesPerAgent` | 1,024 |
| `maxCatalogChars` | 12,000 |
| `stagingTtl` | 24 h |

所有数值启动时必须大于 0，且 `maxFrontmatterBytes <= maxSkillMarkdownBytes <= maxFileBytes <= maxExpandedBytes`、`maxSkillsPerAgent <= maxCandidatesPerAgent`。YAML duplicate keys/custom tags/aliases 固定禁用，不作为可放宽配置。

## 8. SkillOrigin

`.oryxos-origin.yml` 的持久化内容：

```yaml
schemaVersion: 1
sourceType: upload
originalFilename: weather-skill.zip
importedAt: 2026-07-22T10:30:00Z
```

`originalFilename` 仅保留清洗后的 basename 和可打印字符，不作为目录名或 Skill 身份。没有该文件的手工包派生为 `source=workspace`。

## 9. ArchivedSkill

`archive.yml` 由固定字段安全序列化（不得字符串拼 YAML），持久化内容：

```yaml
schemaVersion: 1
agent: ops-agent
skill: weather
source: upload
deletedAt: 2026-07-22T11:00:00Z
originalRelativePath: agents/ops-agent/skills/weather
```

归档事件目录名使用 UTC 基本时间 + UUID；Skill/directoryName 只写入 `archive.yml`，不参与归档路径，避免手工 invalid 目录名成为路径段。不依赖毫秒时间戳唯一性。`package/` 存在才表示归档完成；启动清理可以移除超时且没有 `package/` 的空事件。

## 10. 状态转换

| 当前状态 | 操作 | 前置校验 | 结果 |
|---|---|---|---|
| 不存在 | import | Agent 存在；ZIP/metadata/聚合预算合法；目标同名路径（含 unmanaged 目录）不存在 | 原子发布，默认 `ENABLED` |
| ENABLED | disable | Skill 存在 | 原子创建 marker → `DISABLED` |
| DISABLED | disable | Skill 存在 | 幂等返回 `DISABLED` |
| DISABLED | enable | 包重新完整校验；聚合预算可容纳 | 删除 marker → `ENABLED` |
| ENABLED | enable | Skill 合法 | 幂等返回 `ENABLED` |
| INVALID | disable | 目录存在 | 创建 marker；状态仍 `INVALID`，configuredEnabled=false |
| INVALID | enable | 必须重新校验成功 | 成功才删 marker；失败保持原文件与 marker |
| 任意存在状态 | delete | 归档目标可创建且支持原子移动 | 活动包消失，归档事件完成 |
| 不存在 | enable/disable/delete | — | 404，无文件副作用 |

同名 import 返回 409，不覆盖 enabled、disabled、invalid 或 unmanaged 的现有目录路径。

## 11. 一致性边界

- 管理动作在写锁外完成上传、解包和预校验，在写锁内完成 Agent/冲突/预算重检及单次原子切换，缩短阻塞时间。
- 管理 list/get 在短读锁内完成整次扫描和统计，构造不可变 descriptor 后释放，避免与 marker/目录切换交错。
- 删除在写锁内以 `NOFOLLOW_LINKS`/real-path containment 验证 `.oryxos/archive/.skills/<agent>` 全父链，再进行归档事件写入和原子移动；archive symlink 必须安全失败且活动包保持原位。
- 写锁排队后，fair lock 阻止新请求无限插队；已持有读租约的请求可完成 L2/L3。
- generic Workspace API、`AgentLifecycleService.saveFiles`/`AgentStore.writeAll` 对受管 Skill 子树写入时必须取得同一写锁并原子替换目标文件；直接 shell/scp 编辑属于运维旁路，不能获得请求内保证。
- disabled/invalid 只控制 OryxOS 的 L1 发现与正常渐进加载；它不是通用 shell 的操作系统级文件隔离标签。
