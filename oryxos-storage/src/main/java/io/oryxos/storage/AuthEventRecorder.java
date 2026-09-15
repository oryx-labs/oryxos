package io.oryxos.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证事件落库（040）。
 *
 * <ul>
 *   <li>{@link #recordOrThrow}——LOGIN_SUCCESS / 映射变更：写失败抛出（fail-closed，拒绝建 session / 拒绝变更）
 *   <li>{@link #recordBestEffort}——LOGIN_FAILURE / LOGOUT：写失败只记 ERROR，不改变主路径结果
 * </ul>
 */
public class AuthEventRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(AuthEventRecorder.class);

  private static final int MAX_PRINCIPAL = 128;
  private static final int MAX_DETAIL = 1024;

  private final AuthEventRepository repository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图。")
  public AuthEventRecorder(AuthEventRepository repository) {
    this.repository = repository;
  }

  /** 成功路径 / 映射变更：写失败向上抛，调用方据此拒绝建 session 或拒绝变更。 */
  public void recordOrThrow(AuthEventType type, String principalId, String detail) {
    repository.save(newEvent(type, principalId, detail));
  }

  /** 失败路径 / 登出：写失败吞掉并 ERROR，不掩盖原失败原因、不阻断登出。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "exception toString 仅诊断；失败审计不得改变主路径结果；无凭证明文。")
  public void recordBestEffort(AuthEventType type, String principalId, String detail) {
    try {
      repository.save(newEvent(type, principalId, detail));
    } catch (RuntimeException ex) {
      LOG.error("auth_events 写入失败（best-effort，主路径不回滚）：{}", ex.toString());
    }
  }

  private static AuthEvent newEvent(AuthEventType type, String principalId, String detail) {
    AuthEvent event = new AuthEvent();
    event.setEventType(type == null ? "UNKNOWN" : type.name());
    event.setPrincipalId(truncate(principalId, MAX_PRINCIPAL));
    event.setDetail(truncate(detail, MAX_DETAIL));
    return event;
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
