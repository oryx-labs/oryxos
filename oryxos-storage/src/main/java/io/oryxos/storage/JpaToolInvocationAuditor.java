package io.oryxos.storage;

import io.oryxos.core.agent.ToolInvocationAuditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ToolInvocationAuditor 的 JPA 实现。
 *
 * <p>审计自身失败只记 ERROR、不上抛——可用性优先，审计故障不阻断工具结果返回（口径同 16 节 D5）。
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
      LOG.error("tool_invocations 审计写入失败（不阻断工具结果返回）: {}", sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
