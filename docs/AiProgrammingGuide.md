# OryxOS AI 编程指南

> 本文档定义 OryxOS 的 AI 编程实施思路。主体思路是用 **Spec-Kit** 完成主体开发，把已有的需求文档和技术方案喂给 Spec-Kit，按五大核心能力拆成 5 个 user story 逐步实施；后续增量阶段切换到手动提示词配合 Claude Code。前置阅读《项目篇 OryxOS 业界调研》《OryxOS 需求文档》《OryxOS 技术方案》。本文档讲思路和拆解方法，不绑定具体时间安排，也不展开提示词细节。

> 本文档以最新技术方案为准：核心阶段交付的是 Agent OS 的运行时内核，Maven 模块为 9 个（技术方案第 10 章），五大核心能力（对接 LLM、ReAct、Memory、Tool、Web Service）作为 5 个 user story 的骨架。

---

## 1. 实施总览

### 1.1 主体思路：Spec-Kit + 手动提示词的混合模式

OryxOS 的 AI 编程实施分两个阶段，两个阶段用不同的协作工具：

| 阶段 | 工具 | 适用场景 |
|------|------|---------|
| **主体开发阶段** | Spec-Kit | 从零开发 OryxOS 1.0 的五大核心能力，9 个 Maven 模块 |
| **增量开发阶段** | 手动提示词 + Claude Code | 扩展功能、修 bug、加 Plugin Tool 等小颗粒度增量 |

这两个阶段的边界很清晰：**Spec-Kit 适合大颗粒度 greenfield，手动提示词适合小颗粒度增量**。OryxOS 主体开发是前者，社区接力是后者，工具选择跟工作性质匹配。

---

### 1.2 跟需求文档 + 技术方案的关系

实施指引不重写需求和技术方案，而是把已有文档喂给 Spec-Kit。具体对应关系：

| Spec-Kit 输入 | 来源文档 | 作用 |
|--------------|---------|------|
| `/speckit.specify` 输入 | 需求文档 | Spec-Kit 把需求文档转成 5 个 user story 的 spec |
| `/speckit.plan` 输入 | 技术方案 | Spec-Kit 把技术方案转成模块化的实施 plan |
| `constitution.md` | 需求文档第 3 章设计目标 + 技术方案第 1.1 节关键技术决策 | 非协商原则 |
| acceptance criteria | 需求文档第 13 章 5 个验收 Demo | 直接复用 |

已有文档的投入不浪费，Spec-Kit 只是把它们转换成 AI agent 能直接消费的格式。

> **关键注意**：技术方案是 `/speckit.plan` 的输入，所以 plan 里的模块结构必须跟技术方案第 10 章的 9 个模块一致，喂文档时确保用的是最新版技术方案，否则生成的 plan 会按错误的模块数拆分。

---

### 1.3 拆解策略：按 user story 拆，不按时间拆

整个 OryxOS 主体开发按 **5 个 user story** 组织，每个对应一个核心能力。

5 个 user story 不是平行的，它们之间有明确的依赖关系，依赖关系决定推进顺序：

| User Story | 核心能力 | 依赖 | 可并行 |
|-----------|---------|------|--------|
| **US-1** | 对接 LLM（Provider 抽象） | 无 | — |
| **US-2** | ReAct 循环（Agent 大脑） | US-1 | — |
| **US-3** | Memory 三层记忆 | US-2 | 与 US-4 并行 |
| **US-4** | Plugin Tool 体系 | US-2 | 与 US-3 并行 |
| **US-5** | Web Service | US-1 ~ US-4 全部 | — |

**推进顺序**：US-1 → US-2 → （US-3 ∥ US-4）→ US-5

- US-1 是基础，没有 LLM 调用所有 Agent 能力都跑不起来
- US-2 依赖 US-1，ReAct 循环每轮都要调 LLM
- US-3 + US-4 并行依赖 US-2（Memory 注入 ReAct 的 prompt，Tool 被 ReAct 调用）
- US-5 依赖前 4 个，对外暴露所有能力

> 具体推进的时间投入由项目方根据团队情况决定。本文档按依赖顺序拆，不规定时长。需求文档和技术方案定的核心阶段节奏是 4 周每周 3 小时，5 个 user story 跟这个节奏的对应关系见技术方案第 12 章，本文档不重复。

这套 user story 拆法跟 Spec-Kit 的机制天然契合。Spec-Kit 的 `/speckit.tasks` 命令本身就是按 user story 组织任务的，每个 user story 成为一个独立的实施 phase，任务之间按依赖排序、可并行的标记出来。

---

## 2. Spec-Kit 跟 OryxOS 的匹配度评估

写实施计划之前先回答一个根本问题：Spec-Kit 真的适合 OryxOS 项目吗？

### 2.1 Spec-Kit 适合什么场景

Spec-Kit 是 GitHub 开源的 spec-driven development 工具链，是 2026 年增长最快的开发者工具之一，社区采用度很高，支持二十多种 AI coding agent。它把 AI 辅助编码结构化成可重复的 specify → plan → tasks → implement 流程，核心理念是让 spec 成为代码行为的契约和单一事实来源，把 AI agent 从"代码生成器"变成"按规格干活的协作者"，对治 vibe coding。

**社区共识适合的场景：**
- medium 到 large greenfield 项目（从零开发，工程量中到大，模块跨多个文件夹）
- 需求清晰（上游有明确的需求文档或产品决策）
- AI agent 协作（用 Claude Code、Copilot、Cursor 等做主体开发）
- 方法论场景（强制 spec-driven 流程，团队能学到工程方法论）

**不适合的场景：**
- 小 feature、快速原型、单文件改动（流程开销大于收益）
- 大型 brownfield 项目改造（legacy 代码上下文太复杂，超出 LLM context limit）
- 探索性研究项目（需求未定就跑 spec 会反复返工）

### 2.2 OryxOS 的匹配度判断

对照 Spec-Kit 适合的场景逐条评估 OryxOS：

| 评估维度 | OryxOS 情况 | 匹配度 |
|---------|------------|--------|
| greenfield | 从零开发的全新项目，不是改造现有代码 | ✅ 完全匹配 |
| 规模 | 9 个 Maven 模块、五大核心能力清晰，典型 medium 规模 | ✅ 完全匹配 |
| 需求清晰 | 已有完整的需求文档 + 技术方案，五大核心能力都有 user story 级别描述 | ✅ 完全匹配 |
| AI agent 协作 | OryxOS 本来就是用 Claude Code 做主体开发 | ✅ 完全匹配 |
| 方法论场景 | Spec-Kit 的强制流程让产出对齐需求，对开发者掌握工程方法论很有价值 | ✅ 匹配 |

**结论**：Spec-Kit 是 OryxOS 主体开发的最佳工具选择。社区在 brownfield 项目上对 Spec-Kit 有争议，但 OryxOS 是纯 greenfield，这些争议不适用。

### 2.3 Spec-Kit 的局限和应对

| 局限 | 描述 | OryxOS 的应对 |
|------|------|-------------|
| 流程对小增量过重 | Spec-Kit 完整流程对小改动开销过大 | OryxOS 主体开发是大颗粒度，增量阶段切换到手动提示词 |
| spec 不会自动跟实现同步 | AI agent 在 implement 阶段可能偏离 spec | 每个 user story 实施完成后跑 `/speckit.analyze` 做一致性检查 |
| context limit 在大型 brownfield 上失效 | 十万级文件的 legacy 项目 LLM 看不全 | OryxOS 是纯 greenfield，整个项目在 LLM context window 内，不适用 |
| Spec-Kit 本身在快速迭代 | 命令名、artifacts 格式、集成方式都还在变 | 本文档不锁定具体版本细节，具体命令以实施时官方文档为准 |

---

## 3. 准备阶段

准备阶段是正式实施前的脚手架工作，由项目方完成，产出三份 Spec-Kit artifacts（constitution、spec、plan），让后续每个 user story 的实施都有清晰的依据。

### 3.1 Spec-Kit 安装 + Claude Code 配置

Specify CLI 是 Spec-Kit 的入口工具（Python 实现，需要 Python 3.11+，推荐用 `uv` 安装）。安装后通过 `specify init` 初始化 OryxOS 项目的 Spec-Kit 工作区，工作区里有 `.specify/memory/constitution.md` 以及 spec、plan、tasks 等 artifacts 的目录结构。

Claude Code 是主推的 AI agent，Spec-Kit 官方支持 Claude Code。具体集成方式（早期是 slash 命令，现在 Claude Code 走 skills 模式，初始化时通过参数指定）以官方文档为准。

---

### 3.2 `/speckit.constitution`：写 OryxOS 项目宪章

`constitution.md` 是项目的 **non-negotiable principles**，所有后续 spec、plan、tasks、implement 都要遵守。OryxOS 的 constitution 从需求文档第 3 章设计目标 + 技术方案第 1.1 节关键技术决策提炼：

| # | 原则 | 说明 |
|---|------|------|
| 1 | JDK 21 + Spring Boot 3.x 单体应用 | Maven 多模块（9 个），单二进制部署 |
| 2 | 五大核心能力优先 | 核心阶段交付运行时内核，企业级治理层放扩展阶段 |
| 3 | 自实现 ReAct loop | 不直接用 Spring AI 的 Agent 抽象 |
| 4 | **Spring AI 只用一半** | 只用 Provider 抽象、协议转换、@Tool schema 生成；**禁用自动 tool 执行**；tool 调度完全由 `ReActLoop` + `ToolExecutor` 控制。**最容易被写错的一条** |
| 5 | Plugin Tool 三档接入 | 主推 Agent 私有 SKILL.md + 显式配置的内置/MCP Tool 零代码方式 |
| 6 | SQLite + MEMORY.md 文件存储 | 向量检索放扩展阶段；`tool_invocations` 和 `llm_calls` **核心阶段就写入落库** |
| 7 | 每个 user story 完成后有可演示 Demo | 优先级是跑通而非完美 |

> `constitution.md` 写一次定下来，整个主体开发期间不改。如果中途发现某条原则不对，停下来重新讨论，**不允许 AI agent 自己修改 constitution**。

---

### 3.3 `/speckit.specify`：把需求文档转成 5 个 user story

`/speckit.specify` 命令的输入是需求文档，输出是 5 个 user story 的 spec，每个 user story 对应一个核心能力。

5 个 user story 按依赖关系排推进顺序，而不是按重要性。这里要特别说明：US-5 Web Service 排在最后实施，是因为它依赖前四个能力都就绪，**不是因为它不重要**。恰恰相反，Web Service 是 OryxOS 区别于个人助手项目的关键能力，重要性很高。本文档不用 P1/P2/P3 这种优先级标记，避免被误读成"靠后的可以不做"，只讲依赖顺序。

每个 user story 的 acceptance criteria 直接复用需求文档第 13 章 5 个验收 Demo：

| User Story | 对应 Demo | 场景 |
|-----------|---------|------|
| US-1 + US-2 | Demo 一 | 查天气穿衣 |
| US-3 | Demo 二 | 跨对话记偏好 |
| US-4 | Demo 三 | 零代码 PR digest |
| US-5 | Demo 四 + Demo 五 | Web Service 同步调用 + 多端点联动 |

`/speckit.specify` 执行后生成 `spec.md`，AI agent 据此理解 OryxOS 整体要做什么。跑完后建议跑一次 `/speckit.clarify`，AI agent 会问几个澄清问题（比如 max iterations 默认值、对话历史截断策略等），这一步可选但推荐。

---

### 3.4 `/speckit.plan`：把技术方案转成实施 plan

`/speckit.plan` 命令的输入是技术方案 + 上一步的 `spec.md` + `constitution.md`，输出是实施 plan。Plan 包含：

- 技术栈选型（JDK 21 + Spring Boot 3.x + Spring AI Alibaba + SQLite + Picocli）
- 9 个 Maven 模块的职责（对照技术方案第 10 章）
- 关键技术决策的展开（自实现 ReAct、Spring AI 只用一半的边界、Plugin Tool 三档、SQLite + MEMORY.md、审计 day one 落库）
- 数据流和模块间协作（`PromptBuilder` + `ProviderService` + `ToolExecutor` + `MemoryService` 三层门面）

**Plan 生成后人工 review 是必要环节**。AI agent 可能根据自己对技术方案的理解做了不该做的取舍，重点检查：

- [ ] 有没有把 Memory 简化成跟 Session 合并（应该是 `MemoryService` 三层统一门面）
- [ ] 有没有把 Tool 又拆成多个模块（应该是合并的 `oryxos-tool` 一个模块）
- [ ] 有没有把 `AgentLoader`/`AGENT.md` 当成 Tool（Agent 目录应该归 core 的 `ContextLoader`，正文注入 prompt）
- [ ] 有没有启用 Spring AI 的自动 tool 执行（必须禁用）

Review 通过后 `plan.md` 锁定。

---

### 3.5 准备阶段交付物清单

准备阶段结束时，OryxOS 项目仓库里应该有：

```
.specify/
└── memory/
    └── constitution.md       # 非协商原则集
spec.md                       # 5 个 user story
plan.md                       # 技术栈 + 9 个模块 + 技术决策
docs/
├── DemandAnalysis.md         # 需求文档（来源参考）
├── TechnicalSolution.md      # 技术方案（来源参考）
├── IndustryResearch.md       # 业界调研（来源参考）
└── AiProgrammingGuide.md     # 本文档
```

准备阶段完成后，5 个 user story 的实施依据全部就绪，可以按依赖关系顺序推进。

---

## 4. 基于 Spec-Kit 的实施拆解

准备阶段把整体 spec 和 plan 都准备好了，下面按 5 个 user story 拆解具体实施。每个 user story 的拆解结构一致：核心目标、涉及的 Maven 模块、Spec-Kit 任务拆分思路、关键 task 颗粒度、验收 Demo。模块名以技术方案第 10 章的 9 模块为准。

---

### 4.1 US-1：对接 LLM（核心能力一）

**核心目标**：让 OryxOS 能调任意主流 LLM，Agent 不感知具体调的是哪家。LLM 调用的复杂度都被 Spring AI Alibaba 吸收，OryxOS 只在它之上做一层薄包装。

**涉及的 Maven 模块**：
- `oryxos-core`（`OryxTool` 接口、`Session`、`Profile`、`ContextLoader` 等核心抽象）
- `oryxos-provider`（核心能力一）
- `oryxos-boot`（Spring Boot 启动模块）

**Spec-Kit 任务拆分思路**：`/speckit.tasks` 针对 US-1 拆任务，按依赖关系排序，标记可并行任务。预期产出的 task 大类：

| Task 类别 | 主要内容 |
|----------|---------|
| 环境搭建类 | Maven 多模块骨架 9 个模块、Spring Boot 启动配置、Spring AI Alibaba 依赖 |
| 核心抽象类 | `OryxTool` 接口、`Profile` 数据结构、`Message` 数据结构 |
| Provider 实现类 | `ProviderService` 实现、provider name 到 `ChatModel` 的显式映射、Function Calling 适配 |
| 配置类 | `application.yaml` 配置至少跑通 DeepSeek 或 Kimi，配合 `ConfigLoader` 从环境变量加载 API key |

> **关键注意**：`ProviderService` 不能靠"扫描容器里所有 `ChatModel`"来区分 Provider，多 Provider 并存时 Bean 类型相同会有歧义，必须维护 provider name 到 `ChatModel` 的显式映射（技术方案 3.2）。AI agent 很容易写成类型扫描，要在 task 里点明。

US-1 实施完成后不立刻有 demo，因为它没有用户可见的入口，下一步 US-2 完成后跟 US-1 一起跑 Demo 一。

---

### 4.2 US-2：ReAct 循环（核心能力二）

**核心目标**：实现 Agent 的核心工作机制。即：LLM 思考是否调用工具，调用之后看结果，再决定下一步，直到给出最终响应。ReAct 循环是 OryxOS 最关键的一段代码。

**涉及的 Maven 模块**：
- `oryxos-core`（`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`ContextLoader`）
- `oryxos-tool`（一个 HTTP Tool + `SandboxChecker` 简化版，Demo 一需要）
- `oryxos-channel-cli`（CLI Channel，Demo 一需要）
- `oryxos-cli`（`oryxos init` + `oryxos chat` 命令）

> 注意这里 Tool 相关只有一个 `oryxos-tool` 模块（技术方案已把 builtin/skill/mcp 合并），不再是旧版的多个 tool 模块。

**Spec-Kit 任务拆分思路**：预期产出的 task 大类：

| Task 类别 | 主要内容 |
|----------|---------|
| ReAct 循环类 | `ReActLoop` 主循环、`PromptBuilder`、`ToolExecutor`、`MAX_ITERATIONS` 控制 |
| CLI Channel 类 | `CliChannel`、`oryxos chat` 命令、`oryxos init` 工作区初始化 |
| 基础 Tool 类 | HTTP Tool、`SandboxChecker` 简化版（只校验 URL 白名单） |
| `AGENT.md` frontmatter 解析类 | SnakeYAML、派生 Profile 校验 |
| Session 类 | `Session` 数据结构、`SessionManager` 内存版（持久化放 US-5） |

**关键 task 颗粒度**：US-2 是 Spec-Kit 拆分的重点。几个需要拆细的复杂 task：
- `ReActLoop` 主循环（核心循环逻辑精简约数十行 Java，但工程化部分如错误处理、日志、消息累积、迭代次数控制建议拆 2~3 个子 task）
- `PromptBuilder` 组装（四部分内容即 system prompt + Bootstrap + Memory + 对话历史 + Tool 列表，建议拆成几个子 task 逐步加入）

> **再次强调 constitution 原则四**：调用 Spring AI 时只用它的协议转换和 schema 生成，**禁用它的自动 tool 执行**，tool 的实际调度由 `ToolExecutor` 控制。AI agent 实现 `ReActLoop` 时很容易顺手启用 Spring AI 的自动执行，导致 tool 被调两次，task 里要明确禁用。

US-1 + US-2 完成后跑 `/speckit.analyze` 检查 spec 跟代码一致性。

**验收 Demo 一**：查天气穿衣

`oryxos chat` 启动 CLI，用户输入"查一下北京天气并告诉我穿什么"，Agent 通过 ReAct 循环调用 HTTP Tool 拉天气 JSON，根据数据回复穿衣建议，完整对话日志正确累积到 Session，至少跑通一个 Provider（DeepSeek 或 Kimi）。

---

### 4.3 US-3：Memory 三层记忆（核心能力三）

**核心目标**：让 Agent 跨对话保留状态。核心阶段做极简版的两层（会话和长期），用一份 `MEMORY.md` 文件加两个内置 Tool 实现，让 Agent 主动写入和读取。

**涉及的 Maven 模块**：
- `oryxos-memory`（核心能力三，含 `MemoryService` 三层门面、`LongTermMemory`、`MemoryTools`）

**Spec-Kit 任务拆分思路**：US-3 相对独立，依赖 US-2 但不影响 US-4。预期产出的 task 大类：

| Task 类别 | 主要内容 |
|----------|---------|
| `MemoryService` 门面类 | 三层统一门面，内部把会话记忆委托给 `SessionManager`、长期记忆委托给 `LongTermMemory` |
| `LongTermMemory` 类 | `append`、`load`、`recallByKeyword`、`truncateIfNeeded` 四个方法，接口预留 `recall(mode)` 向量检索升级空间 |
| `MemoryTools` 类 | `save_memory` + `recall_memory` 两个内置 Tool，用 `@Tool` 注解 |
| `PromptBuilder` 集成类 | 在 `PromptBuilder` 里通过 `MemoryService` 注入记忆，确保不破坏 US-2 跑通的 ReAct 循环 |
| `MEMORY.md` 文件管理类 | 文件位置、格式约定、超长截断策略 |

US-3 实施完成后跑 `/speckit.analyze`。

**验收 Demo 二**：跨对话记偏好

第一次对话告诉 Agent"我项目用 Spring Boot，部署在 K8s 上"，Agent 主动调 `save_memory` 追加到 `MEMORY.md`；重启 OryxOS 或新开会话；第二次对话问"帮我看看我的项目能用什么数据库"，Agent 在响应里引用之前记的偏好给出建议。

---

### 4.4 US-4：Plugin Tool 体系（核心能力四）

**核心目标**：让业务方扩展 OryxOS 的能力，并让一个 Agent 通过私有 Skill 渐进加载任务知识。Plugin Tool 三档接入：
1. 零代码 Agent 私有 SKILL.md + 已显式配置的内置 Tool / MCP（主推）
2. 轻代码自写 MCP server
3. 重代码 Java `@Tool` 注解

核心阶段做完三档基础设施 + 内置 Tool 补齐。

**涉及的 Maven 模块**：
- `oryxos-tool`（补齐文件 Tool + Shell Tool、MCP Client、`SandboxChecker` 完整版、`ToolRegistry`，三合一模块）
- `oryxos-core`（Skill catalog/snapshot、渐进披露、生命周期管理与 `ContextLoader`；不在 tool 模块）
- `oryxos-web`（既有 Agent 的 Skill 列表/详情、ZIP 导入、启停、删除和管理台页签）

**Spec-Kit 任务拆分思路**：US-4 跟 US-3 可以并行（都依赖 US-2 但互不依赖）。预期产出的 task 大类：

| Task 类别 | 主要内容 |
|----------|---------|
| 内置 Tool 补齐类 | `read_file`、`write_file`、`list_dir`，Shell Tool 带白名单，`SandboxChecker` 完整实现 |
| MCP Client 类 | `mcp_servers.yaml` 解析、`McpClientService` 启动时连接、`tools/list` 拉工具、`McpToolAdapter` 包装成 `OryxTool` |
| `AGENT.md` / Skill 上下文类 | `ContextLoader` 全量加载 `AGENT.md` 正文，只把已启用私有 Skill 的 L1 元数据拼入 system prompt；这部分归 core 不归 tool |
| Skill 渐进披露类 | 顶层请求冻结一次 `SkillSnapshot`；命中后用既有 `read_file` 读取 L2 `SKILL.md`，再按需读取/运行 L3 |
| Skill 管理类 | 安全 ZIP 导入、三态扫描、启停 marker、归档式删除、REST/管理台与 Agent 级读写租约 |
| Agent 定义类 | `AgentLoader.deriveProfile` 从 `AGENT.md` frontmatter 派生 `Profile`（含 `tools` / `mcp_servers` 等字段） |

**关键 task 颗粒度**：US-4 的 task 数量较多，几个需要重点拆解的复杂 task：

- **MCP Client 集成**（MCP 协议是 JSON-RPC over stdio 或 SSE，Java 生态成熟度不如 Python）：
  - 建议先实现 stdio transport（最常用），SSE 放扩展
  - stdio MCP Client 建议拆几个子 task：连接管理、`tools/list`、`tool/call`、错误恢复
- **`SandboxChecker` 完整版**（从 US-2 的简化版扩展到完整版：文件路径白名单 + Shell 命令白名单 + HTTP 域名白名单，建议拆 3 个子 task）
- **Skill 包与并发边界**：
  - 标准受管路径只认 `.oryxos/agents/<agent>/skills/<skill>/SKILL.md`；旧 `skills/*.md` 保持 legacy/unmanaged
  - L1 只含 name/description/entry，严禁为建目录全文读取正文；同一请求持有读租约并复用 snapshot
  - 导入执行“同盘 staging → 完整校验 → 原子发布”，启停/删除拿同一 Agent 写租约，从下一次请求生效
  - 不新增 `use_skill`、全局索引或 Tool 执行旁路；`allowed-tools` 只展示，不修改 `AGENT.md` 的显式 Tool 列表

#### Agent 私有 Skill 标准脚手架

```text
.oryxos/agents/daily-pr-digest/
├── AGENT.md
└── skills/
    └── pr-digest/
        ├── SKILL.md
        ├── references/
        │   └── review-policy.md
        ├── scripts/
        └── assets/
```

```markdown
---
name: pr-digest
description: 汇总并分级待处理 Pull Request；生成团队 PR 日报时使用。
license: Apache-2.0
compatibility: Requires read_file and a configured GitHub MCP server.
metadata:
  author: platform-team
allowed-tools: read_file
---

# PR Digest

需要评审分级规则时读取 `references/review-policy.md`，再调用 Agent 已配置的 GitHub MCP 工具。
```

`name` 必须与目录名一致，`description` 必须说明“做什么、何时用”。加载语义固定为：L1 只注入名称/描述/入口；模型命中后用 `read_file` 加载 L2 `SKILL.md`；只有 L2 需要时才读取 reference/assets 或用 `shell` 跑 L3 script。Profile 没有显式授予 `read_file`/`shell` 时不得自动补权。

运行时状态为 `enabled`、`disabled`、`invalid`。合法本地 ZIP 导入默认 enabled；禁用通过包内 `.oryxos-disabled` 跨重启持久化，启用前重新校验；删除把完整包归档到 `.oryxos/archive/.skills/` 而非物理擦除，当前不提供恢复 API。禁用/删除从下一次顶层请求生效，不改写既有 Session 或 Tool/LLM 审计。旧 `skills/*.md` 不迁移、不进入 L1、不由管理 API 禁用或删除。

安全审查必须把 Skill 当成代码：ZIP Slip、链接、大小和 YAML 校验只能证明包的结构安全，不能证明指令、references 或 scripts 善意。导入是管理员的显式信任动作；所有实际 Tool 仍必须经过 `ToolExecutor`、沙箱和审计。disabled 表示退出 L1/正常渐进链路，不是通用 `shell` 的逐路径文件 ACL。

US-4 实施完成后跑 `/speckit.analyze`。

**验收 Demo 三**：零代码 PR digest

业务方写一个 Agent 目录 `.oryxos/agents/daily-pr-digest/`（`AGENT.md` 配 Profile，`skills/pr-digest/SKILL.md` 描述何时及如何生成日报），在 `mcp_servers.yaml` 配置 `github-mcp`。首个 prompt 只有该 Skill 的 L1，命中后才读取 L2/L3，再调用已授权的 `github-mcp` 拉 PR、汇总成简报；整个过程零 Java。

---

### 4.5 US-5：Web Service（核心能力五）

**核心目标**：把 OryxOS 的所有能力通过 REST API 对外暴露，业务系统通过 HTTP 接入。这是 OryxOS 区别于个人助手项目的关键能力。

**涉及的 Maven 模块**：
- `oryxos-web`（核心能力五）
- `oryxos-storage`（SQLite 持久化层，Session 持久化从内存版升级，并落 `tool_invocations` 和 `llm_calls` 审计表）
- `oryxos-cli`（Picocli 12 个命令补全）
- `oryxos-core`（`ConfigLoader`、`ContextLoader` 的 Bootstrap 加载补全）

**Spec-Kit 任务拆分思路**：US-5 依赖前 4 个 user story 都完成，是最后实施的 user story，Spec-Kit 拆解的任务密度最高。预期产出的 task 大类：

| Task 类别 | 主要内容 |
|----------|---------|
| Web Service 基础类 | `WebServer` 启动 + virtual thread 配置、`GlobalExceptionHandler`、OpenAPI 文档 |
| 6 个 ApiController 类 | Session + Agent + Profile + Memory + Tool + System，每个 Controller 一组端点，**可并行实现** |
| 核心 10 个 REST 端点 | 会话管理 4 个、Agent 调用 1 个、Profile/Memory/Tool 列表 3 个、health/info 2 个 |
| 持久化升级类 | Session 从内存版升级到 SQLite，`SessionRepository`，跨重启恢复，以及 **`tool_invocations` 和 `llm_calls` 审计表的写入** |
| 配置与上下文类 | `ConfigLoader` 配置密钥加载，`ContextLoader` 的 Bootstrap 文件加载补全并跟 `PromptBuilder` 集成 |
| CLI 完整版 | Picocli 12 个命令全部实现 |
| 工程化类 | Logback + SLF4J 结构化日志 + 错误处理 |

> **注意审计表的写入**（constitution 原则六）：`tool_invocations` 和 `llm_calls` 核心阶段就落库，不是只放日志，这样可审计的数据地基 day one 就立起来。这一点 AI agent 容易漏掉（觉得日志够了），task 里要明确。

**关键 task 颗粒度**：US-5 工程量最大。
- 6 个 `ApiController` 可以并行实现（互不依赖），每个 Controller 1~2 个端点
- Session SQLite 升级主要是 `SessionRepository` + `messages_json` 序列化，要小心 Session 状态的迁移
- Bootstrap 加载（`ContextLoader`）跟 `PromptBuilder` 集成时确保不破坏之前跑通的 ReAct 循环

US-5 完成后跑最后一次 `/speckit.analyze`，整个主体开发完成。

**验收 Demo 四**：Web Service 同步调用

外部系统 `POST /api/v1/sessions` 创建 Session，`POST /api/v1/sessions/{id}/messages` 发消息，`GET` 查历史，`DELETE` 归档，完整链路跑通。

**验收 Demo 五**：Web Service 多端点联动

外部系统调 `GET /info` 查健康 + Provider 列表、`GET /profiles` 列可用 Agent、`GET /tools` 查可用 Tool、`POST /agents/{name}/invoke` 无状态调用 Agent、`GET /memory` 查长期记忆，5 个不同端点协同完成一次业务流程。

---

### 4.6 实施过程中的协作模式

5 个 user story 的实施过程中有几个跨 user story 的协作要点：

**`/speckit.analyze` 每个 user story 结束后必跑**

检查 constitution + spec + plan + tasks + 代码是否一致，发现漂移立刻修正，这是 Spec-Kit 防漂移的核心命令，**不能省**。

**AI agent 跑偏 constitution 时主动纠正**

看到 Claude Code 生成的代码不符合 constitution，主动让 AI agent 重读 constitution 改正。OryxOS 最容易被写错的几个点：

| 问题 | 正确做法 |
|------|---------|
| 用了非 JDK 21 特性 | 强制要求 JDK 21 |
| 改了 ReAct 实现方式（依赖 Spring AI 自动执行） | 自实现，tool 被调两次时立刻查这里 |
| 启用了 Spring AI 自动 tool 执行 | 必须禁用，见 constitution 原则四 |
| 把 Tool 又拆成多模块 | 应该合并为一个 `oryxos-tool` 模块 |
| Provider 用类型扫描 | 必须用显式 provider name 映射 |
| `AgentLoader`/`AGENT.md` 当成 Tool | Agent 目录应该归 `ContextLoader`，在 core 模块里 |
| 审计表没落库 | `tool_invocations` 和 `llm_calls` day one 写入 |

**跨 task 上下文丢失时回到 spec**

Spec-Kit 把代码拆成多个 task 后，AI agent 实施每个 task 时可能不知道前面任务做了什么，定期让它读 `spec.md` + `plan.md` + 最近的代码。

**git commit 标记每个 user story 完成**

方便随时回退到稳定状态。

---

## 5. 项目交付物

主体开发完成后 OryxOS 1.0 是一个可演示的最小完整 Agent OS 运行时内核，五大核心能力全部跑通。除了核心代码本身，还有几个交付物：

### 项目主页

OryxOS 作为开源项目需要一个独立的主页作为对外门面，技术栈用 VitePress 或类似静态站点工具，内容讲清楚 OryxOS 是什么、五大核心能力是什么、怎么快速开始。

### Spec-Kit Artifacts 保留

`.specify/` 目录下的 constitution、spec、plan 在主体开发结束后仍然保留在仓库里，作为社区接力的长期参考。

### 社区文档

API 参考文档、部署运维手册、贡献者指南这些剩余文档作为社区共建项目，由社区贡献者通过 PR 完成。

---

## 6. 增量阶段：手动提示词模式

### 6.1 为什么从 Spec-Kit 切换到手动提示词

主体开发完成后 OryxOS 进入增量阶段。这个阶段的工作性质跟主体开发完全不同：

| 维度 | 主体开发 | 增量开发 |
|------|---------|---------|
| 任务颗粒度 | 大（9 个模块同时建） | 小（加一个 Channel、补一个 Bug） |
| 涉及文件数 | 多（跨模块） | 少（通常 1~3 个） |
| 跨模块协作 | 有 | 通常无 |
| 上下文 | 从零设计 | 已有代码 |

这种工作性质下 Spec-Kit 流程过重，跑一次完整的 constitution + specify + plan + tasks + implement 流程，开销大于单次任务的工作量本身。手动提示词配合 Claude Code 更适合：直接打开 Claude Code 描述要做的事，Claude Code 在已有代码上下文里直接修改，改完跑测试没问题就提 PR，不需要正式的 spec 和 plan artifacts。

### 6.2 增量开发的工作流

增量阶段的典型工作流：

```
1. 社区贡献者认领一个 issue（主仓库标注 good-first-issue / feature-request / long-term-goal）
2. 本地 fork + clone OryxOS
3. 用 Claude Code 打开项目，跟 Claude 描述要做的改动
4. Claude 在已有代码基础上修改、加测试、跑通
5. 提 PR 到主仓库
6. 项目方 review + merge
```

这个流程不强制走 Spec-Kit，每个贡献者按自己习惯做就行。对要求严格的大 feature 可以选择走 Spec-Kit，但不强制。

### 6.3 跟主体阶段 Spec-Kit Artifacts 的对接

主体阶段产出的 `constitution.md` 和 `spec.md` 在增量阶段仍作为参考文档保留在仓库里：

- **`constitution.md` 仍然是非协商原则**：社区贡献的代码必须遵守（JDK 21 + Spring Boot、自实现 ReAct、Spring AI 只用一半、Plugin Tool 三档等）
- **`spec.md` 是核心能力的契约**：社区贡献者改某个核心能力时要保证不破坏 spec 里的 acceptance criteria
- **`plan.md` 在主体阶段后基本不再更新**：技术方案文档作为社区参考保留

**新加 user story 的处理方式：**
- 小 feature：直接手动提示词 + PR
- 大 feature（涉及新增 Maven 模块、改 constitution、跨多个核心能力）：由项目方决定是否单独跑一次 Spec-Kit specify → plan → tasks 流程

---

## 7. 风险和注意事项

### 7.1 Spec-Kit 当前局限

Spec-Kit 还在快速迭代，工具本身变化频繁，使用时几个注意点：

| 注意点 | 说明 |
|--------|------|
| **版本锁定** | 实施前锁定 Specify CLI 一个具体版本号，整个主体开发期间不升级，命令名、artifacts 格式、集成方式可能在版本之间变化 |
| **官方文档随时查** | 本文档讲思路 + 节奏，具体命令和安装方式以实施时官方文档为准 |
| **community extension 谨慎用** | Spec-Kit 有 70 多个社区扩展，主体开发期间只用官方核心命令，不引入 extension 增加不确定性 |

### 7.2 实施过程中的常见挑战

| 挑战 | 对策 |
|------|------|
| **AI agent 跑偏 constitution** | 每次跑完 implement 后人工检查，发现偏离立刻让 AI agent 重读 constitution 修正 |
| **跨 user story 的上下文断裂** | 每个 user story 开始前让 AI agent 重读 `spec.md` + `plan.md` + 最近代码 |
| **`/speckit.analyze` 被跳过** | 把 analyze 作为每个 user story 结束的硬性环节，不能省 |
| **MCP server 集成踩坑** | US-4 实施 MCP 前先用一个最简的 MCP server 测试连通性（stdio transport 可能遇到 process 启动失败、编码问题） |
| **把 legacy 平铺文件误当 Skill** | 受管扫描只认 `skills/<name>/SKILL.md`；`skills/*.md` 必须保持 unmanaged，并加兼容回归 |
| **把结构校验误当内容可信** | UI/文档明确“Skill 等同代码”；导入前审查全文和脚本，`allowed-tools` 永不扩权 |
| **启停让同一 ReAct 前后不一致** | 顶层请求冻结 snapshot 并持读租约；管理最终切换持同一 Agent 写租约 |
| **Java 工程基础是前提** | 实施前确保团队成员对 Spring Boot + Maven + JPA 有基本掌握 |

---

## 8. 总结

OryxOS 的 AI 编程实施分两个阶段：

### 主体开发阶段（Spec-Kit）

已有的需求文档 + 技术方案喂给 Spec-Kit，转成 constitution + spec + plan + tasks 等 artifacts。准备阶段一次性把 constitution、spec、plan 准备好，然后按 5 个 user story 的依赖关系顺序实施：

```
US-1 → US-2 → ┌─ US-3 ─┐ → US-5
               └─ US-4 ─┘
```

每个 user story 完成后有可演示 Demo，对应需求文档第 13 章的 5 个验收 Demo。

### 增量阶段（手动提示词 + Claude Code）

小颗粒度增量不适合 Spec-Kit 完整流程，社区贡献者用 Claude Code 直接在已有代码上做改动，主体阶段产出的 constitution + spec 作为长期参考保留。

---

**Spec-Kit 跟 OryxOS 的契合度很高**：纯 greenfield、medium 规模（9 个模块）、需求清晰、AI agent 协作、方法论场景，每条都对得上。

**核心策略是已有文档喂给 Spec-Kit，不重写**。OryxOS 已经投入了完整的业界调研 + 需求文档 + 技术方案，这些是 Spec-Kit 的最佳输入。**关键是喂的是最新版文档**：模块是 9 个不是 11 个，constitution 要包含"Spring AI 只用一半"、"审计 day one 落库"这些新决策，否则 Spec-Kit 生成的 plan 会按旧结构走偏。
