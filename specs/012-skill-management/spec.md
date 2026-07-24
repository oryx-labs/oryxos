# Feature Specification: 公共 Skill 渐进式加载、关联与生命周期管理

**Feature Branch**: `012-skill-management`

> Spec Kit 逻辑 feature id；当前工作树分支为 `codex/skill-management`，规划产物归档在 `specs/012-skill-management/`。

**Created**: 2026-07-22

**Status**: Ready

**Input**: User description: "增加 Skill 的渐进式加载与市场化管理能力；完整保留安全 parser/manifest 契约；公共 Skill 只通过 Agent 目录内的标准软链接关联，不通过 AGENT.md/AGENTS.md 声明；支持导入、全局禁用、解除关联、普通/强制删除；创建 Agent 时把所选 Skill 实际建链且不生成 example。"

## Clarifications

### Session 2026-07-22

- Q: Skill 是跨 Agent 的全局能力库，还是某个 Agent 的私有组成？ → A: 采用公共 Skill 目录；Agent 通过自身 `skills/<skill-name>` 下指向公共包目录的软链接建立关联。软链接是关联真相源，不在 AGENT.md/AGENTS.md 中维护 Skill 名单，也不新增 `use_skill` 工具。
- Q: “删除”是否物理擦除？ → A: 对调用方表现为删除并立即从可用目录消失，底层移入归档区以便追溯和恢复，不做不可恢复擦除；有关联时普通删除先拒绝，强制删除才级联解除关联。
- Q: 管理变更何时影响正在执行的 ReAct？ → A: 一次顶层请求使用固定的 Skill 快照；导入、禁用、启用、删除从下一次顶层请求生效，不在一轮 ReAct 中途改变上下文。
- Q: 第一版从哪里导入？ → A: 管理台/REST 上传本地 Skill 包到公共 Skill 目录；不在本特性内从 URL、Git 仓库或 Marketplace 远程拉取。
- Q: Skill 包采用什么兼容格式？ → A: 对齐开放的 Agent Skills 目录规范：每个受管 Skill 是一个目录，根入口为 `SKILL.md`，frontmatter 至少包含 `name` 与 `description`；`allowed-tools` 只作为说明信息，绝不自动扩大 Agent 的工具权限。
- Q: 导入成功后是否立即可用？ → A: 导入是管理员的显式信任动作；合法包默认启用，但只有建立软链接的 Agent 才会从下一次顶层请求把它加入 L1。界面与文档必须提示 Skill 与代码一样需要先审查，不把结构校验等同于内容可信。
- Q: 禁用是否清除既有会话已经读过的 Skill 内容？ → A: 不做追溯性“遗忘”。禁用保证后续请求不再把它放入 L1、也不再由目录发现触发新的渐进读取；已经持久化在旧 Session tool result/对话里的内容仍按现有历史规则保留。验证禁用后的不可发现性使用新会话。

### Session 2026-07-24

- Q: 删除仍被 Agent 关联的公共 Skill 时如何处理？ → A: 普通删除先扫描全部 Agent 目录；发现软链接关联则拒绝并返回 Agent 列表。前端据此弹窗提供强制删除；管理员确认后，强制删除级联移除这些软链接，再将公共 Skill 包归档。当前版本不做反向索引或性能优化。
- Q: 公共 Skill 方案如何满足治理约束？ → A: 宪章 v2.0.0 已将公共 Skill 市场定义为禁止跨 Agent 共享能力库的唯一受控例外；本 Feature 必须严格使用公共包 + 标准相对软链接，不得扩展为 AGENT.md/数据库关联或其它共享目录。
- Q: 禁用公共 Skill 的作用范围是什么？ → A: 禁用是公共 Skill 的全局状态，影响所有已关联 Agent；某个 Agent 单独停用时解除其软链接关联，不增加 Agent 级禁用状态。
- Q: Agent 与公共 Skill 的软链接允许什么目标形式？ → A: 只允许系统创建、指向 `.oryxos/skills/<skill-name>` 的相对软链接；拒绝绝对链接、越界目标和其他非标准链接。
- Q: 最初约定的 SKILL.md parser/manifest 契约是否仍然有效？ → A: 有效，且属于 US1/安全导入的核心契约。必须覆盖行尾归一化、UTF-8 BOM、文件开头空行、严格 frontmatter fence、空 prompt、稳定错误分类、name/version 注入安全、activation/requires 上限和 legacy `metadata.openclaw.requires` 告警；现有实现只能作为可复用基础，不能用“已存在”替代规格和回归测试。
- Q: Agent 创建页面选择 Skill 后如何持久化？ → A: 创建成功前必须建立实际标准软链接；`AGENT.md` 和数据库不保存关联名单，也不得生成 `skills/example`。Agent 文件或任一链接失败时，整个新 Agent 创建失败且不留下半成品。
- Q: 强制删除是否必须在本期实现跨进程崩溃 journal/recovery？ → A: 不必须。本期按简单 CRUD 边界实现进程内受控强删、锁内重扫、失败可诊断和可重试；持久化操作日志、崩溃补偿和启动恢复只有经后续独立需求批准才进入范围。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用到时才加载 Skill (Priority: P1)

一个 Agent 可以通过自身 `skills/` 目录下的软链接关联多个公共 Skill。每次开始处理请求时，系统扫描这些关联，模型只看到当前有效且已启用 Skill 的轻量元数据；只有确定某个 Skill 与任务相关时，才读取软链接目标中的完整指令，随后再按指令需要读取参考资料或运行脚本。未命中的 Skill 正文和资源不得挤占上下文。

**Why this priority**: 渐进式加载是 Skill 能力的运行时核心；如果仍把全部 Skill 一次性塞进 prompt，Skill 数量增加后上下文成本和相互干扰都会失控。

**Independent Test**: 为一个 Agent 准备两个内容带唯一标记的 Skill，发起只命中其中一个的请求；首个 prompt 只能出现两个 Skill 的名称/描述，不得出现任一正文标记，随后只能读取被命中的 `SKILL.md`，未命中的 Skill 与其资源全程不进入上下文。

**Acceptance Scenarios**:

1. **Given** Agent 目录中存在多个指向公共 Skill 的有效软链接，**When** 新请求开始，**Then** system context 只包含这些 Skill 的名称、描述和 Agent 内关联入口，不包含完整正文或资源内容。
2. **Given** 任务命中某个 Skill，**When** Agent 需要它的具体步骤，**Then** 通过既有文件读取能力加载该 Skill 的 `SKILL.md` 正文，其他 Skill 仍不加载。
3. **Given** Skill 正文引用参考文件或脚本，**When** 执行确实需要该资源，**Then** 才按需读取或运行；未引用资源不预加载。
4. **Given** 一次 ReAct 已经开始，**When** 管理员同时禁用或删除该 Skill，**Then** 管理操作等待当前请求释放该 Agent 的读取租约；当前请求继续使用启动时快照及其 L2/L3 文件，下一次请求起不再发现该 Skill。

---

### User Story 2 - 安全导入一个 Skill (Priority: P1)

管理员选择本地 Skill 包并导入公共 Skill 目录。系统先在隔离区完整校验格式、元数据、路径与大小，再原子性发布；成功后可供 Agent 建立软链接关联，失败则不留下半个目录。

**Why this priority**: 没有可靠导入入口，Skill 只能靠运维手改目录，无法形成可管理能力；而导入包是外部输入，原子性和路径安全必须从第一版具备。

**Independent Test**: 上传一个合法 Skill 包后查询公共列表，把它关联到指定 Agent 并发起命中请求；再分别上传重名包、缺少 `SKILL.md` 的包和包含 `../` 路径穿越的包，均应明确拒绝且公共 Skill 目录没有残留文件。

**Acceptance Scenarios**:

1. **Given** 一个合法且名称未占用的 Skill 包，**When** 导入公共 Skill 目录并关联到指定 Agent，**Then** 校验通过后原子发布、默认启用，并在该 Agent 下一次请求可发现，无需重启。
2. **Given** 包结构、元数据或名称非法，**When** 导入，**Then** 返回可读校验错误，活动目录与 Skill 列表保持不变。
3. **Given** 包含绝对路径、目录穿越、符号链接或超过限制的内容，**When** 导入，**Then** 在写入活动目录前拒绝并清理暂存内容。
4. **Given** 同名公共 Skill 已存在，**When** 再次导入，**Then** 返回冲突，不静默覆盖现有 Skill。
5. **Given** `SKILL.md` 使用 CRLF/CR、UTF-8 BOM、文件开头空行或 closing fence 尾随空白，**When** 校验包，**Then** 按统一规则得到同一 manifest 与 prompt；缺少/未闭合 frontmatter、非法 YAML、非法 name/version 或空 prompt 必须返回稳定且可区分的错误。
6. **Given** manifest 的 activation/requires 超过声明上限，或 version 含空白、引号等属性逃逸字符，**When** 校验包，**Then** activation/requires 按统一规则过滤/截断并产生安全告警，非法 version 在发布前拒绝；legacy `metadata.openclaw.requires` 只产生一次不含敏感内容的告警，不自动赋权。

---

### User Story 3 - 禁用与重新启用 Skill (Priority: P2)

管理员可以在不删除文件和关联软链接的情况下全局禁用某个公共 Skill，也可以重新启用。禁用状态跨重启保留；禁用后它不会出现在任何已关联 Agent 的运行时 L1 Skill 目录中，也不会被渐进式发现。某个 Agent 单独不再使用时，应解除关联而不是禁用公共 Skill。

**Why this priority**: 运营需要先止用、观察再决定是否删除；禁用是比删除更安全的日常控制手段。

**Independent Test**: 把一个公共 Skill 同时关联到两个 Agent 后全局禁用，重启服务并分别发起请求，确认两边的元数据目录与加载轨迹均没有该 Skill；重新启用后两个 Agent 的下一次请求都恢复可见。解除其中一个 Agent 的关联只影响该 Agent。

**Acceptance Scenarios**:

1. **Given** 一个已启用公共 Skill 被多个 Agent 关联，**When** 管理员禁用，**Then** 全局状态持久化，所有关联 Agent 的下一次请求均不再包含它，关联软链接仍保留。
2. **Given** 一个已禁用 Skill，**When** 服务重启，**Then** 它仍为禁用状态。
3. **Given** 一个已禁用 Skill，**When** 管理员重新启用且 Skill 仍合法，**Then** 所有关联 Agent 的下一次请求恢复可发现；若文件已损坏则启用失败并保持禁用。
4. **Given** 旧会话曾读取过该 Skill，**When** 管理员禁用，**Then** 新请求不再通过 L1 发现该 Skill，系统也不改写历史消息；新会话中不得出现该 Skill 或由目录触发其读取。

---

### User Story 4 - 删除并留痕 (Priority: P2)

管理员可以删除某个公共 Skill。系统先扫描所有 Agent 目录检查软链接引用：无引用时直接归档；有引用时普通删除拒绝并返回 Agent 列表，前端展示影响范围并允许管理员再次确认强制删除。强制删除先移除全部关联软链接，再归档完整公共 Skill 包，避免留下悬空关联。

**Why this priority**: 删除是用户明确要求的管理闭环；采用可恢复归档与现有 Agent 删除语义一致，也更适合企业环境。

**Independent Test**: 删除一个被两个 Agent 关联的公共 Skill，确认普通删除返回冲突和两个 Agent 名；确认强制删除后两个软链接均消失、后续请求不可发现、公共活动目录不存在且归档区保留完整包。删除不存在的 Skill 返回 404 且无副作用。

**Acceptance Scenarios**:

1. **Given** 一个未被任何 Agent 关联的公共 Skill，**When** 管理员普通删除，**Then** 它从活动列表消失，完整目录原子移入归档区。
2. **Given** 一个仍被 Agent 软链接关联的公共 Skill，**When** 管理员普通删除，**Then** 系统扫描全部 Agent 目录后拒绝删除，并返回完整关联 Agent 列表，文件系统保持不变。
3. **Given** 前端收到关联冲突，**When** 管理员在影响范围弹窗中确认强制删除，**Then** 系统重新扫描并移除全部关联软链接，再归档公共 Skill；所有相关 Agent 的下一次请求均不可发现它。
4. **Given** Skill 不存在，**When** 删除或修改状态，**Then** 返回 404，其他 Skill 不受影响。

---

### User Story 5 - 在管理台完成 Skill 管理 (Priority: P3)

管理员进入公共 Skill 页面可以完成导入、禁用/启用和删除；进入某个 Agent 详情的 Skill 页签，可以查看公共 Skill 关联和 Agent 内的软链接状态，并完成关联/解除关联。所有危险操作都有清晰确认、加载中、成功和失败反馈。

**Why this priority**: REST 能力先保证核心可用，管理台再把它变成日常可操作产品闭环。

**Independent Test**: 仅通过管理台完成“导入 → 创建 Agent 并选择 Skill → 详情确认实际链接 → 禁用 → 重启确认状态 → 启用 → 删除”，每一步页面状态与 REST 查询一致，Agent 目录中不出现 example Skill 或 YAML 关联名单。

**Acceptance Scenarios**:

1. **Given** 管理员打开 Agent 详情，**When** 切换到 Skill 页签，**Then** 看见通过软链接实际关联的公共 Skill 名称、描述、状态和链接异常，并可关联或解除关联。
2. **Given** 管理操作进行中或失败，**When** 页面收到响应，**Then** 禁止重复提交，并展示统一响应中的明确错误信息。
3. **Given** 管理员删除仍有关联的公共 Skill，**When** 服务端返回关联 Agent 列表，**Then** 页面展示影响范围并提供强制删除二次确认；服务端成功后移除该项，失败时保留原状态。
4. **Given** 管理员把公共 Skill 关联到 Agent，**When** 操作成功，**Then** Agent 目录中创建指向该公共包的标准相对软链接；解除关联只删除该链接，不修改公共包。
5. **Given** 管理员创建 Agent 时选择一个或多个公共 Skill，**When** 创建成功，**Then** Agent 详情立即从文件系统实际链接显示这些 Skill，`AGENT.md` 不保存 Skill 名单且不生成 example Skill。
6. **Given** 创建 Agent 时任一公共 Skill 不存在、非法或链接路径被占用，**When** 提交创建，**Then** 整个创建失败且 Agent、AGENT.md 和所有已尝试链接均不可见。

### Edge Cases

- Agent 不存在、已归档或正在删除时建立 Skill 关联，必须拒绝且不创建软链接。
- 一个坏 Skill 不得让整个 Agent 无法启动；启动扫描应隔离坏项、报告错误，其他 Skill 正常可用。
- Skill 名称大小写、Unicode 归一化或路径别名造成同名冲突时，必须使用单一规范化规则判断唯一性。
- 导入在解压、校验或最终移动任一步失败时，暂存区必须清理，原 Skill 集合不变。
- Skill 数量或元数据总量超过上下文预算时，必须确定性拒绝或截断并明确告警，不能静默随机丢失。
- `SKILL.md` 使用 CRLF、单 CR、UTF-8 BOM、文件开头空行、opening 行在前三个 `-` 后带兼容性尾部、closing fence 尾随空白或正文前多个空行时，解析结果必须稳定；首个非空位置的前三个字符不是 `---`、opening 行后无换行、closing fence 缺失和 prompt 为空时必须返回对应错误。
- manifest 的 name/version 位于边界长度、含非 ASCII、引号、空白或 XML 属性逃逸字符时，必须按公开语法一致接受或拒绝；不得在后续渲染阶段才发现 breakout。
- activation 的短关键词/标签和超额条目、非法/超长 setup marker，以及 requires.skills 的超额条目必须按统一规则过滤、清除或截断并告警；整个 YAML 的嵌套、code points、frontmatter 字节等硬限制超限时拒绝当前 Skill，不能拖垮其它 Skill 扫描。
- 手工修改仍存在的 `SKILL.md` 使内容/元数据失效，或从带 OryxOS 保留 marker 的包删除入口时，下一次快照不得加载并在管理列表暴露 invalid；无入口也无 marker 的普通目录继续按 legacy/unmanaged 忽略。
- 禁用/删除与一次正在运行的请求并发发生时，当前请求快照不变，后续请求看到新状态。
- 旧版 `skills/*.md` 子指令不得因本特性上线而使既有 Agent 启动失败。
- 包通过结构校验不代表内容可信；恶意指令、脚本或引用仍可能诱导 Agent 使用其已有 Tool 权限，管理台必须把导入呈现为信任边界。
- 管理 API 保证与正在运行的请求互斥；管理员直接在进程外改写文件系统不受进程内租约协调，下一次扫描必须自愈或暴露 invalid，不能依赖 WatchService 缓存。
- Agent 目录中的关联软链接为绝对路径、目标不存在、指向公共 Skill 根之外或形成链接环时，必须忽略并报告 invalid/security warning，不得跟随到任意文件系统位置。
- 建立关联时若 `skills/<skill-name>` 已被普通文件、真实目录或非标准链接占用，必须返回冲突，不覆盖、不迁移现有内容。
- 普通删除的关联检查与强制删除的执行之间可能新增关联；强制删除必须在持有写入租约后重新扫描，而不是复用前一次冲突响应中的旧列表。
- 公共 Skill 市场是宪章 IV 的唯一共享例外；任何 AGENT.md/AGENTS.md/数据库关联名单、第二公共根或隐式共享索引都超出例外，必须拒绝。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 受管 Skill MUST 存放在 `.oryxos/skills/<skill-name>/SKILL.md`；Agent 与公共 Skill 的关联 MUST 且只能由系统在 `.oryxos/agents/<agent-name>/skills/<skill-name>` 创建、指向 `../../../skills/<skill-name>` 的相对软链接表达。MUST NOT 通过 AGENT.md/AGENTS.md 的 Skill 名单建立加载关联，也不得新增 `use_skill` Tool。
- **FR-002**: 系统 MUST 对每个新请求扫描该 Agent 的 Skill 软链接并构建已启用 Skill 元数据快照；L1 只暴露名称、描述和 Agent 内关联入口，MUST NOT 预载任一 Skill 正文或资源。链接名、目标包目录名与 `SKILL.md` frontmatter `name` MUST 一致。
- **FR-003**: 系统 MUST NOT 自动预载或自动执行 L2/L3。L2 `SKILL.md` 与 L3 资源只能由一次显式的既有 Tool 调用取得；每次调用 MUST 受当前 snapshot 成员关系、Agent 显式 Tool 权限、包内路径边界、沙箱与审计约束。系统不负责推断模型的语义因果，但未读取 L2 时不得由运行时自动触发 L3。
- **FR-004**: 一次顶层请求内的 Skill 快照 MUST 保持不变；该请求 MUST 持有所属 Agent 的读取租约直至 ReAct 及会话保存结束。关联、解除关联、强制删除中的链接移除、Agent 删除，以及现有 Workspace/Agent files API 对关联入口的写入 MUST 使用同一 Agent 的写入租约，从下一次顶层请求生效且无需重启。
- **FR-005**: 系统 MUST 支持向公共 Skill 根目录上传并导入本地 Skill 包；导入 MUST 经“暂存 → 完整校验 → 原子发布”流程，中途失败 MUST 清理暂存且活动目录无变化。
- **FR-006**: 导入校验 MUST 覆盖包结构、必填元数据、安全 YAML 子集（无 custom tag/duplicate key/alias，有限深度与 code points）、Skill 名称与目录名一致性、规范化路径唯一性、文件数量、单文件/总大小、解压比、路径深度、路径穿越、绝对路径、符号链接、特殊文件、加密/不支持的 ZIP 条目及不允许的嵌套可执行/压缩格式；限制值 MUST 可配置且有安全默认值。
- **FR-007**: 公共 Skill 根目录下导入同名 Skill MUST 返回冲突并保持原内容；本特性 MUST NOT 提供静默覆盖，也 MUST NOT 新增或扩展远程 URL/Git/Marketplace 拉取协议。既有 GitHub 导入若保留，MUST 复用同一公共包安全校验与原子发布路径。
- **FR-008**: 系统 MUST 提供公共 Skill 列表/详情，以及指定 Agent 的实际关联列表。至少返回名称、描述、状态（enabled/disabled/invalid）、管理员启用设置、来源、更新时间、入口、资源清单、文件统计及可读校验错误；entrypoint/resources 均使用工作区相对路径，不得暴露本机绝对路径。
- **FR-009**: 系统 MUST 支持禁用和重新启用；状态 MUST 落文件系统并跨重启保留。禁用/启用 MUST 是公共 Skill 的全局状态并作用于所有关联 Agent，MUST NOT 删除或改写关联软链接；禁用 Skill MUST 从后续 L1 目录和加载链路中排除，启用前 MUST 重新校验。单个 Agent 停用某 Skill MUST 通过解除关联实现，本特性 MUST NOT 增加 Agent 级禁用状态。
- **FR-010**: 系统 MUST 支持普通删除与显式强制删除公共 Skill。普通删除 MUST 扫描全部 Agent 目录；发现关联时 MUST 拒绝并返回完整 Agent 列表。强制删除 MUST 在执行时重新扫描，在同一受控操作中移除全部关联软链接后，将完整包原子移入归档区并保存 Skill 名称、删除时间、来源及受影响 Agent，不做物理擦除。当前版本 MUST NOT 为删除检查建立反向索引或缓存。
- **FR-011**: 启动扫描或手工修改遇到坏 Skill 时 MUST 隔离该项并记录错误，MUST NOT 阻断 Agent 或其他 Skill；修复后下一次扫描/请求可恢复。
- **FR-012**: 系统 MUST 提供公共 Skill 管理与 `/api/v1/agents/{agentName}/skills` 关联管理 REST 契约；管理台公共 Skill 页面覆盖查询、导入、禁用/启用和删除，Agent 详情 Skill 页签覆盖关联、解除关联与链接异常展示。
- **FR-013**: REST 错误 MUST 沿用统一响应信封：Agent/Skill 不存在为 404，同名或路径占用冲突为 409，格式/安全/状态校验失败为 400，导入过大为 413；普通删除因仍被使用而产生的 `SKILL_IN_USE` 冲突 MUST 返回可供前端展示的完整 Agent 名列表，其它 409 不伪造该列表。任何错误不得返回本机绝对路径或堆栈。
- **FR-014**: Skill 目录与状态 MUST 每次请求重新发现或通过可正确失效的快照读取，文件改动和管理操作无需重启即可生效。
- **FR-015**: 本特性 MUST 保持现有 Agent 目录和旧版 `skills/*.md` 子指令可用；兼容策略不得把旧文件误当成合法受管 Skill 后静默改变行为。
- **FR-016**: 每个已经进入 Skill 管理服务的导入、关联、解除关联、状态变更、普通删除和强制删除调用 MUST 且只能记录一条结构化领域事件，至少包含稳定 event、Skill、动作、结果、时间、失败原因码及适用时的 Agent/受影响 Agent 列表；不得记录包正文、本机路径、密钥、敏感配置或未清洗异常。multipart/JSON/path 等在进入服务前被 Web 层拒绝的请求只沿用 Web 错误日志，不伪装成领域动作。
- **FR-017**: L1 元数据目录 MUST 有确定性的排序和上下文预算；超限行为必须可预测并产生明确告警。
- **FR-018**: 系统 MUST 支持 Agent Skills 的 `name`、`description` 必填字段及 `version`、`license`、`compatibility`、`metadata`、`allowed-tools`、`activation`、`requires` 可选字段。name MUST 匹配 `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`；version 存在时 MUST 匹配 `^[a-zA-Z0-9._+~-]{1,32}$`，不存在时后续输出不得渲染 version 属性。未知扩展字段可保留或忽略但不得导致自动执行；`allowed-tools`、activation 和 requires 均不得改写 Profile 的显式 Tool 权限。
- **FR-019**: 公共 Skill 候选 MUST 为公共 Skill 根目录下含 `SKILL.md`、`.oryxos-disabled` 或 `.oryxos-origin.yml` 的直接真实子目录；含 marker 但入口缺失的候选为 invalid。Agent 运行时候选 MUST 为其 `skills/` 下直接指向这些公共候选的软链接。旧版 `skills/*.md` 及 Agent 目录内真实子目录 MUST 保持 legacy/unmanaged，不自动迁移、不被公共 Skill API 误操作。
- **FR-021**: 手工放入公共根的 Skill 与导入包 MUST 使用同一内容校验。公共包内部的入口/resource 链接或特殊文件候选 MUST 标为 invalid；Agent 关联入口只允许标准相对软链接，并只跟随一层到 `.oryxos/skills/` 内的真实包目录，在读取任何 L2/L3 资源前重新验证真实路径仍位于该公共包内。绝对链接、非标准相对目标、悬空链接、链接环或越界目标 MUST 拒绝进入 L1 并写安全告警。
- **FR-022**: 禁用/删除 MUST 阻止后续 snapshot 与由 Skill 目录触发的 L2/L3 渐进读取，但 MUST NOT 静默改写或删除既有 Session 历史、Tool 审计或 LLM 审计；“已知内容遗忘/历史来源过滤”不属于本特性。
- **FR-023**: 本 Feature 的 PR 描述 MUST 提供醒目的 `Governance Amendment / 治理修订` 独立区块，引用宪章 v2.0.0 Principle IV/VIII，说明公共 Skill 市场例外的边界、兼容与安全影响，并证明实现没有引入 AGENT.md/数据库关联、`use_skill` Tool 或权限扩张。
- **FR-024**: `SKILL.md` 校验 MUST 先将 CRLF 与单 CR 归一为 LF、剥离文件起始 UTF-8 BOM、允许 BOM 后的前导空行，再要求首个非空位置的前三个字符是 opening `---` 且该行其后存在换行；opening 所在行的剩余内容跳过，不进入 YAML。closing fence MUST 是 trim 后独占的 `---`。系统 MUST 只把 opening 行之后与 closing fence 之前的内容解析为 YAML，并在 closing fence 后跳过前导换行得到 prompt。缺少或未闭合 frontmatter、非法 UTF-8/YAML、非法 name/version 和空 prompt MUST 返回稳定、可区分且不泄露本机路径的错误。
- **FR-025**: activation 与 requires MUST 在进入运行时前执行确定性的 `enforceLimits()`：activation 的 keywords/exclude_keywords 各最多 20、patterns 最多 5、tags 最多 10，keywords/tags 中短于 3 字符的项过滤，setup_marker 超过 256 字节或含 `..` 时清除；requires.skills 最多 10 项。发生过滤、清除或截断时 MUST 记录一次不含原值的稳定告警；frontmatter 总量、嵌套和 code points 等硬限制仍按 FR-006 拒绝当前 Skill。若 YAML 包含 legacy `metadata.openclaw.requires`，系统 MUST 记录一次不含 requires 值的稳定告警并忽略其授权语义，不得阻断其它合法字段解析。
- **FR-026**: Agent 创建请求携带的 Skill 选择只表示创建成功后要建立的公共关联。系统 MUST 在对外可见前验证全部 Skill、写入 Agent 定义并建立全部标准链接；任何一步失败 MUST 回滚整个新 Agent。Agent 定义、数据库和生成草稿 MUST NOT 保存 Skill 名单，脚手架 MUST NOT 创建 example Skill；Agent 详情中的关联 MUST 从实际文件系统链接派生。

### Key Entities

- **Public Skill Package**: 公共 Skill 根目录下的目录包，含 `SKILL.md` 及可选 `references/`、`scripts/`、`assets/` 等资源；目录自身是内容真相源，可被多个 Agent 关联。
- **Agent Skill Link**: 系统在 Agent 的 `skills/<skill-name>` 下创建、指向 `../../../skills/<skill-name>` 的相对软链接，是 Agent 与 Skill 关联的唯一真相源；解除关联只删除该链接。
- **Skill Metadata**: 从 `SKILL.md` frontmatter 派生的轻量信息，用于 L1 发现，不含完整指令正文。
- **Skill Manifest**: `SKILL.md` frontmatter 的受限声明，包含身份、描述、可选版本和说明性约束；其中任何字段都不授予 Tool 权限。
- **Skill Snapshot**: 一次顶层请求开始时冻结的已启用 Skill 元数据集合，保证一轮 ReAct 内一致。
- **Skill State**: enabled、disabled、invalid 三态；enabled/disabled 是公共包的全局状态，由公共包内保留 marker 持久化并影响所有关联 Agent；invalid 由当前文件内容或关联链接校验派生而不单独落盘。
- **Skill Lease**: 以规范化 Agent 名为键的进程内公平读写租约；一次顶层请求持有读租约，管理发布/变更/删除持有写租约，保证请求内 L1/L2/L3 一致。
- **Import Staging Area**: 导入时隔离解包和校验的临时目录，成功才原子移动到活动区。
- **Archived Skill**: 删除后移出公共活动区的完整 Skill 包及归档元数据，不参与运行时发现；强制删除的元数据同时记录受影响 Agent。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 自动化测试证明首个 prompt 对每个 Skill 只包含 L1 元数据，0 个未命中 Skill 的正文或资源进入上下文。
- **SC-002**: 两个 Skill 并存时，命中其中一个的端到端测试只产生该 Skill 的 L2/L3 读取，未命中 Skill 的文件读取次数为 0。
- **SC-003**: 合法 Skill 包导入并建立 Agent 软链接后无需重启，在该 Agent 下一次请求即可发现；非法、重名或恶意包 100% 被拒且公共活动目录残留文件数为 0。
- **SC-004**: 全局禁用、重新启用和删除均在所有关联 Agent 的下一次请求生效；状态跨一次完整服务重启保持正确；解除单个 Agent 关联不影响其他 Agent。
- **SC-005**: 路径穿越、绝对路径、符号链接、超限包和不允许文件类型的安全回归全部通过。
- **SC-006**: 普通删除能返回全部关联 Agent 且不改变文件系统；确认强制删除后，扫描到的 Agent 软链接与公共活动目录中该 Skill 记录均为 0，归档区保留完整内容、受影响 Agent 和可追溯元数据。
- **SC-007**: 既有 Agent 与旧版子指令回归通过；一个 invalid Skill 不影响同 Agent 其他 Skill 或 Agent 启动。
- **SC-008**: 管理台可独立完成“导入 → 禁用 → 启用 → 删除”闭环，页面状态与 REST 结果一致。
- **SC-009**: 发布前要求的后端、前端、格式和安全质量门禁 100% 通过，新增契约及关键并发/安全路径均有自动化覆盖。
- **SC-010**: 并发测试证明禁用/删除在运行中请求结束前不会移动或改变其 Skill 文件；写操作一旦排队，后续新请求不会持续插队读取旧状态。
- **SC-011**: PR 描述存在独立、醒目的治理修订区块，评审者无需阅读代码即可确认宪章 v2.0.0 的市场例外、设计动机、影响范围和不可突破的边界。
- **SC-012**: 工作区整体移动后标准相对关联仍可加载；绝对、越界、悬空、循环或被既有路径占用的关联测试全部明确拒绝，且不覆盖任何已有文件。
- **SC-013**: 自动化解析矩阵覆盖 LF/CRLF/CR、BOM、前导空行、fence 尾随空白、未闭合 frontmatter、非法 UTF-8/YAML、空 prompt、name/version 边界与 breakout、activation/requires 超限和 legacy warning；每个输入 100% 得到约定结果与稳定错误分类。
- **SC-014**: 通过直接创建和“生成后保存”两条路径选择多个 Skill 时，成功结果中 100% 存在对应标准链接且 example/YAML/数据库关联数为 0；任一步故障后可见的新 Agent 和残留链接数均为 0。

## Assumptions

- 第一版新增导入格式为本地上传的单 Skill ZIP 包；接受根目录直接包含 `SKILL.md`，或仅有一个与 Skill 名一致的顶层目录。导入目标是公共 Skill 根目录；批量导入、新的远程仓库/Marketplace 协议、签名验证、版本依赖解析不在本特性范围。既有 GitHub 导入仅作为兼容入口收敛到同一安全导入管线。
- 本文“公共 Skill 市场”指工作区内的公共包目录与安装管理体验；远程市场浏览、搜索、下载、签名和版本依赖协议不在本特性范围。
- version 为兼容现有包的可选字段；只有通过安全语法验证的非空 version 才能出现在任何结构化输出或属性中。
- 强制删除本期只保证进程内锁定、执行时重扫、失败可诊断和可重试；持久化 journal、崩溃补偿和启动恢复属于后续独立可靠性特性，不是当前简单 CRUD 的验收条件。
- 导入成功后默认 enabled；删除采用可恢复归档，恢复 API 不在本特性范围。
- OryxOS 核心阶段仍无认证/RBAC，Skill 管理沿用现有内网部署假设；未来认证接入后复用相同 REST 资源边界。
- 渐进式加载复用既有 `read_file` 与 `shell`，不新增 `use_skill` 或自动工具执行路径。
- Skill 管理状态跟随公共 Skill 包落文件系统；Agent 关联跟随 Agent 目录中的软链接落文件系统，不为状态或关联新增 SQLite 表。
- 禁用的产品语义是从 OryxOS 的 L1 发现与正常渐进加载链路中排除；Skill 仍属于受信任工作区内容，本特性不把通用 `shell` 升级为逐路径的强制访问控制器。
- 禁用/删除不让模型追溯性遗忘已进入既有 Session 历史的正文或 Tool 结果；不可发现性验收使用新 Session，并单独断言旧历史没有被篡改。
