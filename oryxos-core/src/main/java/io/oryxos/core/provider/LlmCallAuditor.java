package io.oryxos.core.provider;

/**
 * llm_calls 审计写入口（宪法 V：Day One 落库）。
 *
 * <p>实现方（oryxos-storage 的 JPA 实现）必须自吞内部异常并记 ERROR——审计自身失败不阻断模型调用（research D5）。
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
