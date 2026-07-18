# Data Model: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

本节不新增数据表；下面是内存值对象与文件形态。

## 实体

### Agent 目录（文件形态，真相源）

`.oryxos/agents/<name>/` —— 一个目录 = 一个 Agent。目录名 = Agent 名（唯一）。

| 成员 | 必选 | 说明 |
|---|---|---|
| `AGENT.md` | 是 | frontmatter（YAML，= 这个 Agent 的 profile）+ 正文（任务指令） |
| `skills/*.md` | 否 | 子指令，Agent 用 `read_file` 按需读 |
| `scripts/*` | 否 | 脚本，Agent 用 `shell`/`python` 按需跑（产出进上下文、代码不进） |
| `REFERENCE.md` | 否 | 参考资料，Agent 用 `read_file` 按需读 |

`AGENT.md` frontmatter 字段（与派生 `Profile` 一一对应）：`name`、`description`、`identity{agent_name, prompt}`、`provider{name, model, temperature?}`、`tools[]`、`mcp_servers[]`、`channels[]`、`notify_channels[{type, ...config}]`、`schedules[{id?, cron, zone, message}]`、`bootstrap[]`、`settings{max_iterations, max_history_turns}`。**无 `skills` 字段**（终态无该概念）。

### Profile（派生值对象，底座通货）—— 改造点

`io.oryxos.core.profile.Profile`（record，不可变，防御性拷贝）。

- **改造**：移除 `List<String> skills`（第 6 位置字段），canonical **12 参 → 11 参**。
- 移除后字段顺序：`name, description, identity, provider, tools, mcpServers, channels, notifyChannels, schedules, bootstrap, settings`。
- 嵌套 record 不变：`Identity(agentName, prompt)`、`ProviderRef(name, model, temperature)`、`NotifyChannel(type, config)`、`ScheduleConfig(id, cron, zone, message)`、`Settings(maxIterations, maxHistoryTurns)`。
- **派生来源**：`AgentLoader.deriveProfile(agentDir)` 拆 `AGENT.md` frontmatter → Map → 校验 → `Profile`。正文不进 `Profile`（由 `ContextLoader` 现读，见 D1）。

**连带修改点（移除 skills 的影响面）**：
- `ProfileLoader`：停解析/校验 `skills`；`new Profile(...)` 少一参。
- `ContextLoader`：删 `for (String skill : profile.skills())` 循环。
- `ProfileApiController`：视图 DTO 去 `p.skills()`。
- 11 个测试的 `new Profile(...)` 构造点：`ContextLoaderTest`、`PromptBuilderTest`、`ReActLoopTest`、`AgentSchedulerTest`、`AgentServiceTest`、`MockProviderFlowTest`、`ProviderServiceTest`、`ProviderSmokeIT`、`WebSmokeIT`、`AgentApiControllerTest`、`NotifyToolsTest`。

### AgentLoader（新增，oryxos-core/agent）

扫 `.oryxos/agents/`，把每个目录解析成"可注册的 Agent"。

- 拆 `AGENT.md` → (frontmatter Map, body) via `AgentMarkdown`；记住 `scripts/`/`skills/`/`REFERENCE.md` 资源路径。
- `deriveProfile(Path agentDir) → Profile`：frontmatter Map 走 `ProfileLoader.fromMap`（同一异常同一消息，D2）。
- 校验：缺 `name`/`provider` → `ProfileValidationException` 点名（与启动一致）；`tools` 引用未注册能力 → WARN；坏 Agent 记错误、跳过、**不阻断启动或其余 Agent**（FR-009）。

### AgentMarkdown（新增，小工具）

`split(String content) → (Map<String,Object> frontmatter, String body)`：识别 `---\n…\n---\n` frontmatter 块（SnakeYAML 解析）+ 其后正文。AgentLoader（取 frontmatter）与 ContextLoader（取 body）共用。

### ProfileRegistry（改造点，oryxos-core/profile）

- **改造**：不可变 `Map.copyOf` → 可变并发 `ConcurrentHashMap`。
- 新增：`register(Profile)`、`remove(String)`、`exists(String)`；保留 `get`、`all`。
- 运行时 `register` 与启动登记走**同一套校验**（D2）——非法配置同一异常同一消息（FR-006）。
- 状态转换：`(空) → register → 可 get/all → remove → 不可见`。

### AgentScheduler（改造点，oryxos-core/agent）

- **改造**：`registerAll()` 循环体抽成 `registerProfile(Profile)`；`registerAll()` 遍历 `ProfileRegistry` 调它。
- 新增：`Map<String, ScheduledFuture<?>> scheduledTasks` 句柄表（与既有 `taskLocks`/`taskStore` 并存），登记带 `schedules` 的 Agent 时留可注销句柄（FR-007，为 30 节铺路）。
- cron/zone 来自 `Profile.schedules`（派生自 frontmatter）——定时来自 Agent（FR-004）。
- 保留每条 `try/catch` 跳过非法 cron（不破 25 节行为）。

### ContextLoader（改造点，oryxos-core/context）

- ctor 不变 `(Path oryxosRoot)`。
- `load(Profile)`：注入 `identity.prompt`（人格，保留）→ **现读 `oryxosRoot/agents/<name>/AGENT.md` 正文注入**（新）→ `bootstrap` 文件（保留）；**删** `skills` 循环。
- 铁律保留：每次现读、无缓存（改正文即时生效，FR-003）；Bootstrap 缺失 WARN。

## 校验规则（来自 spec FR）

- FR-002：扫 N 个合法目录 → 注册表恰好 N 个 Agent，不产生别的。
- FR-006：运行时/启动同一异常同一消息。
- FR-009：缺必填点名报错、引用未注册能力 WARN、坏 Agent 不阻断启动。
