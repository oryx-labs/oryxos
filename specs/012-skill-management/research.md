# Research: 公共 Skill + Agent 软链接关联

## 1. 收敛两套现有实现

**Decision**

保留 `.oryxos/skills` 公共存储，同时复用现有 Agent 私有 managed Skill 中的安全解析、ZIP 校验、L1 snapshot、读写租约和归档能力。删除 `Profile.skills → SkillRegistry → ContextLoader` 的正文 eager injection 路径。

**Rationale**

当前公共实现存储位置正确但关联/加载错误；私有实现渐进披露与安全能力成熟但所有权错误。合并比重写风险低。

**Alternatives considered**

- 继续维护两套机制：会双加载并产生两个真相源。
- 全部重写：丢弃已经覆盖 ZIP/租约/归档的测试资产。

## 2. 标准软链接是唯一关联真相源

**Decision**

系统只创建：

```text
.oryxos/agents/<agent>/skills/<skill>
  -> ../../../skills/<skill>
```

扫描必须验证：条目是 symlink；`readSymbolicLink` 是相对路径且原始值精确等于标准 target；链接名、公共目录名和 manifest name 一致；公共根和包是真实直接目录；解析目标仍在公共根内。

**Rationale**

关联可见、可移动、无需数据库或 YAML 双写。精确比较原始 target 能拒绝绝对链接、路径别名和多余 `..`。

**Alternatives considered**

- `AGENT.md skills:`：重复状态，已导致用户看到“选了但未实际关联”。
- 绝对链接：工作区移动后失效。
- 任何最终落在公共根的链接：允许多种表示，弱化审计与安全判断。

## 3. 运行时渐进披露

**Decision**

`AgentSkillCatalog` 每次顶层请求扫描 Agent `skills/` 的标准链接，解析公共包的 enabled/invalid 状态，冻结 `SkillSnapshot`。L1 只有 name、description、Agent 内 entry；L2/L3 继续由模型调用 `read_file`/`shell`。`Profile.skills` 仅兼容解析并告警，不参与任何运行时决策。

**Rationale**

这保证未命中正文不占 prompt，且所有读取继续经过 ToolExecutor、沙箱和审计。

**Alternatives considered**

- 从 `SkillRegistry` 注入全文：破坏渐进披露且缓存易过期。
- 新增 `use_skill`：形成旁路并扩大 Tool 面。

## 4. 公共包与全局状态

**Decision**

公共包是真实目录，包内仍禁止 symlink/特殊文件；`.oryxos-disabled` 位于公共包并作用于所有关联 Agent。disabled 可以被关联但 `discoverable=false`；enable 前完整重校验。单 Agent 停用等于 unlink。

**Rationale**

单一 marker 自然表达全局状态，不需要 Agent 级 sidecar。

**Alternatives considered**

- 每个 Agent 一份 disabled marker：引入未授权的 Agent 级禁用状态。
- 禁用时删链接：失去关联意图且启用无法自动恢复。

## 5. 安全创建与清理链接

**Decision**

创建前以 `NOFOLLOW_LINKS` 验证所有父链和公共目标，目标占用（普通文件、真实目录、悬空/非标准链接）统一 409，不覆盖。以临时链接 + 原子 rename 发布并在创建后重读验证。解除关联只删除复验通过的标准 symlink inode，不跟随目标；异常 symlink 和真实 legacy 内容都不可由普通关联 API 删除。

**Rationale**

保护用户已有内容并缩小 TOCTOU；不支持 symlink 的平台明确失败。

**Alternatives considered**

- `REPLACE_EXISTING`：可能覆盖 legacy 文件。
- 复制公共包：破坏共享状态和统一禁用。

## 6. 删除扫描与返回契约

**Decision**

普通删除每次用 `DirectoryStream` 扫描全部真实 Agent 目录，只统计标准链接，返回去重、排序的 Agent 列表；有关联时 409 且零文件变化。无反向索引或 cache。

**Rationale**

文件系统是真相源；用户明确当前不做性能优化。直接层级扫描有界且不会递归跟随链接。

**Alternatives considered**

- 内存/数据库反向索引：需要失效、重建和一致性语义，本期明确延期。

## 7. 跨 Agent 锁序

**Decision**

新增 fair 的公共 Skill 图谱锁。所有图谱变更遵循：

```text
全局 Skill 图谱锁 → Agent 名升序写锁 → 文件系统操作
```

请求持全局图谱读锁 + 单 Agent 读锁直到本轮 ReAct 和 session save 完成。禁用/启用和 force 删除取得图谱写锁，再按序等待相关 Agent 的活跃请求完成；force 在全部锁内重新扫描，不能复用第一次 409 数据。

**Rationale**

图谱读锁让公共 marker/包在请求期保持稳定，图谱写锁关闭“扫描后新增关联”竞态；排序多锁避免死锁，fair lock 防 writer 饥饿。本期接受全局管理操作与所有请求互斥，不做更细粒度优化。

**Alternatives considered**

- 只锁已扫描 Agent：仍可从集合外新增关联。
- 运行时只持 Agent 锁：全局禁用或归档可在请求 L2/L3 中途改变公共目标。

## 8. Force 删除的本期一致性边界

**Decision**

跨多个软链接和一个目录移动无法原子提交。本期保持简单 CRUD：force 在图谱写锁内重新扫描并预检全部标准链接，按 Agent 名排序加锁后逐个 unlink，再用 `ATOMIC_MOVE` 归档公共包。同进程失败时，只重建“由本操作移除且 path 仍为空”的标准链接并返回稳定、可诊断错误；重试会重新扫描文件系统真相。

**Rationale**

满足用户要求的 unlink→归档顺序，并把复杂度限制在当前生命周期 CRUD。持久化 journal、启动恢复和跨进程崩溃原子性需要额外状态机与运维语义，最新 spec 已明确不属于本 Feature。

**Alternatives considered**

- 把多步 NIO 当作事务：文件系统不提供此保证。
- 先归档再留悬空链接：暴露无效关联状态。
- pending operation journal + 启动恢复：可以增强 crash consistency，但超出本期范围，留待单独 Feature 设计。

## 9. REST 与 UI 边界

**Decision**

公共 `/api/v1/skills` 管理包；Agent `/api/v1/agents/{agent}/skills` 管理链接。普通 DELETE 409 返回 `{reasonCode, skillName, linkedAgents}`；force 用 `?force=true`，执行时重扫。前端只在 `SKILL_IN_USE` 时展示列出完整 Agent 的强制删除弹窗；请求助手保留 `status/code/data`。

**Rationale**

公共包和关联是不同资源；结构化错误使 A→B 交互不依赖解析错误文案。

**Alternatives considered**

- 一步级联删除：用户看不到影响范围。
- 只返回 message：前端无法安全判断是否允许 force。

## 10. Agent 创建与兼容

**Decision**

创建 DTO 的 `skills` 表示创建后要建立的链接；草稿生成不写 `skills:`。创建先验证全部公共包，Agent 文件与全部链接任一步失败则回滚整个新 Agent。脚手架不创建 example Skill。旧 `AGENT.md skills:` 允许解析但忽略、告警且不自动迁移。

**Rationale**

直接创建和生成后保存得到相同真实关联；不自动迁移避免未经管理员确认修改工作区。

**Alternatives considered**

- 同时写 YAML 与链接：需要双写修复并重建第二真相源。

## 11. 导入与既有 GitHub 能力

**Decision**

本 Feature 的规范入口是本地 multipart ZIP，复用 `SkillPackageImporter` 的 staging、central-directory 与资源限制。现有 GitHub 导入若保留，必须落入同一公共包验证/原子发布路径；本计划不扩展远程下载协议。

**Rationale**

避免远程入口绕过公共包内容校验，同时不把已有产品能力误当成本 Feature 新范围。

## 12. `SKILL.md` 解析与 manifest 校验

**Decision**

解析前把 CRLF 与单独 CR 归一化为 LF，移除 UTF-8 BOM，再只 trim 文件开头的换行。首个非空位置必须以 `---` 开始且 opening fence 必须有后续换行；closing fence 逐行扫描，只有整行 trim 后等于 `---` 才结束 frontmatter。YAML 使用 SnakeYAML 的 safe、YAML 1.2 等价配置，禁止 custom tag、duplicate key 与 alias；解析错误映射为稳定领域 code。

manifest `name` 使用 `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`；可选 `version` 出现时使用 `^[a-zA-Z0-9._\-+~]{1,32}$`，防止未来插值进 XML 属性时发生 breakout。`activation.enforceLimits()` 对 keywords/exclude/patterns/tags 做 20/20/5/10 上限、过滤短关键词/标签，并清除超长或含 `..` 的 setup_marker；`requires.enforceLimits()` 把 skills 截断到 10。发生修正只写安全 WARN，YAML 通用资源限制仍硬拒绝。`metadata.openclaw.requires` 只发 legacy warning，不阻断解析，也不得暗中填充顶层 `requires`。closing fence 后正文 trim 空白为空时返回 `EmptyPrompt`。

**Rationale**

行尾归一化让字节偏移定位在所有平台上确定；显式 grammar 和限额避免路径身份、XML 属性与资源消耗问题；稳定错误便于 REST、UI 与测试可靠处理。该兼容语义核对自 [IronClaw parser.rs](https://github.com/nearai/ironclaw/blob/672f003eaf14b89753f9b2ce4d69c09453921380/crates/ironclaw_skills/src/parser.rs) 与 [types.rs](https://github.com/nearai/ironclaw/blob/672f003eaf14b89753f9b2ce4d69c09453921380/crates/ironclaw_skills/src/types.rs)；Java 实现不需要逐字照搬 Rust API，但输出语义必须等价，并额外保留 OryxOS 的安全 YAML/包级硬限制。

**Alternatives considered**

- 直接 `split("---")`：无法正确区分正文、尾随空白和平台行尾。
- YAML 1.1 默认隐式类型：会把 `on/off/yes/no` 等值意外转型，不符合约定。
- 只靠 DTO 注解校验：无法覆盖 fence、正文与 legacy shape 诊断。

## 13. 宪章市场例外

**Decision**

宪章 v2.0.0 已将公共 Skill 市场定义为跨 Agent 共享能力的唯一受控例外。本设计只有在公共根、标准相对软链接、无 YAML/数据库关联、无 Tool 扩权这些边界全部成立时合规。

**Rationale**

市场式复用是显式治理决定，而不是对“禁止任意共享能力库”的泛化放宽。

## 结论

没有未解决的技术澄清。宪章检查已通过；实现与 PR 仍须证明没有越过市场例外边界。
