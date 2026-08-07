package io.oryxos.core.agent;

/**
 * tool_invocations 审计写入口（宪法 V：Day One 落库）——一次工具调用不管成没成，事后都得能查到。
 *
 * <p>实现方必须在写入失败时抛出异常，调用链不得在缺少审计记录的情况下返回工具结果。
 */
public interface ToolInvocationAuditor {

  void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs);
}
