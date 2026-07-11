# Contracts: CLI 命令面与会话管理

**Date**: 2026-07-10 | 消费方：终端用户（命令面）+ 26 节 Web / 25 节定时（SessionManager 三元组口径）

## 1. 命令面（用户契约）

```text
oryxos init                          # 初始化 .oryxos/ 工作区（幂等）
oryxos status                        # 工作区/配置/库文件状态
oryxos chat [--profile <name>]       # 交互对话（默认 default）；/quit 退出、EOF 等同退出、空行跳过
oryxos serve [--port 8080]           # 启动骨架：起 Spring 常驻（REST 端点 26 节）
oryxos gateway                       # 启动骨架：守护进程模式
oryxos profile list|create <name>|show <name>|delete <name>
oryxos provider list                 # 实例声明的 provider 清单
oryxos tool list                     # 可用工具清单（20 节接 ToolRegistry）
oryxos session list                  # sessions 表概览（库不存在→暂无会话）
```

统一行为：全部命令支持 `--help`；未知子命令/参数由 Picocli 统一报错（退出码非 0，无堆栈）。

## 2. SessionManager（io.oryxos.core.session，17 节前向接口本节补全）

```java
public interface SessionManager {
    /** 三元组唯一决定会话；同一三元组幂等返回同一条（含已恢复历史）。id 拼接只在实现内部。 */
    Session getOrCreate(String channel, String userId, String profileName);
    Optional<Session> get(String sessionId);
    void save(Session session);   // 17 节已有：序列化历史 + 刷 last_active_at
}
```

行为契约：`getOrCreate` 未命中即落一条 status=active 的新记录；`save` 整体覆盖 messages_json；archived 状态本节不产生。

## 3. CliChannel（io.oryxos.channel.cli）

```java
public class CliChannel {
    public CliChannel(AgentService agentService, SessionManager sessionManager);
    /** 课件骨架：getOrCreate("cli", userId, profileName) → 循环读行→process→打印，/quit 退出。 */
    public void run(String profileName, String userId);
}
```

行为契约：channel 字面量 `"cli"` 只作为三元组参数出现；空行跳过；EOF 等同 `/quit`；Profile 不存在时报错退出不进循环（AgentService 点名异常透出）。

## 4. 恢复构造器（io.oryxos.core.session.Session，改造点）

```java
public Session(String sessionId, String profileName, List<Message> restored);
```

## 5. OryxOsRuntime 装配面（oryxos-cli，重命令专用）

`@SpringBootApplication(scanBasePackages="io.oryxos")` + `@EnableJpaRepositories(basePackages="io.oryxos.storage")` + `@EntityScan(basePackages="io.oryxos.storage")`；`@Bean` 显式装配：providerMap（宪法 III）→ SpringAiProviderServiceImpl → 审计双 auditor → ProfileRegistry → ContextLoader → PromptBuilder → ToolExecutor(空 Map，20 节) → ReActLoop → JpaSessionManager → AgentService → CliChannel。
