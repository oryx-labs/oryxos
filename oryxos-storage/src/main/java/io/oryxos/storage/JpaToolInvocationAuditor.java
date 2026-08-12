package io.oryxos.storage;

import io.oryxos.core.agent.ToolInvocationAuditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ToolInvocationAuditor 的 JPA 实现。
 *
 * <p>审计写入失败时 fail-closed：记录错误并抛出异常，防止主链路在没有审计记录的情况下返回工具结果。
 */
public class JpaToolInvocationAuditor implements ToolInvocationAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(JpaToolInvocationAuditor.class);

  private final ToolInvocationRepository repository;

  public JpaToolInvocationAuditor(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    try {
      ToolInvocation record = new ToolInvocation();
      record.setSessionId(sessionId);
      record.setToolName(toolName);
      record.setInputJson(inputJson);
      record.setResultJson(resultJson);
      record.setSuccess(success);
      record.setErrorMessage(errorMessage);
      record.setDurationMs(durationMs);
      repository.save(record);
    } catch (RuntimeException e) {
      LOG.error("tool_invocations 审计写入失败: {}", sanitize(e.getMessage()));
      throw new IllegalStateException("tool_invocations 审计写入失败", e);
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
