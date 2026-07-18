package io.oryxos.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.Map;

/**
 * 工具执行的唯一路径（宪法 I/II：执行权只在这里，Provider 侧自动执行已关闭）。
 *
 * <p>成功要记、失败也要记（宪法 V）：每次执行不论成败都写 tool_invocations——先落审计再还结果。 工具异常转失败 ToolResult
 * 交还循环（模型下一轮能看到失败原因并决定下一步），不上抛不中断。
 */
public class ToolExecutor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, OryxTool> tools;
  private final ToolInvocationAuditor auditor;

  public ToolExecutor(Map<String, OryxTool> tools, ToolInvocationAuditor auditor) {
    this.tools = Map.copyOf(tools);
    this.auditor = auditor;
  }

  public ToolResult execute(String sessionId, String agentName, ToolCallRequest call) {
    long startedAt = System.currentTimeMillis();
    OryxTool tool = tools.get(call.name());
    if (tool == null) {
      return fail(sessionId, call, "未注册的工具: " + call.name(), startedAt);
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
