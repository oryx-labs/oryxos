# Data Model: 公共 Skill 与 Agent 软链接关联

本特性以文件系统为唯一真相源，不新增数据库表。公共包、全局启停状态与 Agent 关联是三个独立维度；`AGENT.md` 中的 `skills:` 不参与任何状态推导。

## 1. 文件系统布局

```text
.oryxos/
├── skills/
│   └── <skill>/
│       ├── SKILL.md                 # 必填，公共内容只保存一份
│       ├── .oryxos-disabled         # 可选，全局禁用 marker
│       ├── .oryxos-origin.yml       # 可选，受信导入来源
│       ├── references/              # 可选 L3
│       ├── scripts/                 # 可选 L3
│       └── assets/                  # 可选 L3
├── agents/
│   └── <agent>/
│       ├── AGENT.md
│       └── skills/
│           └── <skill> -> ../../../skills/<skill>
├── .staging/skill-import/<uuid>/    # 上传与解包暂存，不参与发现
└── archive/.skills/<UTC>-<uuid>/
    ├── archive.yml
    └── package/                     # 公共包完整归档
```

标准关联只能是系统创建的相对软链接，link text 必须逐字等于 `../../../skills/<skill>`。工作区整体移动后链接仍有效。公共包根、Agent 根、`skills/` 父目录、归档和 staging 的任何父链都不得是软链接。

## 2. SkillName 与 SkillVersion

- `SkillName.value`: 1–64 字符，`^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`。
- `SkillVersion.value`: 可选；出现时为 1–32 字符，`^[a-zA-Z0-9._\-+~]{1,32}$`。它未来可能进入 `<skill version="...">` XML 属性，因此拒绝空白、引号、尖括号和其它 breakout 字符。
- 必须同时等于 `.oryxos/skills/<value>` basename 与 `SKILL.md` frontmatter `name`。
- 任何 API path member 必须先解析为 `SkillName`，再进行 `resolve`、normalize 与 `NOFOLLOW_LINKS` 校验；不得直接拼路径。
- 大小写或 Unicode 规范化后冲突的候选拒绝导入。

## 3. PublicSkillPackage

公共包的派生管理视图：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | SkillName | 公共身份 |
| `metadata` | SkillMetadata? | invalid 时可能为空 |
| `status` | `ENABLED / DISABLED / INVALID` | 内容校验优先于 marker |
| `configuredEnabled` | boolean | `.oryxos-disabled` 不存在为 true |
| `source` | `UPLOAD / GITHUB / WORKSPACE` | 来源展示，不影响权限 |
| `relativeEntrypoint` | String? | `skills/<skill>/SKILL.md`，REST 不返回绝对路径 |
| `resources` | List<String> | 包根相对普通文件，排除保留文件 |
| `fileCount` / `totalBytes` | int / long | 有界资源统计 |
| `linkedAgents` | List<String> | 请求时扫描所得，按 Agent 名排序 |
| `validationError` | Error? | 稳定 reason code，不含绝对路径 |

状态推导：结构或内容非法为 `INVALID`；合法且 marker 存在为 `DISABLED`；合法且 marker 不存在为 `ENABLED`。全局禁用不删除任何关联链接；invalid/disabled 包都不进入运行时 L1。

## 4. SkillAssociation

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentName` | String | Agent 规范名称 |
| `skillName` | SkillName | 由链接 basename 推导 |
| `linkPath` | Path | 内部路径，REST 只暴露相对路径 |
| `rawTarget` | String | `readSymbolicLink` 原始文本 |
| `status` | `VALID / INVALID` | 是否为标准关联 |
| `skillStatus` | SkillStatus? | 目标公共包状态；不存在时为空 |
| `discoverable` | boolean | `VALID && skillStatus == ENABLED` |
| `error` | Error? | 错误链接、悬空目标或非法包原因 |

标准关联的全部不变量：

1. `.oryxos/agents/<agent>/skills/<skill>` 本身是软链接；
2. `rawTarget` 逐字等于 `../../../skills/<skill>`；
3. 解析后的 normalize 路径等于公共包目标，且真实公共包仍位于 `.oryxos/skills`；
4. Agent 的 `skills/` 父目录是真实目录，非链接；
5. 链接名、目标名与 metadata name 一致。

手工创建的绝对链接、不同层级链接、越界链接、悬空链接和指向非标准目标的链接一律不跟随、不加载；管理列表可显示为 invalid 供修复。普通解除关联只删除经上述校验的标准链接，不删除目标包或真实目录。

## 5. SkillManifest、SkillMetadata 与 SkillSnapshot

`SkillManifest` 是 `SKILL.md` frontmatter 的安全反序列化结果：

| 字段 | 类型 | 规则 |
|---|---|---|
| `name` | SkillName | 必填，且与包目录一致 |
| `description` | String | 必填，trim 后 1–1024 字符 |
| `version` | SkillVersion? | 可选，出现时通过安全 grammar |
| `license` | String? | 管理展示 |
| `compatibility` | String? | 最多 500 字符 |
| `metadata` | Map<String,String> | 有界、安全展示；legacy 嵌套字段不进入顶层能力 |
| `allowedTools` | String? | 只展示，不授予权限 |
| `activation` | Activation? | 反序列化后按统一上限过滤/截断；不触发自动执行 |
| `requires` | Requires? | `skills` 最多保留 10 项；其它声明不执行 gating |

`metadata.openclaw.requires` 只产生结构化 legacy WARN；它不会阻断合法包，也不会静默赋值给顶层 `requires`。

`SkillMetadata` 是供 catalog/L1 使用的派生视图，包含上述展示字段与内部 entry path。只有 `name`、`description` 与入口路径进入 L1；`allowed-tools`、`activation` 和 `requires` 都不能扩展 Agent 的 Tool 权限。

`SkillSnapshot` 是一次顶层请求的不可变值：

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentName` | String | 所属 Agent |
| `capturedAt` | Instant | 诊断时间 |
| `skills` | List<SkillMetadata> | 只含有效关联且全局 enabled 的包，按 name 排序 |
| `renderedChars` | int | L1 字符数 |
| `omittedCount` | int | 超预算时被确定性省略的数量 |

同一轮 ReAct 始终使用同一个 snapshot。L1 不含 `SKILL.md` 正文；命中后由既有 `read_file` 读取 L2，正文需要时再读取/执行 L3。所有 L2/L3 操作仍经过 Tool 权限、SandboxChecker 与审计。

## 6. 并发对象

`SkillGraphCoordinator` 维护一把 fair 全局图谱读写锁，并复用按规范 Agent 名建立的 fair `ReentrantReadWriteLock`：

- 顶层请求：图谱读锁 + 当前 Agent 读锁，构建 snapshot 后持有到本轮 ReAct 完成；
- 单 Agent 关联/解除：图谱写锁 + 该 Agent 写锁；
- 全局启停：图谱写锁；
- 普通删除：图谱写锁下扫描所有 Agent；
- 强制删除：图谱写锁 + 扫描结果中全部 Agent 写锁，按规范名排序取得、逆序释放。

锁对象不从 registry 删除。禁止在持有 Agent 锁后再申请图谱锁，避免锁顺序反转。本期为单实例文件系统锁；进程外直接修改只能在下一次安全扫描时发现。

## 7. DeleteConflict 与强制删除结果

普通删除发现关联时抛出：

```text
DeleteConflict(skillName, linkedAgents(sorted), reasonCode=SKILL_IN_USE)
```

强制删除返回：

```text
DeleteResult(skillName, forced=true, affectedAgents(sorted), archived)
```

服务在图谱写锁内重新扫描、取得排序 Agent 写锁、预检全部链接仍为标准链接，然后逐个解除并原子归档公共包。发生同进程失败时，服务只对本次已经移除且当前位置仍为空的 path 尽力重建标准链接；不得覆盖外部占位或删除非标准内容。失败返回稳定 reason code，下一次重试重新扫描文件系统真相。

本期不创建持久化 operation journal，不做启动恢复，也不承诺进程在多路径操作中崩溃时的事务原子性。进程重启后的列表/删除请求会重新扫描并如实报告当前包和链接状态，供管理员诊断和重试。

## 8. 状态转换

| 当前状态 | 操作 | 前置条件 | 结果 |
|---|---|---|---|
| 不存在 | import | ZIP 与 metadata 合法；公共目标不存在 | 原子发布，默认 enabled |
| 公共包存在 | associate | Agent 存在；包合法；链接路径不存在 | 创建标准相对链接；disabled 包允许关联但不进入 L1 |
| 已关联 | associate | 已存在相同标准链接 | 幂等成功 |
| 非标准占位存在 | associate | — | 409，不覆盖文件/目录/错误链接 |
| enabled | disable | 包存在且合法 | 创建全局 marker；所有 Agent 下一请求移出 L1 |
| disabled | enable | 完整复验成功 | 删除 marker；所有有效关联下一请求恢复 L1 |
| 任意关联 | unlink | 标准链接存在 | 只删除该链接，公共包不变 |
| 无关联 | normal delete | 锁内扫描为空 | 公共包原子归档 |
| 有关联 | normal delete | — | 409 + 完整排序 Agent 列表，无副作用 |
| 有关联 | force delete | 锁内重新扫描并预检全部标准链接 | 解除全部标准链接并归档公共包；同进程失败尽力补偿 |
| 不存在 | mutate/delete | — | 404，无文件副作用 |

## 9. 解析、包限制与归档

解析顺序固定为：归一化 CRLF/CR → 移除 UTF-8 BOM → trim 开头换行 → 校验 opening fence → 逐行寻找 trim 后为 `---` 的 closing fence → YAML 1.2 等价安全反序列化 → legacy warning → name/version 校验 → activation/requires `enforceLimits()` → 提取并验证非空正文。稳定错误至少包括 `MissingFrontmatter`、`InvalidYaml`、`InvalidName`、`InvalidVersion` 与 `EmptyPrompt`。

沿用 `SkillLimits`：默认 ZIP 10 MiB、解压总量 25 MiB、单文件 5 MiB、`SKILL.md` 256 KiB、frontmatter 64 KiB、128 entries、8 层、100:1 解压比。`SkillManifestLimits` 固定 activation keywords/exclude=20、patterns=5、tags=10、setup_marker=256 bytes、requires.skills=10，并对短关键词/标签做过滤。禁止链接、特殊文件、压缩嵌套可执行归档、custom YAML tag、duplicate key 与 alias。

归档事件目录名只使用 UTC 时间和 UUID；Skill 名、来源、删除模式和受影响 Agent 写入安全序列化的 `archive.yml`。`package/` 存在表示包已归档；归档区不参与发现，当前版本不提供恢复 API。

## 10. 核心不变量

- 公共包内容只保存一份；Agent 关联真相只来自标准软链接。
- `AGENT.md/AGENTS.md` 不创建、不删除、不隐式迁移关联。
- 任一请求期间 Skill 快照不变化；管理变更从下一次顶层请求生效。
- 普通删除必须 O(Agents) 重新扫描，不能依赖缓存列表；本期不建立反向索引。
- force delete 必须在锁内再次扫描，前端确认时显示的列表只用于交互，不能作为服务端执行依据。
- Agent 创建的 Skill 选择必须落为标准链接；任一链接失败时不得发布半成品 Agent，也不得生成 `example`。
- parser 的 grammar、限额、warning 与错误 code 对导入、catalog 重扫和 enable 复验保持一致。
- 禁用/删除不追溯修改旧 Session 与既有 Tool/LLM 审计。
- 所有 REST 错误、日志与归档 metadata 不泄露工作区绝对路径、包正文或敏感配置。
