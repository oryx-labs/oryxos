package io.oryxos.core.agent;

/**
 * tool_invocations 审计写入口（宪法 V：Day One 落库）——一次工具调用不管成没成，事后都得能查到。
 *
 * <p>实现方写入失败必须抛出异常（不得静默吞掉）。调用方必须尝试落库；落库失败时以 ERROR 日志告警，不得用审计异常 掩盖工具的真实执行结果（工具副作用已发生，成败结果都照常返回给循环）。
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
