<!--
Sync Impact Report
- Version change: 1.0.0 → 1.1.0
- Bump rationale: MINOR — 技术栈与架构约束新增「模块结构可按需演进」条款（用户在第17节
  tasks 停点批准）：模块划分跟随 Agent 能力域，不锁死 9 模块，可新建（如 oryxos-sandbox）
  或调整模块；新建/改名须在对应特性 plan 中声明并同步 CLAUDE.md 与 TechnicalSolution §10。
- Previous version change: (none) → 1.0.0  (initial ratification)
- Bump rationale (1.0.0): First formal adoption of the OryxOS constitution. MAJOR baseline.
- Principles defined (8):
    I.   自实现 ReAct 循环 (NON-NEGOTIABLE)
    II.  Spring AI 仅做协议转换与 Schema 生成 (NON-NEGOTIABLE)
    III. Provider 显式映射
    IV.  一个目录 = 一个 Agent；AGENT.md 由 ContextLoader 加载，不作为 Tool
    V.   审计 Day One 落库 (NON-NEGOTIABLE)
    VI.  安全是地基：强制沙箱白名单，不用 SecurityManager (NON-NEGOTIABLE)
    VII. 同步执行 + 虚拟线程，不引入异步编程模型
    VIII.配置即 Agent，实例无状态、状态外置
- Added sections: 技术栈与架构约束; 开发流程与质量门禁; Governance
- Removed sections: none (initial)
- Templates checked:
    ✅ .specify/templates/plan-template.md   (Constitution Check gate is dynamic; no edit needed)
    ✅ .specify/templates/spec-template.md    (no constitution-specific tokens)
    ✅ .specify/templates/tasks-template.md   (no constitution-specific tokens)
- Follow-up TODOs: none
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

### IV. 一个目录 = 一个 Agent；AGENT.md 由 ContextLoader 加载，不作为 Tool

一个 Agent MUST 是 `.oryxos/agents/<name>/` 一个目录（借 Anthropic Agent Skills 的目录形态，
但定义的是 Agent）：`AGENT.md` = frontmatter（这个 Agent 自己的 profile）+ 正文（任务指令），
外加可选 `skills/*.md` 子指令、`scripts/` 脚本、`REFERENCE.md`。`AgentLoader.deriveProfile`
MUST 把 frontmatter 派生成 `Profile` 复用底座。`AGENT.md` **正文** MUST 由 `oryxos-core` 的
`ContextLoader` 注入 system prompt，与 Bootstrap 文件（`AGENTS.md`、`SOUL.md`、`USER.md`）同类；
目录里的子指令 / 脚本经底座既有 `read_file`/`shell` 按需取用。MUST NOT 建跨 Agent 的共享能力库、
`use_skill` 工具或全局能力索引；一个 Agent 目录 MUST NOT 注册进 `ToolRegistry` 或放入 `oryxos-tool`
模块。内置 Tool 与 MCP Client MUST 合并在单一 `oryxos-tool` 模块，不拆分。

**Rationale**: Agent 目录是上下文来源而非可执行工具；每个 Agent 自足独立，只调用底座系统基础能力，
混淆两者会在执行期报错、并让模块依赖混乱。

### V. 审计 Day One 落库 (NON-NEGOTIABLE)

`tool_invocations` 与 `llm_calls` 两张审计表 MUST 从核心阶段起就写入 SQLite（无需查询接口，
但写入不可省）。MUST NOT 以「日志足够」为由跳过落库。

**Rationale**: 可审计是 OryxOS 的核心差异化能力；事后从日志反解析代价高且不可靠。

### VI. 安全是地基：强制沙箱白名单，不用 SecurityManager (NON-NEGOTIABLE)

工具调用 MUST 经 `SandboxChecker` 白名单校验：文件走路径白名单、Shell 走命令首 token 白名单、
HTTP 走域名通配白名单。MUST NOT 使用 `SecurityManager`（JDK 21 已不可用）。凭证 MUST 走
环境变量 / 企业密钥体系，MUST NOT 明文写入代码、配置、日志或提交历史。最小权限、来源受控、
全链路可审计从第一天就在架构里，不是补丁。

**Rationale**: 企业私有部署对安全零容忍；安全若非地基，后期无法补齐。

### VII. 同步执行 + 虚拟线程，不引入异步编程模型

核心阶段 MUST 全程同步阻塞，靠 Java 21 虚拟线程处理并发。MUST NOT 引入 Reactor / WebFlux /
`CompletableFuture` 等异步编程模型（SSE 流式等留待扩展阶段，且不得侵入核心循环）。

**Rationale**: 虚拟线程已让同步代码扛住高并发；异步会让复杂度激增、调试困难。

### VIII. 配置即 Agent，实例无状态、状态外置

一个 Agent MUST 完全由一份 YAML Profile 定义，不需要写代码。运行实例 MUST 无状态，会话与
记忆等状态 MUST 外置（SQLite / 文件），为走向分布式预留路径。表结构变更 MUST NOT 依赖
Hibernate 自动迁移（SQLite `ALTER TABLE` 支持弱），需手工建表脚本或 Flyway。

**Rationale**: 「配置即 Agent」降低接入门槛；「状态外置」是未来分布式化不大改设计的前提。

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

**Version**: 1.1.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-10
