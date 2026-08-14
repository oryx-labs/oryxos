package io.oryxos.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

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

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的工具名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public ToolResult execute(String sessionId, String agentName, ToolCallRequest call) {
    long startedAt = System.currentTimeMillis();
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
      ToolResult result;
      try {
        result = tool.execute(input);
      } catch (RuntimeException e) {
        return fail(sessionId, call, e.getMessage(), startedAt);
      }
      // 审计 fail-open：工具已执行完、副作用已发生，审计存储抖动不应让循环把这次执行当失败处理（否则模型可能
      // 重调一次有副作用的工具）；不重试审计也不伪造第二条失败审计，失败走 ERROR 日志独立告警。
      try {
        auditor.record(
            sessionId,
            call.name(),
            call.argumentsJson(),
            result.success() ? result.content() : null,
            result.success(),
            result.success() ? null : result.errorMessage(),
            System.currentTimeMillis() - startedAt);
      } catch (RuntimeException auditFailure) {
        LOG.error("工具调用审计落库失败（结果照常返回）: tool={}", sanitize(call.name()), auditFailure);
      }
      return result;
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

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的工具名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  private ToolResult fail(
      String sessionId, ToolCallRequest call, String errorMessage, long startedAt) {
    // 同成功路径：审计失败不掩盖工具的真实失败原因（否则循环看到的是审计异常而非工具错误）。
    try {
      auditor.record(
          sessionId,
          call.name(),
          call.argumentsJson(),
          null,
          false,
          errorMessage,
          System.currentTimeMillis() - startedAt);
    } catch (RuntimeException auditFailure) {
      LOG.error("工具调用失败的审计落库也失败（失败结果照常返回）: tool={}", sanitize(call.name()), auditFailure);
    }
    return ToolResult.error(errorMessage, false);
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
