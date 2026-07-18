# Research: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

所有 Technical Context 项均无 NEEDS CLARIFICATION（技术栈固定、终态模型已与用户确认）。本文件记录本节实现前必须定死的设计决策。

## D1：AGENT.md 正文如何进 system prompt —— ContextLoader 现读

- **Decision**：`ContextLoader.load(profile)` 每次从 `oryxosRoot/agents/<profile.name()>/AGENT.md` **现读**文件、用 `AgentMarkdown` 拆掉 frontmatter、把**正文**追加进上下文。`ContextLoader` 构造签名保持 `(Path oryxosRoot)` 不变；保留既有 `identity.prompt`（人格，来自 frontmatter、随 Profile 携带）与 `bootstrap` 注入；**移除** `profile.skills()` 循环。
- **Rationale**：FR-003 / SC-003 要求"改 Agent 目录里的正文不重启、下一次触发即用新说明"。正文只有**每次现读**才能即时生效——若在 `deriveProfile`（扫描/注册时一次性）把正文固化进 `Profile`，编辑正文必须重扫才生效，直接违反该验收点。`ContextLoader` 本就有"每次重读、无缓存"的铁律（17 节），把正文归它天然正确。定位用 `oryxosRoot + agents/ + name`（名 = 目录名，FR-001），无需给 `Profile` 加目录字段。
- **Alternatives considered**：
  - *正文固化进 Profile（deriveProfile 时）*：live-edit 失效，否决。
  - *给 ContextLoader 注入 SkillRegistry / AgentRegistry*：改构造签名会波及 `MockProviderFlowTest` 等既有接线（方案2 曾因此翻车），且引入不必要依赖，否决。
  - *给 Profile 增 `agentDir` 字段*：又一处公共类型改动；用 `name→dir` 约定即可定位，无需加字段，否决。

## D2：deriveProfile 与启动加载"同一异常同一消息" —— 复用 ProfileLoader 校验入口

- **Decision**：从 `ProfileLoader.parse(Path)` 抽出纯 `Map<String,Object> → Profile`（含全部字段校验、抛 `ProfileValidationException`）的可复用入口（如包级 `ProfileLoader.fromMap(Map, String source)`）。`AgentLoader.deriveProfile(agentDir)` 用 `AgentMarkdown` 拆出 frontmatter Map 后调用它。
- **Rationale**：FR-006 关键回归"运行时路径与启动路径报错完全一致（同一异常类型 + 同一消息）"。两条路径共用**同一段校验代码**是最可靠的实现，也让 `ProfileRegistryRuntimeTest` 的断言直接对号。
- **Alternatives considered**：*AgentLoader 自写一套校验* —— 必然与 ProfileLoader 消息漂移，破坏关键回归，否决。

## D3：Profile.skills 字段去留 —— 移除（软门禁项）

- **Decision（推荐）**：**物理移除** `Profile` record 的 `skills` 字段（canonical 12 参 → 11 参），使数据模型与终态模型（无 skills 概念）一致。
- **影响面（已量化）**：`new Profile(` 12 处（11 个测试 + `ProfileLoader`）、`ProfileLoader` 停解析 `skills`、`ContextLoader` 去 `profile.skills()` 消费、`ProfileApiController` 视图去 `p.skills()`。
- **Rationale**：终态无 skills；留死字段会诱导后续误用、与课件/TechSol §8.2 已改的字段表不一致。移除机制在上一轮（已回滚的方案2）验证可行、影响面清晰。
- **软门禁**：这是"修改前序节交付的公共类型、且课件未逐字列为改造点"——按 lesson-dev 软门禁，`/speckit-tasks` 停点向用户确认后才落。**备选**：保留为恒空字段（改动更小：仅 `ContextLoader` 去消费、`ProfileLoader` 停填、其余不动），但留一个永远空的死字段。二者择一，tasks 前定死。

## D4：定时来自 Agent —— AgentScheduler.registerProfile + 句柄表

- **Decision**：`AgentScheduler` 把 `registerAll()` 的循环体抽成 `registerProfile(Profile)`；新增 `Map<String, ScheduledFuture<?>> scheduledTasks` 句柄表，与既有 `taskLocks`/`taskStore` 并存；`registerAll()` 改为遍历 `ProfileRegistry` 调 `registerProfile`，保留每条 `try/catch` 跳过非法 cron。cron/时区从 `Profile.schedules` 取（派生自 frontmatter）。
- **Rationale**：`schedules` 属于 Agent（写在 frontmatter、派生进 Profile）；抽出单条注册 + 留句柄，为 30 节运行时注销/更新铺路（FR-007），逻辑与既有 `registerAll` 等价，不破 25 节测试。
- **Alternatives considered**：*另起一套定时来源* —— 违反"定时来自 Agent"、重复造轮，否决。

## D5：脚本沙箱与信任边界 —— 复用 24 节 SandboxChecker

- **Decision**：Agent 目录里的脚本经底座既有 `shell`/`python` 跑；沙箱复用 24 节 `SandboxChecker` 白名单：`shell.allowed_commands` 放行解释器（`python`/`bash`），`file.allowed_paths` 限定到 `.oryxos/agents/<name>/scripts/`。明确信任边界：脚本是任意代码、能自发网络请求绕过 `http_get` 域名白名单——"装带脚本的 Agent = 信任其作者"。
- **本节范围界定**：脚本真执行的工作目录/相对路径联调，属 31 节 Demo。本节 harness（`ProgressiveDisclosureTest`）只钉"正文进 prompt、子指令/参考/脚本**不预载**、靠 `read_file`/`shell` 按需取"这一机制，**不要求脚本在 29 节真跑**。
- **Rationale**：不新造沙箱机制（宪法 VI），把信任边界讲清是做 Agent OS 的诚实底线。

## D6：.oryxos/profiles/ 取消 —— OryxOsRuntime 改扫 agents

- **Decision**：`OryxOsRuntime` 启动扫 `.oryxos/agents/`：逐目录 `AgentLoader` → `deriveProfile` → `ProfileRegistry.register`，有 `schedules` 交 `AgentScheduler.registerProfile`；替换原先"扫 `.oryxos/profiles/` YAML"接线。`ProfileLoader.loadAll`（扫 profiles 目录）退出接线，其 `fromMap` 校验入口保留供 `AgentLoader` 复用。`InitCommand` 建的目录 `profiles`→`agents`；`StatusCommand`/`ProfileCommand` 的 `profiles` 路径同步；`my-agent/.oryxos/profiles/` 与 `oryxos-web` 测试对 `.oryxos/profiles` 的引用一并迁 `agents`。
- **Rationale**：终态唯一来源是 Agent 目录（FR-001/FR-002）。
- **Alternatives considered**：*profiles 与 agents 并存双来源* —— 违反"一个目录 = 一个 Agent"的单一来源、增歧义，否决。

## 依赖核实

- 无新增第三方依赖：`AgentMarkdown` 用既有 SnakeYAML（`org.yaml.snakeyaml.Yaml`，`ProfileLoader` 已在用）拆 frontmatter；其余全是 JDK 标准库（`Files`/`Path`）。动手前 `mvn dependency:tree` 确认 SnakeYAML 在锁定 BOM 里存在（`ProfileLoader` 已依赖，实为存在性复核）。
- 无新增数据表、无表结构变更（本节不碰持久化 schema）。
