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
    justification =
        "tools / mcpToolOwners / profileRegistry 都是运行时共享索引：MCP 增删必须立刻可见，copyOf 会把执行面冻在装配瞬间。")
public class ToolExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, OryxTool> tools;

  /** 审计的策略拒绝标记（tool_invocations.blocked_by，020 FR-006）。 */
  private static final String POLICY_BLOCKED = "policy";

  private final Map<String, String> mcpToolOwners;

  /** 020 工具策略（事中保险）。默认 ALLOW_ALL——旧构造/未装配策略时行为与现状一致。 */
  private io.oryxos.core.policy.ToolPolicyService toolPolicy =
      io.oryxos.core.policy.ToolPolicyService.ALLOW_ALL;

  private final ProfileRegistry profileRegistry;
  private final ToolInvocationAuditor auditor;
  private final AgentRunEventPublisher events;

  public ToolExecutor(Map<String, OryxTool> tools, ToolInvocationAuditor auditor) {
    this(tools, Map.of(), null, auditor, null);
  }

  /** 装配期注入（OryxOsRuntime）；测试直构不调用即保持 ALLOW_ALL（零策略零破坏）。 */
  public void setToolPolicy(io.oryxos.core.policy.ToolPolicyService toolPolicy) {
    this.toolPolicy =
        toolPolicy == null ? io.oryxos.core.policy.ToolPolicyService.ALLOW_ALL : toolPolicy;
  }

  /** 023 业务指标（装配期注入，setToolPolicy 同款惯例）；未装配保持 NOOP 零破坏。 */
  private io.oryxos.core.metrics.MetricsRecorder metrics =
      io.oryxos.core.metrics.MetricsRecorder.NOOP;

  public void setMetricsRecorder(io.oryxos.core.metrics.MetricsRecorder metrics) {
    this.metrics = metrics == null ? io.oryxos.core.metrics.MetricsRecorder.NOOP : metrics;
  }

  /** 31 节：注入 MCP 工具归属表 + ProfileRegistry，用以按调用方 Agent 的 mcp_servers 声明做白名单校验。 */
  public ToolExecutor(
      Map<String, OryxTool> tools,
      Map<String, String> mcpToolOwners,
      ProfileRegistry profileRegistry,
      ToolInvocationAuditor auditor) {
    this(tools, mcpToolOwners, profileRegistry, auditor, null);
  }

  public ToolExecutor(
      Map<String, OryxTool> tools,
      Map<String, String> mcpToolOwners,
      ProfileRegistry profileRegistry,
      ToolInvocationAuditor auditor,
      AgentRunEventPublisher events) {
    this.tools = tools;
    this.mcpToolOwners = mcpToolOwners;
    this.profileRegistry = profileRegistry;
    this.auditor = auditor;
    this.events = events;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的工具名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public ToolResult execute(String sessionId, String agentName, ToolCallRequest call) {
    if (AgentRunExecutionContext.isCancelRequested()) {
      throw new RunCancelledException();
    }
    long startedAt = System.currentTimeMillis();
    String toolCallId = call.id() == null || call.id().isBlank() ? call.name() : call.id();
    publish(
        AgentRunEventTypes.TOOL_CALL_STARTED,
        java.util.Map.of(
            "toolCallId",
            toolCallId,
            "toolName",
            call.name(),
            "inputSummary",
            AgentRunEventPayloads.summarizeJson(call.argumentsJson())));
    OryxTool tool = tools.get(call.name());
    if (tool == null) {
      return fail(sessionId, agentName, call, "未注册的工具: " + call.name(), startedAt);
    }
    String deniedReason = checkMcpAuthorization(agentName, call.name());
    if (deniedReason != null) {
      return fail(sessionId, agentName, call, deniedReason, startedAt);
    }
    // 020 事中保险：按执行瞬间的最新策略裁决（覆盖模型幻觉调用与热更新窗口——本轮 prompt 可见但已被禁）
    var policyDecision = toolPolicy.check(agentName, call.name());
    if (!policyDecision.allowed()) {
      return fail(
          sessionId,
          agentName,
          call,
          "被平台策略禁止：" + policyDecision.reason(),
          POLICY_BLOCKED,
          startedAt);
    }
    JsonNode input;
    try {
      input = MAPPER.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
    } catch (Exception e) {
      return fail(sessionId, agentName, call, "工具入参不是合法 JSON: " + e.getMessage(), startedAt);
    }
    // 沙箱检查位：24 节 SandboxChecker 就位后在此接线（执行前白名单校验，宪法 VI）
    // 置入当前 Agent 名（30 节 Agent 专属记忆）：save_memory 等工具据此落到本 Agent 自己的 MEMORY.md；执行后必清除。
    ToolExecutionContext.setAgentName(agentName);
    try {
      ToolResult result;
      try {
        result = tool.execute(input);
      } catch (RuntimeException e) {
        return fail(sessionId, agentName, call, e.getMessage(), startedAt);
      }
      recordCompleted(sessionId, agentName, call, toolCallId, result, startedAt);
      return result;
    } finally {
      ToolExecutionContext.clear();
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的工具名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  private void recordCompleted(
      String sessionId,
      String agentName,
      ToolCallRequest call,
      String toolCallId,
      ToolResult result,
      long startedAt) {
    // 审计 fail-open：工具已执行完、副作用已发生，审计存储抖动不应让循环把这次执行当失败处理（否则模型可能
    // 重调一次有副作用的工具）；不重试审计也不伪造第二条失败审计，失败走 ERROR 日志独立告警。
    // 024：docker 档时补记执行后端与容器 ID（此时进程已结束，cidfile 必已写入）；local 档不置上下文，
    // 走既有 8 参签名——对 auditor 的既有交互契约（现存测试按 8 参 stub/verify）零变化。
    String executionBackend = ToolExecutionContext.executionBackend();
    try {
      if (executionBackend == null) {
        auditor.record(
            sessionId,
            agentName,
            call.name(),
            call.argumentsJson(),
            result.success() ? result.content() : null,
            result.success(),
            result.success() ? null : result.errorMessage(),
            System.currentTimeMillis() - startedAt);
      } else {
        auditor.record(
            sessionId,
            agentName,
            call.name(),
            call.argumentsJson(),
            result.success() ? result.content() : null,
            result.success(),
            result.success() ? null : result.errorMessage(),
            null,
            executionBackend,
            ToolExecutionContext.containerId(),
            System.currentTimeMillis() - startedAt);
      }
    } catch (RuntimeException auditFailure) {
      LOG.error("工具调用审计落库失败（结果照常返回）: tool={}", sanitize(call.name()), auditFailure);
    }
    publishToolFinished(call, toolCallId, result, startedAt);
    try {
      metrics.recordToolInvocation(call.name(), result.success()); // 023：指标异常不伤主链路
    } catch (RuntimeException ignored) {
      // FR-010
    }
    // 021 日志与审计互查（SC-007）：处理路径关键日志点——MDC 自动携带 traceId，不记参数/结果（防敏感泄漏）
    LOG.info(
        "工具执行完成: tool={} success={} durationMs={}",
        sanitize(call.name()),
        result.success(),
        System.currentTimeMillis() - startedAt);
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
      String sessionId,
      String agentName,
      ToolCallRequest call,
      String errorMessage,
      long startedAt) {
    return fail(sessionId, agentName, call, errorMessage, null, startedAt);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的工具名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  private ToolResult fail(
      String sessionId,
      String agentName,
      ToolCallRequest call,
      String errorMessage,
      String blockedBy,
      long startedAt) {
    // 同成功路径：审计失败不掩盖工具的真实失败原因（否则循环看到的是审计异常而非工具错误）。
    // blockedBy 为空走旧 8 参签名——保持对 auditor 的既有交互契约（现存测试按 8 参 stub/verify）。
    // 024：docker 档失败（含超时被杀）同样补记后端与容器 ID——SC-004 的审计故事；local 档交互不变。
    try {
      long duration = System.currentTimeMillis() - startedAt;
      String executionBackend = ToolExecutionContext.executionBackend();
      if (executionBackend != null) {
        auditor.record(
            sessionId,
            agentName,
            call.name(),
            call.argumentsJson(),
            null,
            false,
            errorMessage,
            blockedBy,
            executionBackend,
            ToolExecutionContext.containerId(),
            duration);
      } else if (blockedBy == null) {
        auditor.record(
            sessionId,
            agentName,
            call.name(),
            call.argumentsJson(),
            null,
            false,
            errorMessage,
            duration);
      } else {
        auditor.record(
            sessionId,
            agentName,
            call.name(),
            call.argumentsJson(),
            null,
            false,
            errorMessage,
            blockedBy,
            duration);
      }
    } catch (RuntimeException auditFailure) {
      LOG.error("工具调用失败的审计落库也失败（失败结果照常返回）: tool={}", sanitize(call.name()), auditFailure);
    }
    String toolCallId = call.id() == null || call.id().isBlank() ? call.name() : call.id();
    publish(
        AgentRunEventTypes.TOOL_CALL_FINISHED,
        java.util.Map.of(
            "toolCallId",
            toolCallId,
            "toolName",
            call.name(),
            "success",
            false,
            "error",
            AgentRunEventPayloads.summarizeText(errorMessage),
            "durationMs",
            System.currentTimeMillis() - startedAt));
    try {
      metrics.recordToolInvocation(call.name(), false); // 023：失败（含被拦）也计数
      if (blockedBy != null) {
        metrics.recordPolicyBlock(call.name());
      }
    } catch (RuntimeException ignored) {
      // FR-010：指标失败静默
    }
    return ToolResult.error(errorMessage, false);
  }

  private void publishToolFinished(
      ToolCallRequest call, String toolCallId, ToolResult result, long startedAt) {
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("toolCallId", toolCallId);
    payload.put("toolName", call.name());
    payload.put("success", result.success());
    payload.put("durationMs", System.currentTimeMillis() - startedAt);
    if (result.success()) {
      payload.put("outputSummary", AgentRunEventPayloads.summarizeText(result.content()));
    } else {
      payload.put("error", AgentRunEventPayloads.summarizeText(result.errorMessage()));
    }
    publish(AgentRunEventTypes.TOOL_CALL_FINISHED, payload);
  }

  private void publish(String type, java.util.Map<String, Object> payload) {
    if (events == null) {
      return;
    }
    events.publishCurrent(type, payload);
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
