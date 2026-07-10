package io.oryxos.storage;

import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LlmCallAuditor 的 JPA 实现。
 *
 * <p>research D5：审计自身失败只记 ERROR、不上抛——可用性优先，审计故障不阻断模型调用主链路。
 */
public class JpaLlmCallAuditor implements LlmCallAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(JpaLlmCallAuditor.class);

  private final LlmCallRepository repository;

  public JpaLlmCallAuditor(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs) {
    try {
      LlmCall record = new LlmCall();
      record.setSessionId(sessionId);
      record.setProvider(provider);
      record.setModel(model);
      if (usage != null) {
        record.setPromptTokens(usage.promptTokens());
        record.setCompletionTokens(usage.completionTokens());
        record.setTotalTokens(usage.totalTokens());
      }
      record.setSuccess(success);
      record.setErrorMessage(errorMessage);
      record.setDurationMs(durationMs);
      repository.save(record);
    } catch (RuntimeException e) {
      LOG.error("llm_calls 审计写入失败（不阻断调用）: {}", sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
