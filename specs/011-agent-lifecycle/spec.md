# Feature Specification: 动态管理 Agent —— 一句话生成、上传即上线

**Feature Branch**: `class-30`

**Created**: 2026-07-18

**Status**: Draft

**Input**: 第30节需求：动态管理 Agent——一句话生成、上传即上线，让 Agent 目录能通过 API / 页面 / 直接丢目录动态增删改查、全程免重启。

## Clarifications

### Session 2026-07-18

- Q: 一句话生成用哪个 provider/model（系统无全局默认，model 只在各 Agent frontmatter 里）？ → A: 新增默认配置键 `oryxos.generate.provider` + `oryxos.generate.model`（对齐课件"系统默认 provider"）；`provider` 缺省时取 `oryxos.providers` 第一个。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 建一个 Agent 即上线（免重启） (Priority: P1)

业务系统 / 运营调一次创建 API 带上一个 Agent 定义，返回成功后**不重启**，这个 Agent 立刻出现在列表里、到它自己声明的时间点自动跑。创建中途任一步失败必须回滚，系统里不留半个 Agent。

**Why this priority**: 这是"动态管理"的核心——把 29 节"手写目录 + 重启"变成"调一次 API 即上线"。是本节 MVP，其余故事都建立在"写一个 Agent 目录 → 派生注册"这条链上。

**Independent Test**: 调创建 API 建一个带定时的 Agent，不重启，查列表应立刻出现；注入一个注册失败，核对已写的目录被回滚、注册表里没有它。

**Acceptance Scenarios**:

1. **Given** 一个合法的 Agent 定义，**When** 调创建 API，**Then** 返回成功后不重启，查询列表立刻出现这个 Agent；带定时的到点自动跑。
2. **Given** name 已存在，**When** 调创建 API，**Then** 第一步就拒（400），一个目录都不写。
3. **Given** 创建过程中注册这一步失败，**When** 调创建 API，**Then** 已写的 Agent 目录被回滚删除，注册表里查不到它。

---

### User Story 2 - 丢一个目录也即上线（两条录入殊途同归） (Priority: P1)

运维直接往 Agent 目录区拷 / 改 / 删一个 Agent 目录（scp / git / 编辑器），不走 API，几秒内它就被登记 / 注销、可用——因为目录区被实时监听。API 上传与手工丢目录，底层落到**同一段"注册一个 Agent 目录"的代码**。

**Why this priority**: "上传即上线"若不等于"丢目录即上线"，就名不副实。这条也是 29 节"API 建的和文件建的行为一模一样"的兑现，与 US1 同为 P1。

**Independent Test**: 往目录区手工放一个 Agent 目录（不调 API），几秒内查注册表应出现；删掉目录应注销；放一个坏目录不拖垮监听、其余照常。

**Acceptance Scenarios**:

1. **Given** 系统运行中，**When** 往 Agent 目录区手工放一个合法 Agent 目录，**Then** 监听事件触发注册，免重启后它出现在注册表。
2. **Given** 一个已登记的 Agent，**When** 手工删掉它的目录，**Then** 监听事件触发注销，注册表里不再有它。
3. **Given** 手工放进一个坏目录（定义非法），**When** 监听处理它，**Then** 记日志跳过、不拖垮监听，其余 Agent 照常。

---

### User Story 3 - 一句话生成一个 Agent（人在环里） (Priority: P2)

运营在管理台说一句话——"每天早上九点查北京天气，把穿搭建议发到团队群"——系统用一次 LLM 调用产出一份规范的 Agent 定义草稿，**原样返回预览、不落盘、不注册**；运营看一眼、可改（尤其定时时刻、工具权限）→ 满意了再走创建。

**Why this priority**: 把"定义 Agent"的门槛降到一句话，是本节最亮的能力；但必须留"人过一眼"这步（LLM 可能把 cron 理解错、给多权限），所以是生成→预览→创建两步，不直接上线。

**Independent Test**: 调生成 API 传一句话，核对返回的草稿能被解析成合法 Agent 定义、且此刻注册表里没有它、盘上也没写；LLM 产出非法定义时返回 400 + 可读原因。

**Acceptance Scenarios**:

1. **Given** 一句话需求，**When** 调生成 API，**Then** 返回一份能被解析成合法 Agent 定义的草稿，且**不落盘、不注册**（注册表无变化）。
2. **Given** 生成的草稿，**When** 运营改完再调创建 API，**Then** 才真正落盘 + 注册。
3. **Given** LLM 产出的不是合法 Agent 定义，**When** 调生成 API，**Then** 返回 400 + 可读原因。

---

### User Story 4 - 查 / 改 / 删一个 Agent（删除时序安全） (Priority: P2)

对已定义的 Agent 做列表 / 详情查询、更新、删除。更新改正文 / provider / 通知目标覆写即时生效；定时变了必须先注销旧句柄、再注册新的。删除必须**先注销定时 → 再移出索引 → 再归档目录**（顺序反了会在窗口期空指针），目录归档不物理删。

**Why this priority**: 管起来就要能改能删；删除 / 改定时的**时序**是容易踩的时序 bug（先删索引后停定时，中间 cron 一触发就空指针），必须钉死。

**Independent Test**: 删一个带定时的 Agent，核对调用顺序是"注销定时→移出索引→归档"；改一个 Agent 的定时，核对先注销旧、再注册新；查 / 改 / 删不存在的 Agent 返回 404。

**Acceptance Scenarios**:

1. **Given** 一个带定时的 Agent，**When** 删除它，**Then** 顺序是"先注销定时 → 再移出注册表 → 再把整个目录归档到归档区（不物理删）"。
2. **Given** 一个 Agent 改了定时，**When** 更新它，**Then** 先注销旧句柄、再注册新的（旧定时不会跟新定时一起跑）。
3. **Given** 一个不存在的 Agent，**When** 查 / 改 / 删它，**Then** 返回 404。

---

### User Story 5 - 工作区文件浏览器 + 管理台补"管" (Priority: P3)

管理台像文件浏览器一样：列出 Agent 目录区与归档区的目录树、钻进一个 Agent 目录看它的定义 / 脚本 / 子指令内容（只读）。文件读取必须防目录穿越。管理平台加"Agent 管理""工作区"两页，从"只读"升级成"能管"。

**Why this priority**: 让运营在页面上看清、走通"一句话建 → 预览 → 看它跑 → 浏览目录 → 编辑 → 删除"闭环；是体验完善项，优先级低于核心 API。

**Independent Test**: 调目录树 API 应返回 agents/archive 结构、可钻进 Agent 目录列文件；调文件读取 API 传一个越界路径（如 ../../etc/passwd）应被拒（400）；传合法路径返回内容。

**Acceptance Scenarios**:

1. **Given** 目录区有若干 Agent，**When** 调目录树 API，**Then** 返回 agents / archive 的目录树，每个 Agent 一个可展开的目录。
2. **Given** 一个越界文件路径（解析后落在工作区外），**When** 调文件读取 API，**Then** 被拒（400）。
3. **Given** 一个工作区内的合法文件路径，**When** 调文件读取 API，**Then** 返回文件文本内容。

---

### Edge Cases

- 创建中途失败（写目录后派生 / 注册抛错）→ 回滚已写目录，不留半个 Agent（US1-3）。
- 手工放进坏目录 → 监听记日志跳过、不拖垮监听（US2-3）。
- 删除 / 改定时的时序颠倒 → 窗口期 cron 触发空指针，靠时序断言钉死（US4）。
- 文件读取路径穿越（`../` 越界、绝对路径越界）→ 一律拒绝（US5-2）。
- API 创建只写主文件；带脚本 / 子指令的复杂 Agent 走手工丢目录（明确不做 multipart/zip 上传）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 对外只有一类资源——Agent（一个目录）；唯一真相源是 Agent 目录区。系统 MUST 提供两条录入路径且行为一致：API 上传（校验 + 写 Agent 目录）、手工丢目录。
- **FR-002**: 系统 MUST 实时监听 Agent 目录区（启动全量扫 + 之后监听新增 / 改 / 删）；任何 Agent 目录变更 MUST 触发同一段注册 / 注销，全程免重启。
- **FR-003**: 创建 Agent MUST 按序执行：校验（provider 存在、tool 已注册）→ 写 Agent 目录 → 派生 → 注册 → 有定时则注册定时；中途任一步失败 MUST 回滚已写目录、不留半个 Agent。
- **FR-004**: 一句话生成 MUST 是一次 LLM 调用产出 Agent 定义草稿并原样返回；MUST NOT 落盘、MUST NOT 注册；生成 MUST 落审计。
- **FR-005**: 系统 MUST 支持查询已定义 Agent 的列表与单个详情。
- **FR-006**: 更新 Agent MUST 支持覆写正文 / provider / 通知目标即时生效；定时变更时 MUST 先注销旧句柄、再注册新的。
- **FR-007**: 删除 Agent MUST 按序：注销定时 → 移出注册表 → 整个目录归档到归档区（MUST NOT 物理删）；手工删目录 MUST 对称触发注销。
- **FR-008**: 系统 MUST 提供只读工作区浏览：列 Agent 目录区 / 归档区目录树、读文件文本；文件读取 MUST 防目录穿越——路径解析后必须落在工作区内，否则拒绝。
- **FR-009**: 三种录入（API 上传 / 手工丢目录 / 启动扫描）MUST 落到同一段"注册一个 Agent 目录"的代码。
- **FR-010**: 错误码 MUST 沿用既有 Web 口径：name 已存在 / 定义非法 / provider 不存在 → 400；查 / 改 / 删不存在 → 404；生成产出非法定义 → 400；统一响应信封。
- **FR-011**: 管理平台 MUST 新增"Agent 管理"页（列表 + 查看 / 编辑 / 删除 + 一句话新建的预览可改流程）与"工作区（文件浏览器）"页（目录树 + 只读看文件）。

### Key Entities *(include if feature involves data)*

- **Agent 目录**: Agent 目录区里的一个目录，唯一真相源。API 上传 / 手工丢目录 / 启动扫描三条来源都落到它。
- **一句话草稿**: 一次 LLM 调用产出的 Agent 定义文本，只用于预览，不落盘不注册。
- **归档区**: 删除的 Agent 目录整体移入处，不物理删，供追溯。
- **目录变更事件**: 实时监听 Agent 目录区产生的新增 / 改 / 删事件，驱动同一段注册 / 注销。
- **定时句柄**: 29 节登记时留下的可注销引用，删除 / 改定时时用它注销。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 调一次创建 API 建一个带定时的 Agent，不重启，查列表立刻可见、到点自动跑。
- **SC-002**: 往 Agent 目录区手工放一个 Agent 目录（不走 API），几秒内查列表即出现——与 API 上传殊途同归。
- **SC-003**: 创建过程中任一步失败，已写的 Agent 目录被回滚、注册表里干干净净。
- **SC-004**: 删除一个带定时的 Agent，调用顺序恒为"注销定时 → 移出索引 → 归档目录"；删除后目录在归档区、历史审计仍可查。
- **SC-005**: 一句话生成产出能被解析成合法 Agent 定义的草稿，且此刻不落盘、不注册。
- **SC-006**: 文件读取传越界路径被拒；传合法路径返回内容。
- **SC-007**: 自动化验收（课件 harness 全绿）覆盖以上关键回归；`mvn clean verify` 全绿。

## Assumptions

- 前序节交付物均已就位并直接复用：29 节的派生校验、注册表 register/remove/exists、定时器 registerProfile 与可注销句柄表；26 节的 Agent 端点入口、统一响应信封、错误码与全局异常处理。
- 本节新增：给定时器加"按 Agent 注销定时"的方法（用 29 节留的句柄），课件已列为本节交付物。
- 一句话生成复用既有模型调用服务与审计；生成用的 provider / model 由新增配置键 `oryxos.generate.provider` + `oryxos.generate.model` 指定（见 Clarifications），`provider` 缺省取 `oryxos.providers` 第一个——不新增数据表。
- 实时监听用运行时标准库的目录监听能力；无新增第三方依赖、无新增数据表。
- 开发在 `class-30` 分支进行，不另建分支。
