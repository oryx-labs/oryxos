package io.oryxos.core.provider;

/**
 * llm_calls 审计写入口（宪法 V：Day One 落库）。
 *
 * <p>实现方写入失败必须抛出异常（不得静默吞掉）。调用方必须尝试落库；落库失败时以 ERROR 日志告警，不得用审计异常 掩盖 LLM
 * 调用的真实结果：成功响应照常返回；失败路径上抛原始异常并把审计异常挂 suppressed。
 */
public interface LlmCallAuditor {

  void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs);
}
