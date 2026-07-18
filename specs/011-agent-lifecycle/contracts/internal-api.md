# Contracts: 动态管理 Agent

## REST 端点（oryxos-web，统一 `ApiResponse` 信封）

在 26 节既有 `AgentApiController`（`@RequestMapping("/api/v1/agents")`）上**加**：

| 方法 | 路径 | body / 参数 | 返回 | 错误 |
|---|---|---|---|---|
| POST | `/api/v1/agents/generate` | `GenerateRequest{sentence}` | `String`（AGENT.md 草稿） | LLM 产出非法 → 400 |
| POST | `/api/v1/agents` | `CreateAgentRequest{name, agentMarkdown}` | `AgentView` | name 冲突/定义非法/provider 不存在 → 400 |
| GET | `/api/v1/agents` | — | `List<AgentView>` | — |
| GET | `/api/v1/agents/{name}` | — | `AgentView` | 不存在 → 404 |
| PUT | `/api/v1/agents/{name}` | `UpdateAgentRequest{agentMarkdown}` | `AgentView` | 不存在 → 404；定义非法 → 400 |
| DELETE | `/api/v1/agents/{name}` | — | `Void` | 不存在 → 404 |
| POST | `/api/v1/agents/{name}/invoke` | （26 节既有，**不变**） | `MessageResponse` | — |

新增 `WorkspaceApiController`（只读）：

| 方法 | 路径 | 参数 | 返回 | 错误 |
|---|---|---|---|---|
| GET | `/api/v1/workspace/tree` | — | `FileNode`（agents/ + archive/ 树） | — |
| GET | `/api/v1/workspace/file` | `?path=` | `String`（文件文本） | 路径越界 → 400 |

## 内部方法契约（oryxos-core）

```java
// AgentLifecycleService（新增）
public String generate(String sentence);                        // 只生成、不落盘、不注册；非法→IllegalArgumentException
public Profile create(String name, String agentMarkdown);       // exists→IAE(400)；写目录→register；失败回滚
public Profile register(Path agentDir);                         // 三录入共用：deriveProfile→register→registerProfile
public Profile get(String name);                                // 不存在→ResourceNotFoundException(404)
public Profile update(String name, String agentMarkdown);       // 覆写；schedules 变→先 unregister 后 register
public void delete(String name);                                // unregisterProfile→remove→archive
public void unregisterByDir(Path agentDir);                     // watcher 删除事件用

// AgentStore（新增）
public Path write(String name, String agentMarkdown);
public void delete(Path agentDir);
public void archive(String name);

// WorkspaceWatcher（新增）
public void start();
void handleChange(Path agentDir, java.nio.file.WatchEvent.Kind<?> kind);  // 包级可见，单测直接调

// AgentScheduler（改造：加方法）
public void unregisterProfile(Profile profile);                 // 遍历 schedules，scheduledTasks 句柄 cancel(false)+移除
```

## 配置契约（clarify 已定）

```yaml
oryxos:
  generate:
    provider: deepseek     # 缺省取 oryxos.providers 第一个
    model: deepseek-chat   # 生成 AGENT.md 用的模型
```

## harness 对号

| 契约 | 测试 | 守点 |
|---|---|---|
| `create` 回滚 | `AgentLifecycleServiceTest` | 注册失败 → `agentStore.delete` 被调、`registerProfile` never、`exists` false |
| `create` 冲突 | `AgentLifecycleServiceTest` | name 已存在 → 第一步 IAE、`agentStore.write` never |
| `delete` 时序 | `AgentLifecycleServiceTest` | `InOrder`: unregisterProfile → remove → archive |
| `update` 改定时 | `AgentLifecycleServiceTest` | 先 unregisterProfile 后 registerProfile |
| `register` 共用 | `AgentLifecycleServiceTest` + `WorkspaceWatcherTest` | create 与 watcher 都走同一 `register(agentDir)` |
| `handleChange` | `WorkspaceWatcherTest` | CREATE→register、注册表出现；DELETE→注销；坏目录不拖垮 |
| `file` 防穿越 | `WorkspaceApiControllerTest` | `path=../../etc/passwd` → 400；正常 → 内容 |
| `generate` | `GenerateTest` | 产出可解析、不落盘不注册；非法→400 可读原因 |
| 端点转发 | `AgentApiControllerTest` | 薄转发；冲突→400、不存在→404、统一信封 |
