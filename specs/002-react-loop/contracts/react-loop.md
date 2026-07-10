# Contracts: ReAct 循环六个契约面

**Date**: 2026-07-10 | 消费方：17 节内部互联 + 18 节 Channel/CLI（AgentService）+ 20 节 Tool（ProfileContext/Map 注入）+ 24 节 Sandbox（ToolExecutor 检查位）

## 1. ReActLoop（io.oryxos.core.agent）

```java
public class ReActLoop {
    public ReActLoop(PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor);
    /** 课件字面量签名。返回最终答复文本；转满 maxIterations 返回 "达到最大轮数，已停止"。 */
    public String run(Session session, String userMessage, Profile profile);
}
```

行为契约：只调度不执行；每轮先 append 响应再判停；无 toolCalls 即收尾（text null → 空串）；toolCalls 逐个顺序执行、结果全部回填后进下一轮；恰好 maxIterations 轮后强制停（一轮不多）。

## 2. PromptBuilder（io.oryxos.core.agent）

```java
public class PromptBuilder {
    public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools);          // 默认系统时钟
    public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools, Clock clock); // 测试注入
    /** 四段按序拼接为单段文本；工具经 availableTools 传递（Profile.tools 里点名的那些）。 */
    public ProviderRequest build(Session session, Profile profile);
}
```

行为契约：system 段末尾含当前日期时间行；记忆段本节恒空跳过；历史段只留最近 maxHistoryTurns 轮（一轮=一条 user 消息及其后全部消息，恰好 N 轮不截断）；每次 build 重新经 ContextLoader 读文件。

## 3. ToolExecutor（io.oryxos.core.agent）

```java
public class ToolExecutor {
    public ToolExecutor(Map<String, OryxTool> tools, ToolInvocationAuditor auditor);
    /** 唯一工具执行路径。执行前过沙箱检查位（24 节接线）；成败都落审计后返回。 */
    public ToolResult execute(String sessionId, ToolCallRequest call);
}

public interface ToolInvocationAuditor {
    void record(String sessionId, String toolName, String inputJson, String resultJson,
                boolean success, String errorMessage, long durationMs);
}
```

行为契约：工具未注册 → 失败 ToolResult + success=false 审计；工具抛 RuntimeException → 转失败 ToolResult（errorMessage 带原因）+ success=false 审计，不上抛；成功 → success=true 审计。审计先落、结果后还。

## 4. AgentService + ProfileContext（io.oryxos.core.agent）

```java
public class AgentService {
    public AgentService(ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager);
    /** 课件字面量签名。统一处理入口（CLI/Web/定时共用）。 */
    public String process(Session session, String userMessage);
}

public final class ProfileContext {
    public static void set(Profile profile);
    public static Profile current();   // 未设置时 null
    public static void clear();        // ThreadLocal.remove()
}
```

行为契约：process 入口 set、finally 必 clear（异常路径也清）；Profile 不存在 → 点名报错；仅正常返回前 save(session)。

## 5. ContextLoader（io.oryxos.core.context）

```java
public class ContextLoader {
    public ContextLoader(Path oryxosRoot);   // .oryxos 目录
    /** identity.prompt + bootstrap 文件 + skills/{name}.md 按序拼接；每次调用重读，无缓存。 */
    public String load(Profile profile);
}
```

行为契约：Skill 文件缺失 → IllegalStateException 点名；Bootstrap 文件缺失 → WARN + 跳过；改文件后下一次 load 立即读到新内容。

## 6. 契约上移与前向最小（D1/D2）

```java
// io.oryxos.core.provider —— 自 io.oryxos.provider 移入，签名逐字保真
public interface ProviderService {
    ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}
// ProviderRequest / ProviderResponse / ToolCallRequest / Usage / LlmCallAuditor 原样移动

// io.oryxos.core.session —— 前向最小，18 节补全
public interface SessionManager {
    void save(Session session);
}
```

oryxos-provider：`SpringAiProviderService implements ProviderService`（原实现体零改动）；oryxos-storage：pom 去掉 provider 依赖，`JpaLlmCallAuditor` 改 import。
