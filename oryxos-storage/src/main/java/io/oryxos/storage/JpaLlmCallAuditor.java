package io.oryxos.storage;

import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LlmCallAuditor 的 JPA 实现。
 *
 * <p>审计写入失败时 fail-closed：记录错误并抛出异常，防止主链路在没有审计记录的情况下返回成功。
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
      LOG.error("llm_calls 审计写入失败: {}", sanitize(e.getMessage()));
      throw new IllegalStateException("llm_calls 审计写入失败", e);
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
