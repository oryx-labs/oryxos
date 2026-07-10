package io.oryxos.core.agent;

/**
 * tool_invocations 审计写入口（宪法 V：Day One 落库）——一次工具调用不管成没成，事后都得能查到。
 *
 * <p>实现方（oryxos-storage 的 JPA 实现）必须自吞内部异常并记 ERROR——审计自身失败不阻断工具结果返回， 口径与 16 节 LlmCallAuditor 一致。
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
