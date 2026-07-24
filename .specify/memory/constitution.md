<!--
Sync Impact Report
- Version change: 1.1.0 → 2.0.0
- Bump rationale: MAJOR — Principle IV previously prohibited every cross-Agent capability library;
  it now defines the public Skill marketplace as the sole controlled sharing exception. Principle
  VIII is redefined so Agent identity/runtime configuration remains in AGENT.md while marketplace
  associations are persisted as filesystem links rather than YAML fields.
- Modified principles:
    IV.  一个目录 = 一个 Agent；AGENT.md 由 ContextLoader 加载，不作为 Tool
         → 一个目录 = 一个 Agent；公共 Skill 市场是唯一共享例外
    VIII. 配置即 Agent，实例无状态、状态外置
          → AGENT.md 定义运行配置，Skill 关联状态外置
- Added sections: none
- Removed sections: none
- Templates:
    ✅ .specify/templates/plan-template.md (marketplace exception gate added)
    ✅ .specify/templates/spec-template.md (marketplace trust/association requirements added)
    ✅ .specify/templates/tasks-template.md (marketplace boundary tests added)
    ✅ .specify/templates/checklist-template.md (reviewed; no change required)
    ✅ .specify/templates/commands/ (directory absent; no command templates to update)
- Runtime guidance:
    ✅ AGENTS.md
    ✅ CLAUDE.md
    ✅ README.md
    ✅ docs/TechnicalSolution.md
    ✅ docs/AiProgrammingGuide.md
    ✅ docs/CliGuide.md
- Active feature artifacts:
    ✅ specs/012-skill-management/spec.md
    ✅ specs/012-skill-management/plan.md
    ✅ specs/012-skill-management/tasks.md
    ✅ specs/012-skill-management/research.md
    ✅ specs/012-skill-management/quickstart.md
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

### IV. 一个目录 = 一个 Agent；公共 Skill 市场是唯一共享例外

一个 Agent MUST 是 `.oryxos/agents/<name>/` 一个目录：`AGENT.md` frontmatter 定义身份、Provider、
Model、Tool、Channel 与运行设置，正文定义任务指令。`AgentLoader.deriveProfile` MUST 从该文件派生
`Profile`；`AGENT.md` 正文 MUST 由 `oryxos-core` 的 `ContextLoader` 注入 system prompt。Agent
目录 MUST NOT 注册进 `ToolRegistry`，也 MUST NOT 放入 `oryxos-tool` 模块。

公共 Skill 市场是“不得建立跨 Agent 共享能力库”的**唯一受控例外**：受管 Skill 的唯一内容副本
MUST 位于 `.oryxos/skills/<skill-name>/SKILL.md`；Agent 只能通过系统在
`.oryxos/agents/<agent-name>/skills/<skill-name>` 创建、且原始目标严格等于
`../../../skills/<skill-name>` 的相对软链接建立关联。`AGENT.md`、`AGENTS.md`、数据库或独立关联
清单 MUST NOT 成为 Skill 关联真相源。除该市场外，项目 MUST NOT 新建其它跨 Agent 能力库、
共享目录或隐式全局索引。

Skill 仍是渐进披露的上下文包，不是可执行 Tool：运行时 MUST 只把有效且全局 enabled 的关联 Skill
之 `name`、`description`、入口作为 L1；命中后才通过既有 `read_file` 读取 L2 `SKILL.md`，再按需
使用既有 `read_file`/`shell` 获取 L3。项目 MUST NOT 新增 `use_skill` Tool，Skill MUST NOT 进入
`ToolRegistry`；`allowed-tools` MUST NOT 授权或扩大 Agent 的显式 Tool 权限。所有 L2/L3 操作仍
MUST 经过 `ToolExecutor`、沙箱与审计。公共导入是管理员的显式信任动作，系统 MUST 校验包结构、
路径与资源限制，但 MUST NOT 把结构安全宣称为内容可信。

**Rationale**: Agent 目录继续定义“谁以及如何运行”；公共 Skill 市场像软件包市场一样只保存一份
可审查能力，并以可见、可移动、可审计的软链接显式安装到 Agent。严格限定这一例外可避免 YAML
双写、全文 eager 注入和任意共享目录演变成执行旁路。

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

### VIII. AGENT.md 定义运行配置，Skill 关联状态外置

一个 Agent 的身份、Provider、Model、Tool 权限、Channel、调度与任务正文 MUST 完全由其
`AGENT.md` 定义，不需要编写 Java。公共 Skill 的安装关联是 Principle IV 明确允许的例外状态，
MUST 只由 Agent 目录中的标准相对软链接表达，MUST NOT 回写 `AGENT.md` Skill 名单。

运行实例 MUST 无状态；会话、记忆、公共 Skill 包、启停 marker 与 Agent-Skill 链接 MUST 外置到
SQLite 或文件系统。本期强删不创建 operation journal 或启动恢复流程，失败只做同进程尽力补偿。
表结构变更 MUST NOT 依赖 Hibernate 自动迁移（SQLite
`ALTER TABLE` 支持弱），需维护显式建表脚本或引入 Flyway。

**Rationale**: `AGENT.md` 保持运行配置的单一事实来源，市场安装关系保持文件系统可见且无需代码；
所有持久状态外置，才能让实例替换、工作区迁移与未来分布式部署不依赖进程内缓存。

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

**Version**: 2.0.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-24
