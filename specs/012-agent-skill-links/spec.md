# Feature Specification: Agent Skill 软连接绑定与渐进式加载

**Feature Branch**: `codex/agent-skill-progressive-loading`

**Created**: 2026-07-26

**Status**: Approved

**Input**: Issue #40 已商议通过：公共 Skill 统一存储，每个 Agent 通过自身 `skills/` 下的相对软连接
选择可见 Skill；每轮 prompt 只加载这些 Skill 的名称、描述和读取路径，正文与附属资源按需加载；
CRUD 和启动恢复必须检测残留、悬空与越界链接。

## Clarifications

### Session 2026-07-26

- Q: 旧版 `AGENT.md` 中已有的 `skills:` 应如何处理？ → A: 启动时原子转换为本地软连接；全部成功后删除 frontmatter 字段，失败则保持原文件并报告。
- Q: 删除公共 Skill 时，归档 Agent 中的软连接是否也算引用？ → A: 算；活跃与归档 Agent 的引用均阻止删除并完整列出，且无引用时的“删除”改为归档整个 Skill 目录，不物理删除。
- Q: 无引用 Skill 被“删除”后归档到哪里？ → A: `.oryxos/archive/skills/<name>-<timestamp>/`。
- Q: 创建或生成 Agent 时初始 Skill 绑定由谁决定，公共/私有 Skill 如何存储？ → A: 用户选择是必选项，作者模型可从外部列表自动补充公共或私有 Skill；两类安装后都统一存放在 `.oryxos/skills/<name>/`，公共/私有仅是列表的来源或可见性属性，Agent 一律用本地软连接绑定，不保存私有副本。
- Q: 私有 Skill 的访问控制由谁负责？ → A: 外部 Skill 列表只返回调用者可访问的结果；OryxOS 本阶段不实现 Skill ACL，暂不考虑 owner/scope、认证或 RBAC。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Agent 按需使用已绑定 Skill (Priority: P1)

Agent 被触发时能看到自己已绑定 Skill 的名称与用途，但不会为未使用的 Skill 消耗正文上下文。
当任务命中某个 Skill 时，Agent 才读取完整指令并继续执行。

**Why this priority**: 这是渐进式加载的核心用户价值：既能发现能力，又控制上下文成本。

**Independent Test**: 给 Agent 绑定两个带独特正文标记的 Skill，触发一次 prompt 组装；结果只含
两者名称、描述与本地读取路径，不含正文标记。随后让模型调用文件工具读取其中一个，只有该 Skill
正文进入下一轮上下文并产生审计记录。

**Acceptance Scenarios**:

1. **Given** Agent 绑定两个 Skill，**When** 组装 prompt，**Then** 两个 Skill 的名称、描述和读取路径
   按名称稳定出现，正文和附属资源均不出现。
2. **Given** prompt 含某 Skill 的描述和读取路径，**When** 模型选择并读取它，**Then** 正文作为
   工具结果进入下一轮 ReAct 上下文，调用写入工具审计。
3. **Given** 公共库还有未绑定 Skill，**When** 组装该 Agent prompt，**Then** 未绑定 Skill 不可见。
4. **Given** Skill 描述或绑定关系已修改，**When** 下一轮组装 prompt，**Then** 新状态立即生效，
   无需重启。

---

### User Story 2 - 管理员安全管理公共 Skill 与 Agent 绑定 (Priority: P1)

管理员可以创建、导入和更新公共 Skill，并为指定 Agent 绑定或解绑；绑定不会复制 Skill 内容，
一个公共实体更新后所有绑定 Agent 下一轮都看到新元数据。

**Why this priority**: 公共复用和 Agent 能力边界必须同时成立，否则会出现内容复制或全局能力泄漏。

**Independent Test**: 创建一个公共 Skill，将其绑定到两个 Agent，核对两个 Agent 均生成相对软连接；
更新描述后两者下一轮 prompt 同步变化；解绑其中一个只移除本地绑定，不影响公共实体和另一个 Agent。

**Acceptance Scenarios**:

1. **Given** 公共 Skill 存在，**When** 为 Agent 绑定，**Then** Agent 本地出现指向公共实体的相对
   软连接，重复绑定不会产生重复状态。
2. **Given** 两个 Agent 绑定同一 Skill，**When** 更新公共 Skill，**Then** 两者下一轮均读取新描述
   和新正文。
3. **Given** Agent 已绑定 Skill，**When** 解绑，**Then** 只删除该 Agent 的绑定，公共实体不受影响。
4. **Given** 公共 Skill 仍被 Agent 引用，**When** 删除它，**Then** 删除被拒绝并返回引用 Agent
   列表，不产生悬空链接。
5. **Given** 用户在创建 Agent 时选择必选 Skill，且外部列表提供其它可访问的公共或私有 Skill，
   **When** 作者模型生成 Agent，**Then** 用户所选项全部绑定，模型可按任务额外选择列表中的 Skill；
   后端只为有效选择创建软连接，不复制内容、不接受列表外名称。

---

### User Story 3 - 运行时发现损坏或残留绑定 (Priority: P2)

运维人员在启动或 CRUD 后能发现不一致的 Skill 绑定，系统不会把损坏绑定静默注入 Agent prompt。

**Why this priority**: 软连接是文件系统状态，手工变更、异常中断或归档都可能产生残留，必须可观测。

**Independent Test**: 构造悬空、越界、缺少主文件、名称不一致的绑定，执行协调扫描；结果逐项分类，
无效绑定不进入 prompt，合法绑定继续工作。

**Acceptance Scenarios**:

1. **Given** Agent 目录存在悬空或越界链接，**When** 启动扫描，**Then** 系统记录分类明确的错误，
   跳过无效绑定且不阻断其它 Agent。
2. **Given** CRUD 或 Agent 归档/删除完成，**When** 执行一致性检查，**Then** 不残留 stale reference。
3. **Given** 合法绑定与损坏绑定并存，**When** 组装 prompt，**Then** 只包含合法绑定。

---

### User Story 4 - 软连接不能绕过沙箱 (Priority: P1)

企业管理员允许工作区文件访问时，恶意或误配置软连接不能让 Agent 读取工作区外文件。

**Why this priority**: 引入软连接会让纯字符串路径白名单失效，这是不可妥协的安全边界。

**Independent Test**: 在允许根内创建指向根外文件的软连接并调用文件读取；请求必须在接触目标内容前
被拒绝。合法的 Agent Skill 软连接仍能读取。

**Acceptance Scenarios**:

1. **Given** 白名单目录内软连接指向目录外，**When** 读取，**Then** 请求被拒绝且目标内容未泄漏。
2. **Given** 新文件路径的父目录经软连接逃逸，**When** 写入，**Then** 请求被拒绝且目录外不产生文件。
3. **Given** Agent Skill 使用受控相对软连接指向公共 Skill 根，**When** 读取，**Then** 沙箱放行。

### Edge Cases

- Agent 没有 `skills/` 目录或目录为空时，不注入空标题和噪声。
- `SKILL.md` 缺 name、description、正文为空或编码不可读时，绑定被分类为无效并给出可读原因。
- 链接名、公共目录名和 `SKILL.md` name 不一致时，不进入 prompt。
- Agent/Skill 名含路径分隔符、`.`、`..` 或绝对路径时，CRUD 必须拒绝。
- 公共 Skill 删除与 Agent bind 并发时，最终状态不能出现已确认成功的悬空绑定。
- Agent 归档后相对软连接仍须正确指向公共实体；物理删除 Agent 时不得删除公共 Skill。
- 公共 Skill 归档前必须确认活跃与归档 Agent 均无引用；归档保留 `SKILL.md` 和全部附属资源。
- 同名 Skill 多次创建、归档时，时间戳归档目录不得覆盖已有历史版本；归档目录不得被公共 Skill
  扫描或进入任何 Agent prompt。
- 自定义相对或绝对工作区根时，绑定、prompt 路径和沙箱判断保持一致。
- 公共与私有 Skill 同名时属于同一安装名称冲突，系统必须拒绝第二个实体，不得靠来源或可见性
  形成两个同名目录。
- 旧版 `skills:` 引用含不存在或无效 Skill 时，本次迁移整体失败：不得创建部分链接、不得改写
  `AGENT.md`；该 Agent 的旧字段不作为运行时绑定，只报告明确迁移错误。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 以共享 Skill 目录保存唯一已安装内容实体，每个实体 MUST 有可校验的名称
  和描述；公共/私有只作为外部列表的来源或可见性属性，不改变安装路径与全工作区名称唯一性。
- **FR-002**: Agent 可见 Skill MUST 且只能由 Agent 本地 `skills/` 中的受控相对软连接表达；
  frontmatter 或其它索引 MUST NOT 成为第二绑定真相源。
- **FR-003**: 每轮 prompt MUST 重新发现当前 Agent 的全部有效绑定，按名称稳定排列，只暴露名称、
  描述和 Agent 本地读取路径。
- **FR-004**: 系统 MUST NOT 在 prompt 中预载 Skill 正文、参考、模板或脚本。
- **FR-005**: Skill 正文与附属资源 MUST 通过既有文件/命令能力按需进入 ReAct 上下文，并保留审计。
- **FR-006**: 系统 MUST 支持已安装 Skill 的创建、导入、读取、更新和安全归档；用户发起“删除”时
  MUST 将完整 Skill 目录移动到 `.oryxos/archive/skills/<name>-<timestamp>/` 而非物理删除，且不得
  覆盖已有归档。
- **FR-007**: 系统 MUST 支持 Agent Skill 的绑定、解绑和查询；绑定创建必须幂等且不得复制内容。
- **FR-008**: 归档仍被引用的公共 Skill MUST 被拒绝，并返回所有活跃与归档引用 Agent；不得制造
  悬空链接。
- **FR-009**: CRUD、Agent 归档/删除与启动恢复 MUST 检测 dangling、escaped、invalid-target、
  name-mismatch 和 stale-reference，且无效绑定 MUST NOT 进入 prompt。
- **FR-010**: 文件沙箱 MUST 按真实路径判断已存在目标；创建路径 MUST 按最近已存在父目录的真实路径
  判断，阻止软连接逃逸。
- **FR-011**: Agent Skill 绑定 MUST 拒绝绝对软连接、链式越界和公共 Skill 根之外的目标。
- **FR-012**: Skill 内容或绑定修改 MUST 在下一轮 prompt 生效，不依赖进程重启或缓存失效。
- **FR-013**: 工作区浏览、Agent 管理与 Skill 管理界面 MUST 展示新的绑定模型和可读一致性错误。
- **FR-014**: 系统 MUST 支持自定义工作区根，所有暴露给模型的读取路径 MUST 是规范化绝对路径。
- **FR-015**: 启动时发现旧版 `AGENT.md` 的 `skills:` MUST 执行单 Agent 原子迁移：先校验全部
  引用，再创建对应本地软连接并删除该 frontmatter 字段；任一步失败 MUST 回滚本次创建的链接、
  保持原文件不变并报告，旧字段本身 MUST NOT 参与运行时绑定。
- **FR-016**: 外部 Skill 列表 MUST 支持查询公共和私有 Skill，并为作者模型提供名称、描述、
  来源/可见性元数据；访问过滤由外部列表负责，OryxOS MUST 只消费其返回结果且 MUST NOT 在本阶段
  实现 Skill ACL。列表结果 MUST NOT 直接成为 Agent 绑定。
- **FR-017**: 创建或生成 Agent 时，用户明确选择的 Skill MUST 全部绑定；作者模型 MAY 从外部列表
  自动补充公共或私有 Skill。所有候选 MUST 经存在性、可见性和名称校验后创建 Agent 本地软连接，
  列表外或不可访问候选 MUST 被拒绝，Agent 目录 MUST NOT 保存 Skill 内容副本。

### Key Entities

- **已安装 Skill 实体**: `.oryxos/skills/<name>/` 下唯一的可复用指令目录，包含名称、描述、正文和
  可选附属资源；公共/私有不产生不同的物理副本。
- **外部 Skill 列表项**: 可供查询和自动选择的候选元数据，包含名称、描述及公共/私有来源或可见性；
  只有安装并绑定后才成为 Agent 能力。
- **Agent Skill 绑定**: Agent 本地、指向公共 Skill 实体的相对软连接，是唯一绑定事实。
- **Skill 元数据目录**: 当前 Agent 每轮可见的有效 Skill 名称、描述和本地读取路径集合。
- **一致性问题**: 某个绑定的分类结果，包括 dangling、escaped、invalid-target、name-mismatch、
  stale-reference 及可读原因。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 对任意绑定集合，prompt 中 100% 出现有效绑定的名称和描述，0% 出现正文标记或未绑定 Skill。
- **SC-002**: 绑定 Skill 被选择后，一个标准 ReAct 流程可在下一轮获得完整正文，并留下对应工具审计。
- **SC-003**: 公共 Skill 更新后，所有绑定 Agent 在下一轮 prompt 中看到新描述，无需重启。
- **SC-004**: 所有公共 Skill 归档操作均不会产生悬空绑定或物理删除内容；存在引用时 100% 返回
  完整的活跃与归档引用 Agent 列表。
- **SC-005**: dangling、escaped、invalid-target、name-mismatch、stale-reference 五类问题均有自动化测试，
  且无效绑定注入率为 0。
- **SC-006**: 工作区内指向工作区外的读/写软连接逃逸测试 100% 被拦截，合法 Skill 软连接 100% 放行。
- **SC-007**: 新增功能与既有功能通过全部项目质量门禁，自动化测试无失败。
- **SC-008**: 对合法旧配置，启动迁移 100% 生成等价绑定且移除 `skills:`；对含任一非法引用的
  旧配置，100% 保持原文件和迁移前绑定状态不变并返回可读错误。
- **SC-009**: Agent 生成结果 100% 保留用户必选 Skill；模型补充项 100% 来自调用者可访问列表且
  通过后端校验，0% 产生 Skill 内容副本或列表外绑定。

## Assumptions

- 公共 Skill CRUD 继续复用现有管理入口；本特性调整绑定语义和安全行为，不引入新的持久化表。
- 一个 Agent 可以绑定零到多个 Skill，同一个公共 Skill 可以被多个 Agent 复用。
- 公共 Skill 被活跃或归档 Agent 引用时拒绝归档；本阶段不提供强制级联解绑或物理删除。
- 损坏绑定默认报告并跳过，不自动删除用户文件；显式解绑和删除操作保证自身不制造残留。
- Agent 运行阶段只从当前绑定元数据中按需选择；基于外部列表的公共/私有 Skill 自动选择只发生在
  Agent 创建或生成阶段。
- 私有 Skill 的 owner/scope、认证、授权和 RBAC 不在本特性范围；外部列表负责只返回调用者可访问
  的候选，OryxOS 不保存或推断访问策略。
- 旧版 frontmatter 迁移以单个 Agent 为原子边界；一个 Agent 迁移失败不阻断其它 Agent 的迁移与启动。
