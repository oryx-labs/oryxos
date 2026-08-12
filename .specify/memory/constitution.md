<!--
Sync Impact Report
- Version change: 1.1.0 → 2.0.0
- Bump rationale: MAJOR — 原则 IV 从「禁止跨 Agent 共享 Skill」重定义为「公共 Skill 实体库 +
  Agent 本地相对软连接绑定 + 分层渐进式披露」，并移除 AGENT.md frontmatter skills 引用这一
  旧绑定方式；原则 VI 同步新增软连接真实路径安全门禁。
- Principles defined (8):
    I.   自实现 ReAct 循环 (NON-NEGOTIABLE)
    II.  Spring AI 仅做协议转换与 Schema 生成 (NON-NEGOTIABLE)
    III. Provider 显式映射
    IV.  一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露
    V.   审计 Day One 落库 (NON-NEGOTIABLE)
    VI.  安全是地基：强制沙箱与真实路径校验，不用 SecurityManager (NON-NEGOTIABLE)
    VII. 同步执行 + 虚拟线程，不引入异步编程模型
    VIII.目录配置即 Agent，实例无状态、状态外置
- Modified principles:
    IV.  禁止共享 Skill → 公共实体库 + Agent 本地软连接绑定 + 元数据常驻/正文按需
    VI.  路径白名单 → 软连接感知的真实路径白名单
    VIII.YAML Profile 定义 Agent → Agent 目录（frontmatter + 本地 Skill 绑定）定义 Agent
- Added sections: none
- Removed sections: none
- Templates checked:
    ✅ .specify/templates/plan-template.md (Constitution Check gate is dynamic; no edit needed)
    ✅ .specify/templates/spec-template.md (现有安全/边界 Edge Cases 可承载软连接约束)
    ✅ .specify/templates/tasks-template.md (已有 Tests + Security hardening 阶段，无需改模板)
    ✅ .specify/templates/constitution-template.md (通用模板，无项目语义)
    ✅ .specify/templates/commands/ (目录不存在，无命令模板待同步)
- Runtime guidance requiring sync in this amendment:
    ✅ CLAUDE.md
    ✅ README.md
    ✅ docs/DemandAnalysis.md
    ✅ docs/TechnicalSolution.md
    ⚠ AGENTS.md（未纳入当前 Git worktree；外部工作区指南需由维护者同步）
- Follow-up items: none（本特性的 spec/plan/tasks、实现与端到端验收已同步完成）。
-->

# OryxOS Constitution

OryxOS 是用 Java 实现的分布式 AI Agent OS——运行一群业务 Agent 的企业级底座。本宪法定义
不可违背的工程与架构铁律，凌驾于一切个人偏好与临时便利之上；所有代码、Spec、Plan 与实现
必须遵守。原则冲突时以本文件为准。

## Core Principles

### I. 自实现 ReAct 循环 (NON-NEGOTIABLE)

`ReActLoop` MUST 由项目自己实现，完整掌握「思考 → 调工具 → 回填结果 → 再思考」的每一步。
MUST NOT 使用 Spring AI 的 Agent 抽象或 `ChatClient` 的自动工具执行。工具的调度与执行由
`ReActLoop` + `ToolExecutor` 独占控制，循环行为（迭代上限、终止条件、上下文裁剪）必须可定制。

**Rationale**: Agent 的核心竞争力在运行机制本身；把循环交给外部框架会丧失控制权，且会导致
工具被重复调用。

### II. Spring AI 仅做协议转换与 Schema 生成 (NON-NEGOTIABLE)

Spring AI（Alibaba）在 OryxOS 中只允许做两件事：(1) 各家 LLM Provider 的协议差异吸收；
(2) `@Tool` 注解的 JSON Schema 生成。MUST 禁用其自动 tool 执行与 eager 模型自动装配
（如 `DashScopeAutoConfiguration`）。调用方式必须是 `chatModel.call(new Prompt(...))`，
返回的 tool call 由项目自己解析并执行。

**Rationale**: 与原则 I 一致——只借管道，不交控制权；自动装配还会在无 API key 时阻断启动。

### III. Provider 显式映射

多 Provider 并存时 MUST 维护显式的 `provider name → ChatModel` 映射表。MUST NOT 依赖扫描
Spring 容器中的 `ChatModel` Bean 类型来区分 Provider（类型相同会路由错乱）。

**Rationale**: 显式映射是多模型可预测路由的唯一可靠方式。

### IV. 一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露

一个 Agent MUST 是 `.oryxos/agents/<name>/` 一个目录：`AGENT.md` 的 frontmatter 定义运行配置，
正文定义任务指令；可选 `scripts/`、`REFERENCE.md` 与 `skills/` 构成该 Agent 的本地资源视图。
`AgentLoader.deriveProfile` MUST 从 frontmatter 派生 `Profile`，`ContextLoader` MUST 每次现读
`AGENT.md` 正文并注入 system prompt，不得缓存。

公共 Skill 实体 MUST 存在 `.oryxos/skills/<name>/`，至少包含带 `name`、`description`
frontmatter 的 `SKILL.md`。Agent 可见的 Skill MUST 且只能由
`.oryxos/agents/<agent>/skills/<name>` 下指向公共实体的受控相对软连接表达；这组本地软连接是
绑定关系的唯一真相源，MUST NOT 再用 `AGENT.md` frontmatter `skills:` 或其它并行索引表达同一关系。

`ContextLoader` MUST 在每次 prompt 组装时重新扫描当前 Agent 的 `skills/`，稳定排序并验证软连接，
只注入每个有效绑定的 `name`、`description` 与 Agent 本地绝对读取路径。MUST NOT 预载
`SKILL.md` 正文、references、模板或脚本；模型需要某项能力时，MUST 复用底座既有 `read_file` /
`shell` 按需读取或运行，使内容仅以工具结果进入后续 ReAct 上下文。MUST NOT 新增 `use_skill`
工具；Skill 是上下文资源，不是可执行 Tool，不得进入 `ToolRegistry`。

Skill create/import/update/delete、Agent bind/unbind/archive/delete 与启动恢复 MUST 执行一致性检查，
识别 dangling、escaped、invalid-target、name-mismatch 与 stale-reference。删除仍被 Agent 引用的
公共 Skill MUST 默认拒绝并返回引用 Agent 列表，不得制造悬空软连接。Skill 加载、绑定与协调实现
归 `oryxos-core`，内置 Tool 与 MCP Client 仍合并在单一 `oryxos-tool` 模块。

**Rationale**: 公共实体消除 Skill 内容复制，Agent 本地软连接明确能力边界；元数据常驻、正文按需
兼顾发现能力与上下文成本。唯一绑定真相源与强制一致性检查避免 frontmatter/目录漂移和悬空引用。

### V. 审计 Day One 落库 (NON-NEGOTIABLE)

`tool_invocations` 与 `llm_calls` 两张审计表 MUST 从核心阶段起就写入 SQLite（无需查询接口，
但写入不可省）。MUST NOT 以「日志足够」为由跳过落库。

**Rationale**: 可审计是 OryxOS 的核心差异化能力；事后从日志反解析代价高且不可靠。

### VI. 安全是地基：强制沙箱与真实路径校验，不用 SecurityManager (NON-NEGOTIABLE)

工具调用 MUST 经 `SandboxChecker` 白名单校验：文件走路径白名单、Shell 走命令首 token 白名单、
HTTP 走域名通配白名单。MUST NOT 使用 `SecurityManager`（JDK 21 已不可用）。凭证 MUST 走
环境变量 / 企业密钥体系，MUST NOT 明文写入代码、配置、日志或提交历史。最小权限、来源受控、
全链路可审计从第一天就在架构里，不是补丁。

任何允许软连接的文件访问 MUST 校验真实路径：已存在目标经 `toRealPath()` 解析后仍须位于允许根；
创建新路径时须解析最近已存在父目录的真实路径后再校验。Agent Skill 绑定 MUST 拒绝绝对软连接、
链式越界以及指向 `.oryxos/skills/` 之外的目标。字符串形式的 `normalize()+startsWith()` 不足以
作为软连接场景的安全判定。

**Rationale**: 企业私有部署对安全零容忍；软连接可绕过纯字符串路径白名单，若不校验真实路径，
公共 Skill 绑定会成为读取工作区外文件的通道。

### VII. 同步执行 + 虚拟线程，不引入异步编程模型

核心阶段 MUST 全程同步阻塞，靠 Java 21 虚拟线程处理并发。MUST NOT 引入 Reactor / WebFlux /
`CompletableFuture` 等异步编程模型（SSE 流式等留待扩展阶段，且不得侵入核心循环）。

**Rationale**: 虚拟线程已让同步代码扛住高并发；异步会让复杂度激增、调试困难。

### VIII. 目录配置即 Agent，实例无状态、状态外置

一个 Agent MUST 完全由一个目录定义：`AGENT.md` frontmatter/正文给出配置与任务，本地 `skills/`
软连接给出可见 Skill，附属脚本/参考按需存在；定义 Agent 不需要写 Java 代码。运行实例 MUST 无状态，
会话与记忆等状态 MUST 外置（SQLite / 文件），为走向分布式预留路径。表结构变更 MUST NOT 依赖
Hibernate 自动迁移（SQLite `ALTER TABLE` 支持弱），需手工建表脚本或 Flyway。

**Rationale**: 「目录配置即 Agent」降低接入门槛并容纳渐进式资源；「状态外置」是未来分布式化
不大改设计的前提。

## 技术栈与架构约束

- 语言 / 运行时：Java 21（虚拟线程），框架 Spring Boot 3.x，构建 Maven 多模块（当前 9 模块）。
- 模块结构可按需演进：模块划分跟随 Agent 的能力域（Provider / ReAct / Memory / Tool /
  Sandbox / Channel / Web / Storage / 装配），不锁死当前清单——允许新建模块（如把沙箱独立为
  `oryxos-sandbox`）或调整模块边界。新建 / 改名 MUST 在对应特性的 plan 中声明理由，并同步更新
  `CLAUDE.md` 模块表与 `docs/TechnicalSolution.md` §10；跨模块契约放 `oryxos-core`（依赖倒置，
  下游模块实现），禁止模块间循环依赖。
- 部署：单可执行 fat JAR / 单二进制；可装在企业自有 K8s / 虚拟机 / 物理机，数据不出域，不锁云。
- HTTP：Spring MVC + 虚拟线程；持久化：SQLite + Spring Data JPA；日志：Logback + SLF4J
  （生产 JSON 结构化，禁用 `System.out`）。
- 开放标准优先：工具用 MCP、Agent 协作用 A2A、Agent 目录借 Anthropic Agent Skills 的形态，不另立协议。
- Skill 绑定：公共实体在 `.oryxos/skills/`，Agent 以本地相对软连接选择可见集合；元数据每轮注入，
  正文与附属资源按需读取，禁止复制内容或维护第二份绑定索引。
- 模块解耦：新增 Channel 或 Tool 只加新模块，不改 `oryxos-core`。
- 底座优先于 Agent：最重要的交付是让任意 Agent 可靠运行的环境，而非某个强大的 Agent。

## 开发流程与质量门禁

- 采用 Spec-Driven Development：constitution → specify → (clarify) → plan → tasks →
  (analyze) → implement。一次只推进一个特性。
- 每个特性一份 Profile 化的最小完备实现；分阶段克制：先做运行时内核最小完备集，治理与重型
  分布式基础设施待真实使用数据验证后再做。
- 质量门禁（MUST 全绿方可合并）：Spotless（Google 格式）+ 阿里 P3C 编码规约 + Checkstyle +
  SpotBugs/Find Security Bugs + OWASP Dependency-Check，全部接入 `mvn verify`。
- pre-commit 本地把关格式；CI 跑 `mvn verify`，任一检查失败即阻断合并。
- 敏感配置一律 `${ENV_VAR}` 占位，`ConfigLoader` 启动校验必填项，缺失即清晰报错，不静默失败。

## Governance

- 本宪法凌驾于其它一切实践之上；与个人偏好或临时便利冲突时，以本宪法为准。
- 修订流程：任何原则的新增 / 删除 / 重定义 MUST 通过 PR 提出，说明动机与影响，并同步更新受影响
  的模板（plan / spec / tasks）与运行时指南（`CLAUDE.md`、`docs/`）。
- 版本策略（语义化）：MAJOR = 不兼容的治理 / 原则删除或重定义；MINOR = 新增原则或实质性扩充；
  PATCH = 澄清、措辞、笔误等非语义调整。
- 合规审查：所有 PR 与代码评审 MUST 验证是否符合本宪法；违背原则的复杂度 MUST 显式论证，否则
  优先选择更简单、更符合原则的方案。
- 运行时开发指南以 `CLAUDE.md` 为准，其内容 MUST 与本宪法保持一致。

**Version**: 2.0.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-26
