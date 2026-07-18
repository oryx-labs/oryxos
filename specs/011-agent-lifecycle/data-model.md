# Data Model: 动态管理 Agent

本节不新增数据表。下面是新增/改造的类型与 DTO。

## 编排与存储（oryxos-core）

### AgentLifecycleService（新增）

编排者。构造注入：`ProviderService`、`AgentLoader`、`ProfileRegistry`、`AgentScheduler`、`AgentStore`，以及生成用 `(provider, model)`（配置注入）。

| 方法 | 行为 |
|---|---|
| `String generate(String sentence)` | 构造生成 Profile + `ProviderRequest.of(AGENT_AUTHOR_PROMPT + sentence)` → `providerService.chat(...).text()`；校验能否解析成合法定义（非法 `IllegalArgumentException`）；只返回文本、不落盘、不注册 |
| `Profile create(String name, String agentMarkdown)` | `exists(name)` → `IllegalArgumentException`；否则 `agentStore.write` → `try register(dir) catch { agentStore.delete(dir); throw }` |
| `Profile register(Path agentDir)` | `deriveProfile → profileRegistry.register →（schedules 非空）registerProfile`；三录入共用 |
| `Profile get(String name)` | `profileRegistry.get(name).orElseThrow(ResourceNotFoundException)` |
| `Profile update(String name, String agentMarkdown, ...)` | 覆写目录 `AGENT.md`；schedules 变则先 `unregisterProfile(old)` 再 `registerProfile(new)` |
| `void delete(String name)` | `unregisterProfile → remove → archive`（找不到 `ResourceNotFoundException`） |
| `void unregisterByDir(Path agentDir)` | watcher 删除事件用：按目录名找 Profile 注销（不归档，因目录已被手工删） |

### AgentStore（新增）

Agent 目录的文件读写，限定在 `.oryxos/`。

| 方法 | 行为 |
|---|---|
| `Path write(String name, String agentMarkdown)` | 建 `.oryxos/agents/<name>/`，写 `AGENT.md`，返回目录 Path |
| `void delete(Path agentDir)` | 递归删目录（create 回滚用） |
| `void archive(String name)` | 整个 `.oryxos/agents/<name>/` 移入 `.oryxos/archive/<name>/`（不物理删；同名归档加时间戳后缀避免覆盖） |

### WorkspaceWatcher（新增）

| 成员 | 说明 |
|---|---|
| `void start()` | 注册 `WatchService` 于 `.oryxos/agents/`，起 daemon 线程跑事件循环（cli `@Bean(initMethod)`） |
| `void handleChange(Path agentDir, WatchEvent.Kind<?> kind)` | CREATE/MODIFY → `lifecycle.register(agentDir)`；DELETE → `lifecycle.unregisterByDir(agentDir)`；坏目录 `try/catch` WARN 跳过。**包级可见供单测直接调** |

### AgentScheduler（改造点）

- 新增 `public void unregisterProfile(Profile profile)`：遍历 `profile.schedules()`，从既有私有 `scheduledTasks` 取 `ScheduledFuture` `cancel(false)` + 移除句柄；不动 `taskLocks`。
- 既有 `registerProfile`/`registerAll`/`hasScheduledTask`/`scheduledTasks` 不变。

## Web 层（oryxos-web）

### AgentApiController（改造：加方法，invoke 不变）

薄转发 `AgentLifecycleService`。端点见 contracts。

### WorkspaceApiController（新增，只读）

`tree()` → 目录树；`file(path)` → 防目录穿越后读文本。

### DTO（新增，web 层）

- `AgentView(String name, String description, String provider, String model, List<String> tools, List<ScheduleView> schedules)` —— 从 `Profile` 投影（不暴露内部结构；沿用 26 节 ProfileView 风格）。
- `FileNode(String name, String path, String type, List<FileNode> children)` —— `type` ∈ {dir, file}；目录树节点。
- `GenerateRequest(String sentence)`。
- `CreateAgentRequest(String name, String agentMarkdown)` —— 或结构化字段拼成 `AGENT.md`（本版以整段 markdown 为主，带脚本走手工丢目录）。
- `UpdateAgentRequest(String agentMarkdown)`。

## 运行时目录

- `.oryxos/archive/`：删除的 Agent 目录归档处（不物理删，供追溯）。

## 校验规则（来自 spec FR）

- FR-003：create 失败回滚，不留半个 Agent。
- FR-007：delete 恒序 `unregister → remove → archive`。
- FR-008：file 路径 `normalize()` 后必须 `startsWith(oryxosRoot)`。
- FR-010：400=`IllegalArgumentException`、404=`ResourceNotFoundException`，统一 `ApiResponse`。
