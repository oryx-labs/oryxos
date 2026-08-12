package io.oryxos.core.provider;

/**
 * llm_calls 审计写入口（宪法 V：Day One 落库）。
 *
 * <p>实现方必须在写入失败时抛出异常，调用链不得在缺少审计记录的情况下返回成功。
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
