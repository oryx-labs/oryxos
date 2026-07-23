package io.oryxos.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行的唯一路径（宪法 I/II：执行权只在这里，Provider 侧自动执行已关闭）。
 *
 * <p>成功要记、失败也要记（宪法 V）：每次执行不论成败都写 tool_invocations——先落审计再还结果。 工具异常转失败 ToolResult
 * 交还循环（模型下一轮能看到失败原因并决定下一步），不上抛不中断。
 *
 * <p>31 节：补上 {@code mcp_servers} 白名单强制生效——{@code profileRegistry}/{@code mcpToolOwners} 均为 null/空时
 * （旧 2-arg 构造）行为与之前完全一致，不校验；两者都注入后，调用一个 MCP 工具前会校验发起调用的 Agent 是否在自己的 {@code mcp_servers} 里声明了该工具所属的
 * server——声明了 tools 列表不等于自动拿到所有已连接 MCP server 的权限。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "profileRegistry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class ToolExecutor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, OryxTool> tools;
  private final Map<String, String> mcpToolOwners;
  private final ProfileRegistry profileRegistry;
  private final ToolInvocationAuditor auditor;

  public ToolExecutor(Map<String, OryxTool> tools, ToolInvocationAuditor auditor) {
    this(tools, Map.of(), null, auditor);
  }

  /** 31 节：注入 MCP 工具归属表 + ProfileRegistry，用以按调用方 Agent 的 mcp_servers 声明做白名单校验。 */
  public ToolExecutor(
      Map<String, OryxTool> tools,
      Map<String, String> mcpToolOwners,
      ProfileRegistry profileRegistry,
      ToolInvocationAuditor auditor) {
    this.tools = Map.copyOf(tools);
    this.mcpToolOwners = Map.copyOf(mcpToolOwners);
    this.profileRegistry = profileRegistry;
    this.auditor = auditor;
  }

  /** Executes only tools explicitly granted by the current Profile and audits every rejection. */
  public ToolResult execute(String sessionId, String agentName, ToolCallRequest call) {
    return execute(sessionId, agentName, tools.keySet(), call);
  }

  /** Executes only tools explicitly granted by the current Profile and audits every rejection. */
  public ToolResult execute(
      String sessionId,
      String agentName,
      Collection<String> allowedToolNames,
      ToolCallRequest call) {
    Objects.requireNonNull(allowedToolNames, "allowedToolNames");
    Objects.requireNonNull(call, "call");
    long startedAt = System.currentTimeMillis();
    if (!allowedToolNames.contains(call.name())) {
      return fail(sessionId, call, "工具未获当前 Agent 授权: " + call.name(), startedAt);
    }
    OryxTool tool = tools.get(call.name());
    if (tool == null) {
      return fail(sessionId, call, "未注册的工具: " + call.name(), startedAt);
    }
    String deniedReason = checkMcpAuthorization(agentName, call.name());
    if (deniedReason != null) {
      return fail(sessionId, call, deniedReason, startedAt);
    }
    JsonNode input;
    try {
      input = MAPPER.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
    } catch (Exception e) {
      return fail(sessionId, call, "工具入参不是合法 JSON: " + e.getMessage(), startedAt);
    }
    // 沙箱检查位：24 节 SandboxChecker 就位后在此接线（执行前白名单校验，宪法 VI）
    // 置入当前 Agent 名（30 节 Agent 专属记忆）：save_memory 等工具据此落到本 Agent 自己的 MEMORY.md；执行后必清除。
    ToolExecutionContext.setAgentName(agentName);
    try {
      ToolResult result = tool.execute(input);
      auditor.record(
          sessionId,
          call.name(),
          call.argumentsJson(),
          result.success() ? result.content() : null,
          result.success(),
          result.success() ? null : result.errorMessage(),
          System.currentTimeMillis() - startedAt);
      return result;
    } catch (RuntimeException e) {
      return fail(sessionId, call, e.getMessage(), startedAt);
    } finally {
      ToolExecutionContext.clear();
    }
  }

  /**
   * 一个工具名若属于某个 MCP server（{@code mcpToolOwners} 有记录），调用方 Agent 必须在自己的 Profile.mcpServers() 里声明过那个
   * server 名，否则拒绝——返回非空的拒绝原因；放行返回 null。{@code profileRegistry} 为 null（旧构造）时不校验， 保持 20 节既有行为不变。
   */
  private String checkMcpAuthorization(String agentName, String toolName) {
    String owner = mcpToolOwners.get(toolName);
    if (owner == null || profileRegistry == null) {
      return null;
    }
    boolean declared =
        profileRegistry
            .get(agentName)
            .map(Profile::mcpServers)
            .orElse(java.util.List.of())
            .contains(owner);
    if (declared) {
      return null;
    }
    return "Agent 未在 mcp_servers 声明所属 server「" + owner + "」，拒绝调用: " + toolName;
  }

  private ToolResult fail(
      String sessionId, ToolCallRequest call, String errorMessage, long startedAt) {
    auditor.record(
        sessionId,
        call.name(),
        call.argumentsJson(),
        null,
        false,
        errorMessage,
        System.currentTimeMillis() - startedAt);
    return ToolResult.error(errorMessage, false);
  }
}
