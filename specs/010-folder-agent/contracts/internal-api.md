# Internal API Contracts: 插件化 Agent

本节无对外 REST/CLI 新契约（对外动态管理接口在 30 节）。下面是 `oryxos-core` 内部的公共/包级契约——tasks 与 harness 对号用。签名为意图约束，最终以实现为准（禁 Java 18+ 语法形态）。

## 新增

### AgentLoader（`io.oryxos.core.agent`）

```java
public class AgentLoader {
  // agentsDir = .oryxos/agents/；knownProviders 用于告警"引用未注册 provider/能力"
  public AgentLoader(Path agentsDir, Set<String> knownProviders);

  // 扫 agentsDir 下每个子目录 → 逐个 deriveProfile → 收进一个 ProfileRegistry。
  // 坏 Agent 记错误并跳过，不阻断（FR-009）；不产生别的东西（FR-002）。
  public ProfileRegistry loadAll();

  // 单个目录派生：拆 AGENT.md frontmatter → ProfileLoader.fromMap 校验 → Profile。
  // 缺 name/provider 抛 ProfileValidationException（与启动同一异常同一消息，FR-006）。
  Profile deriveProfile(Path agentDir) throws IOException;
}
```

### AgentMarkdown（`io.oryxos.core.agent`）

```java
public final class AgentMarkdown {
  // 拆 "---\n<yaml>\n---\n<body>"；无 frontmatter → 空 Map + 全文当 body。
  public static Parsed split(String content);        // Parsed(Map<String,Object> frontmatter, String body)
}
```

## 改造（前序公共类型 —— 课件改造点 + D3 软门禁项）

### Profile（`io.oryxos.core.profile`）—— 移除 skills 字段（D3，待确认）

```java
// 12 参 → 11 参（移除 List<String> skills，原第 6 位置）
public record Profile(
    String name, String description, Identity identity, ProviderRef provider,
    List<String> tools, List<String> mcpServers, List<String> channels,
    List<NotifyChannel> notifyChannels, List<ScheduleConfig> schedules,
    List<String> bootstrap, Settings settings) { ... }
```

### ProfileLoader（`io.oryxos.core.profile`）

```java
// 抽出可复用校验入口（供 AgentLoader.deriveProfile 复用 → 同一异常同一消息）
static Profile fromMap(Map<String, Object> map, String source);   // 抛 ProfileValidationException
// loadAll()（扫 .oryxos/profiles/）退出装配接线；停解析 skills
```

### ProfileRegistry（`io.oryxos.core.profile`）

```java
public class ProfileRegistry {
  public ProfileRegistry();                         // 可变并发 Map（原 ctor(Map) 兼容保留/或改造）
  public ProfileRegistry(Map<String, Profile> initial);
  public Optional<Profile> get(String name);        // 保留
  public Collection<Profile> all();                 // 保留
  public void register(Profile profile);            // 新增：运行时登记，校验同启动
  public boolean remove(String name);               // 新增
  public boolean exists(String name);               // 新增
}
```

### AgentScheduler（`io.oryxos.core.agent`）

```java
public class AgentScheduler {
  // 既有 ctor / registerAll() / runOnce / runNow / execute / lockFor 保留
  public void registerAll();                        // 改：遍历 ProfileRegistry 调 registerProfile
  public void registerProfile(Profile profile);     // 新增：抽出的单条注册，登记 schedules + 留句柄
  // private final Map<String, ScheduledFuture<?>> scheduledTasks;  // 新增句柄表（30 节注销用）
}
```

### ContextLoader（`io.oryxos.core.context`）

```java
public class ContextLoader {
  public ContextLoader(Path oryxosRoot);            // ctor 不变
  // load：identity.prompt + 现读 agents/<name>/AGENT.md 正文 + bootstrap；删 skills 循环
  public String load(Profile profile);
}
```

## 契约级验收锚点（harness 对号）

| 契约 | 测试 | 守点 |
|---|---|---|
| `AgentLoader.deriveProfile` | `AgentLoaderTest` | 拆 frontmatter/正文、认 scripts/skills/REFERENCE.md、缺 name/provider 点名 |
| `AgentLoader.deriveProfile` | `DeriveProfileTest` | frontmatter 各字段正确映射、schedules 原样带进 Profile |
| `AgentLoader.loadAll` | `AgentScanRegisterTest` | 扫 N → 注册表 N 个、带 schedules 进 scheduler、不产生别的 |
| `ProfileRegistry.register` | `ProfileRegistryRuntimeTest` | register 后立即 get 可见；非法配置与启动同一异常同一消息 |
| `AgentScheduler.registerProfile` | `AgentSchedulerRegisterTest` | 注册后 scheduledTasks 有句柄；cron/zone 来自 Profile.schedules |
| `ContextLoader.load` | `ProgressiveDisclosureTest` | 正文进 prompt；子指令/参考/脚本不预载靠 read_file/shell 按需取 |
