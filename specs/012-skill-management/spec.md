# Feature Specification: Agent 内 Skill 渐进式加载与生命周期管理

**Feature Branch**: `012-skill-management`

> Spec Kit 逻辑 feature id；本次只产出规划文档，当前 Git worktree 仍在 `main`，未创建或切换分支。

**Created**: 2026-07-22

**Status**: Draft

**Input**: User description: "增加 skill 的能力，包括核心的 skill 渐进式加载；以及 skill 的管理能力——提供导入、禁用、删除的能力"

## Clarifications

### Session 2026-07-22

- Q: Skill 是跨 Agent 的全局能力库，还是某个 Agent 的私有组成？ → A: 遵守宪法 IV，Skill 归属于一个具体 Agent，落在该 Agent 目录内；本特性不建立跨 Agent 共享库、全局能力索引或 `use_skill` 工具。
- Q: “删除”是否物理擦除？ → A: 对调用方表现为删除并立即从可用目录消失，底层移入归档区以便追溯和恢复，不做不可恢复擦除。
- Q: 管理变更何时影响正在执行的 ReAct？ → A: 一次顶层请求使用固定的 Skill 快照；导入、禁用、启用、删除从下一次顶层请求生效，不在一轮 ReAct 中途改变上下文。
- Q: 第一版从哪里导入？ → A: 管理台/REST 上传本地 Skill 包；不在本特性内从 URL、Git 仓库或 Marketplace 远程拉取。
- Q: Skill 包采用什么兼容格式？ → A: 对齐开放的 Agent Skills 目录规范：每个受管 Skill 是一个目录，根入口为 `SKILL.md`，frontmatter 至少包含 `name` 与 `description`；`allowed-tools` 只作为说明信息，绝不自动扩大 Agent 的工具权限。
- Q: 导入成功后是否立即可用？ → A: 导入是管理员的显式信任动作；合法包默认启用，并从下一次顶层请求进入 L1 目录。界面与文档必须提示 Skill 与代码一样需要先审查，不把结构校验等同于内容可信。
- Q: 禁用是否清除既有会话已经读过的 Skill 内容？ → A: 不做追溯性“遗忘”。禁用保证后续请求不再把它放入 L1、也不再由目录发现触发新的渐进读取；已经持久化在旧 Session tool result/对话里的内容仍按现有历史规则保留。验证禁用后的不可发现性使用新会话。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用到时才加载 Skill (Priority: P1)

一个 Agent 可以拥有多个 Skill。每次开始处理请求时，模型只看到该 Agent 当前已启用 Skill 的轻量元数据；只有确定某个 Skill 与任务相关时，才读取它的完整指令，随后再按指令需要读取参考资料或运行脚本。未命中的 Skill 正文和资源不得挤占上下文。

**Why this priority**: 渐进式加载是 Skill 能力的运行时核心；如果仍把全部 Skill 一次性塞进 prompt，Skill 数量增加后上下文成本和相互干扰都会失控。

**Independent Test**: 为一个 Agent 准备两个内容带唯一标记的 Skill，发起只命中其中一个的请求；首个 prompt 只能出现两个 Skill 的名称/描述，不得出现任一正文标记，随后只能读取被命中的 `SKILL.md`，未命中的 Skill 与其资源全程不进入上下文。

**Acceptance Scenarios**:

1. **Given** Agent 有多个已启用 Skill，**When** 新请求开始，**Then** system context 只包含这些 Skill 的名称、描述和可读取位置，不包含完整正文或资源内容。
2. **Given** 任务命中某个 Skill，**When** Agent 需要它的具体步骤，**Then** 通过既有文件读取能力加载该 Skill 的 `SKILL.md` 正文，其他 Skill 仍不加载。
3. **Given** Skill 正文引用参考文件或脚本，**When** 执行确实需要该资源，**Then** 才按需读取或运行；未引用资源不预加载。
4. **Given** 一次 ReAct 已经开始，**When** 管理员同时禁用或删除该 Skill，**Then** 管理操作等待当前请求释放该 Agent 的读取租约；当前请求继续使用启动时快照及其 L2/L3 文件，下一次请求起不再发现该 Skill。

---

### User Story 2 - 安全导入一个 Skill (Priority: P1)

管理员在某个 Agent 的管理页选择本地 Skill 包并导入。系统先在隔离区完整校验格式、元数据、路径与大小，再原子性发布到该 Agent；成功后无需重启即可在下一次请求中使用，失败则不留下半个目录。

**Why this priority**: 没有可靠导入入口，Skill 只能靠运维手改目录，无法形成可管理能力；而导入包是外部输入，原子性和路径安全必须从第一版具备。

**Independent Test**: 上传一个合法 Skill 包后立即查询列表并发起命中请求；再分别上传重名包、缺少 `SKILL.md` 的包和包含 `../` 路径穿越的包，均应明确拒绝且 Agent 目录没有残留文件。

**Acceptance Scenarios**:

1. **Given** 一个合法且名称未占用的 Skill 包，**When** 导入到指定 Agent，**Then** 校验通过后原子发布、默认启用，并在下一次请求可发现，无需重启。
2. **Given** 包结构、元数据或名称非法，**When** 导入，**Then** 返回可读校验错误，活动目录与 Skill 列表保持不变。
3. **Given** 包含绝对路径、目录穿越、符号链接或超过限制的内容，**When** 导入，**Then** 在写入活动目录前拒绝并清理暂存内容。
4. **Given** 同名 Skill 已存在，**When** 再次导入，**Then** 返回冲突，不静默覆盖现有 Skill。

---

### User Story 3 - 禁用与重新启用 Skill (Priority: P2)

管理员可以在不删除文件的情况下禁用某个 Skill，也可以重新启用。禁用状态跨重启保留；禁用后它不会出现在该 Agent 的运行时 L1 Skill 目录中，也不会被渐进式发现。

**Why this priority**: 运营需要先止用、观察再决定是否删除；禁用是比删除更安全的日常控制手段。

**Independent Test**: 禁用一个可正常命中的 Skill，重启服务后发起同样请求，确认元数据目录与加载轨迹均没有该 Skill；重新启用后下一次请求恢复可见。

**Acceptance Scenarios**:

1. **Given** 一个已启用 Skill，**When** 管理员禁用，**Then** 状态持久化，下一次请求的 Skill 目录不再包含它。
2. **Given** 一个已禁用 Skill，**When** 服务重启，**Then** 它仍为禁用状态。
3. **Given** 一个已禁用 Skill，**When** 管理员重新启用且 Skill 仍合法，**Then** 下一次请求恢复可发现；若文件已损坏则启用失败并保持禁用。
4. **Given** 旧会话曾读取过该 Skill，**When** 管理员禁用，**Then** 新请求不再通过 L1 发现该 Skill，系统也不改写历史消息；新会话中不得出现该 Skill 或由目录触发其读取。

---

### User Story 4 - 删除并留痕 (Priority: P2)

管理员可以删除某个 Skill。它立即从该 Agent 的活动 Skill 列表和后续请求中消失，底层完整目录被移入归档区并记录来源、时间和原所属 Agent，避免误删后无法追溯。

**Why this priority**: 删除是用户明确要求的管理闭环；采用可恢复归档与现有 Agent 删除语义一致，也更适合企业环境。

**Independent Test**: 删除一个启用中的 Skill，确认下一次请求不可发现、活动目录不存在、归档区保留完整包和删除元数据；删除不存在的 Skill 返回 404 且无副作用。

**Acceptance Scenarios**:

1. **Given** 一个已启用或已禁用 Skill，**When** 管理员删除，**Then** 它从活动列表消失，完整目录原子移入归档区。
2. **Given** Skill 已被删除，**When** 下一次请求开始，**Then** 元数据目录和加载链路均不再包含它。
3. **Given** Skill 不存在，**When** 删除或修改状态，**Then** 返回 404，其他 Skill 不受影响。

---

### User Story 5 - 在管理台完成 Skill 管理 (Priority: P3)

管理员进入某个 Agent 详情的 Skill 页签，可以查看 Skill 列表、状态、元数据和校验错误，并完成导入、禁用/启用、删除操作；所有危险操作都有清晰确认、加载中、成功和失败反馈。

**Why this priority**: REST 能力先保证核心可用，管理台再把它变成日常可操作产品闭环。

**Independent Test**: 仅通过管理台完成“导入 → 查看 → 禁用 → 重启确认状态 → 启用 → 删除”，每一步页面状态与 REST 查询一致。

**Acceptance Scenarios**:

1. **Given** 管理员打开 Agent 详情，**When** 切换到 Skill 页签，**Then** 看见名称、描述、状态、来源和更新时间，并能区分加载失败项。
2. **Given** 管理操作进行中或失败，**When** 页面收到响应，**Then** 禁止重复提交，并展示统一响应中的明确错误信息。
3. **Given** 管理员删除 Skill，**When** 完成二次确认，**Then** 页面在服务端成功后移除该项；失败时保留原状态。

### Edge Cases

- Agent 不存在、已归档或正在删除时导入 Skill，必须拒绝且不创建目录。
- 一个坏 Skill 不得让整个 Agent 无法启动；启动扫描应隔离坏项、报告错误，其他 Skill 正常可用。
- Skill 名称大小写、Unicode 归一化或路径别名造成同名冲突时，必须使用单一规范化规则判断唯一性。
- 导入在解压、校验或最终移动任一步失败时，暂存区必须清理，原 Skill 集合不变。
- Skill 数量或元数据总量超过上下文预算时，必须确定性拒绝或截断并明确告警，不能静默随机丢失。
- 手工修改仍存在的 `SKILL.md` 使内容/元数据失效，或从带 OryxOS 保留 marker 的包删除入口时，下一次快照不得加载并在管理列表暴露 invalid；无入口也无 marker 的普通目录继续按 legacy/unmanaged 忽略。
- 禁用/删除与一次正在运行的请求并发发生时，当前请求快照不变，后续请求看到新状态。
- 旧版 `skills/*.md` 子指令不得因本特性上线而使既有 Agent 启动失败。
- 包通过结构校验不代表内容可信；恶意指令、脚本或引用仍可能诱导 Agent 使用其已有 Tool 权限，管理台必须把导入呈现为信任边界。
- 管理 API 保证与正在运行的请求互斥；管理员直接在进程外改写文件系统不受进程内租约协调，下一次扫描必须自愈或暴露 invalid，不能依赖 WatchService 缓存。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 一个受管 Skill MUST 归属于且仅归属于一个 Agent，目录形态为该 Agent 下的独立 Skill 包，主入口为 `SKILL.md`；所属 Agent 的目录名 MUST 与 `AGENT.md` frontmatter `name` 精确一致，并复用统一安全名称解析；MUST NOT 建立跨 Agent 共享库、全局 Skill 索引或 `use_skill` Tool。
- **FR-002**: 系统 MUST 对每个新请求构建该 Agent 的已启用 Skill 元数据快照；L1 只暴露名称、描述和读取位置，MUST NOT 预载任一 Skill 正文或资源。
- **FR-003**: L2 完整指令 MUST 仅在任务命中后经既有 `read_file` 按需读取 `SKILL.md`；L3 参考资料与脚本 MUST 仅在 L2 指令需要时经既有 `read_file`/`shell` 按需取用，不新增自动执行通道。
- **FR-004**: 一次顶层请求内的 Skill 快照 MUST 保持不变；该请求 MUST 持有所属 Agent 的读取租约直至 ReAct 及会话保存结束。导入发布、禁用、启用、删除、Agent 删除，以及现有 Workspace/Agent files API 对受管 Skill 子树的任何写入 MUST 使用同一 Agent 的写入租约，从下一次顶层请求生效且无需重启。
- **FR-005**: 系统 MUST 支持向指定 Agent 上传并导入本地 Skill 包；导入 MUST 经“暂存 → 完整校验 → 原子发布”流程，中途失败 MUST 清理暂存且活动目录无变化。
- **FR-006**: 导入校验 MUST 覆盖包结构、必填元数据、安全 YAML 子集（无 custom tag/duplicate key/alias，有限深度与 code points）、Skill 名称与目录名一致性、规范化路径唯一性、文件数量、单文件/总大小、解压比、路径深度、路径穿越、绝对路径、符号链接、特殊文件、加密/不支持的 ZIP 条目及不允许的嵌套可执行/压缩格式；限制值 MUST 可配置且有安全默认值。
- **FR-007**: 同 Agent 下导入同名 Skill MUST 返回冲突并保持原内容；本特性 MUST NOT 提供静默覆盖或远程 URL/Git/Marketplace 拉取。
- **FR-008**: 系统 MUST 提供指定 Agent 的 Skill 列表和详情，至少返回名称、描述、状态（enabled/disabled/invalid）、管理员启用设置、来源、更新时间、入口、资源清单、文件统计及可读校验错误；entrypoint 使用 Agent 相对路径，resources 使用 Skill 包根相对路径，均不得暴露本机绝对路径。
- **FR-009**: 系统 MUST 支持禁用和重新启用；状态 MUST 落文件系统并跨重启保留。禁用 Skill MUST 从后续 L1 目录和加载链路中排除；启用前 MUST 重新校验。
- **FR-010**: 系统 MUST 支持删除 Skill；删除对外立即生效，底层 MUST 将完整包原子移入归档区并保存原 Agent、Skill 名称、删除时间和来源，不做物理擦除。
- **FR-011**: 启动扫描或手工修改遇到坏 Skill 时 MUST 隔离该项并记录错误，MUST NOT 阻断 Agent 或其他 Skill；修复后下一次扫描/请求可恢复。
- **FR-012**: 系统 MUST 提供 `/api/v1/agents/{agentName}/skills` 下的 REST 管理契约，并在 Agent 详情中提供 Skill 管理页签，覆盖查询、导入、禁用/启用和删除。
- **FR-013**: REST 错误 MUST 沿用统一响应信封：Agent/Skill 不存在为 404，同名冲突为 409，格式/安全/状态校验失败为 400，导入过大为 413；不得返回本机绝对路径或堆栈。
- **FR-014**: Skill 目录与状态 MUST 每次请求重新发现或通过可正确失效的快照读取，文件改动和管理操作无需重启即可生效。
- **FR-015**: 本特性 MUST 保持现有 Agent 目录和旧版 `skills/*.md` 子指令可用；兼容策略不得把旧文件误当成合法受管 Skill 后静默改变行为。
- **FR-016**: 每个进入 Skill 管理服务的导入、状态变更和删除调用 MUST 写结构化管理日志，至少包含 Agent、Skill、动作、结果和时间，且不得记录 Skill 文件内容或敏感配置；multipart/JSON/path 等在进入服务前被 Web 层拒绝的请求沿用 Web 错误日志，不伪装成已开始的领域动作。
- **FR-017**: L1 元数据目录 MUST 有确定性的排序和上下文预算；超限行为必须可预测并产生明确告警。
- **FR-018**: 系统 MUST 支持 Agent Skills 的 `name`、`description` 必填字段及 `license`、`compatibility`、`metadata`、`allowed-tools` 可选字段；未知扩展字段可保留或忽略但不得导致自动执行。`allowed-tools` MUST NOT 改写 Profile 的显式 Tool 列表。
- **FR-019**: 受管候选 MUST 仅为 `skills/` 下含 `SKILL.md` 或 OryxOS 保留 marker 的直接子目录，且只有合法 `skills/<name>/SKILL.md` 可进入 L1；旧版 `skills/*.md` 及无入口/marker 的资源目录 MUST 保持 legacy/unmanaged，不自动迁移、不被禁用或删除 API 误操作。
- **FR-020**: 每个已经进入 Skill 管理服务的导入/状态/删除调用 MUST 且只能记录一条结构化领域事件，包含稳定的 `event`、Agent、Skill、动作、结果及失败原因码；日志不得包含包正文、本机路径、密钥或未清洗的异常文本。
- **FR-021**: 手工 workspace Skill 与导入包 MUST 使用同一内容校验；catalog、详情与 snapshot 遍历 MUST 不跟随链接并验证真实路径仍在 Skill 根内。真实包目录中的入口/resource 链接或特殊文件候选 MUST 标为 invalid；`skills/` 下的根目录 symlink MUST 完全不跟随、不列为 Skill并写安全告警，二者都不得进入 L1。
- **FR-022**: 禁用/删除 MUST 阻止后续 snapshot 与由 Skill 目录触发的 L2/L3 渐进读取，但 MUST NOT 静默改写或删除既有 Session 历史、Tool 审计或 LLM 审计；“已知内容遗忘/历史来源过滤”不属于本特性。

### Key Entities

- **Skill Package**: 隶属某个 Agent 的目录包，含 `SKILL.md` 及可选 `references/`、`scripts/`、`assets/` 等资源；目录自身是内容真相源。
- **Skill Metadata**: 从 `SKILL.md` frontmatter 派生的轻量信息，用于 L1 发现，不含完整指令正文。
- **Skill Snapshot**: 一次顶层请求开始时冻结的已启用 Skill 元数据集合，保证一轮 ReAct 内一致。
- **Skill State**: enabled、disabled、invalid 三态；管理员启用设置由包内保留 marker 持久化，invalid 由当前文件内容校验派生而不单独落盘。
- **Skill Lease**: 以规范化 Agent 名为键的进程内公平读写租约；一次顶层请求持有读租约，管理发布/变更/删除持有写租约，保证请求内 L1/L2/L3 一致。
- **Import Staging Area**: 导入时隔离解包和校验的临时目录，成功才原子移动到活动区。
- **Archived Skill**: 删除后移出活动区的完整 Skill 包及归档元数据，不参与运行时发现。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 自动化测试证明首个 prompt 对每个 Skill 只包含 L1 元数据，0 个未命中 Skill 的正文或资源进入上下文。
- **SC-002**: 两个 Skill 并存时，命中其中一个的端到端测试只产生该 Skill 的 L2/L3 读取，未命中 Skill 的文件读取次数为 0。
- **SC-003**: 合法 Skill 包导入后无需重启，在下一次请求即可发现；非法、重名或恶意包 100% 被拒且活动目录残留文件数为 0。
- **SC-004**: 禁用、重新启用和删除均在下一次请求生效；状态跨一次完整服务重启保持正确。
- **SC-005**: 路径穿越、绝对路径、符号链接、超限包和不允许文件类型的安全回归全部通过。
- **SC-006**: 删除后活动目录与运行时目录中 0 条该 Skill 记录，归档区保留完整内容和可追溯元数据。
- **SC-007**: 既有 Agent 与旧版子指令回归通过；一个 invalid Skill 不影响同 Agent 其他 Skill 或 Agent 启动。
- **SC-008**: 管理台可独立完成“导入 → 禁用 → 启用 → 删除”闭环，页面状态与 REST 结果一致。
- **SC-009**: `mvn clean verify` 与前端生产构建全绿，新增契约和关键并发/安全路径均有自动化覆盖。
- **SC-010**: 并发测试证明禁用/删除在运行中请求结束前不会移动或改变其 Skill 文件；写操作一旦排队，后续新请求不会持续插队读取旧状态。

## Assumptions

- 第一版导入格式为本地上传的单 Skill ZIP 包；接受根目录直接包含 `SKILL.md`，或仅有一个与 Skill 名一致的顶层目录。批量导入、远程仓库、Marketplace、签名验证、版本依赖解析不在本特性范围。
- 导入成功后默认 enabled；删除采用可恢复归档，恢复 API 不在本特性范围。
- OryxOS 核心阶段仍无认证/RBAC，Skill 管理沿用现有内网部署假设；未来认证接入后复用相同 REST 资源边界。
- 渐进式加载复用既有 `read_file` 与 `shell`，不新增 `use_skill` 或自动工具执行路径。
- Skill 管理状态跟随 Agent 目录落文件系统，不为 enabled/disabled 单独新增 SQLite 表。
- 禁用的产品语义是从 OryxOS 的 L1 发现与正常渐进加载链路中排除；Skill 仍属于受信任工作区内容，本特性不把通用 `shell` 升级为逐路径的强制访问控制器。
- 禁用/删除不让模型追溯性遗忘已进入既有 Session 历史的正文或 Tool 结果；不可发现性验收使用新 Session，并单独断言旧历史没有被篡改。
