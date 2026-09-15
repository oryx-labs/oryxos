package io.oryxos.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 认证事件落库（040 US3）：任何异常只记 ERROR 不抛出——审计写失败绝不能阻断登录/登出主链路（FR-007）。
 *
 * <p>三纪律与 {@link AuthzEventRecorder} 同款：traceId 从 MDC 读（不依赖 servlet API）、超长字段截断、 RuntimeException
 * 吞掉只记日志。字段绝不含令牌/密码内容（调用方只传分类枚举与标识）。
 */
public class AuthEventRecorder {

  /** 事件类型常量：调用方与查询方共用，避免魔法串漂移。 */
  public static final String LOGIN_SUCCESS = "login_success";

  public static final String LOGIN_FAILURE = "login_failure";
  public static final String LOGOUT = "logout";
  public static final String MAPPING_CREATED = "mapping_created";
  public static final String ROLES_CHANGED = "roles_changed";

  /** 认证方式常量。 */
  public static final String METHOD_LOCAL = "local";

  public static final String METHOD_OIDC = "oidc";

  private static final Logger LOG = LoggerFactory.getLogger(AuthEventRecorder.class);

  private static final int MAX_USERNAME = 64;
  private static final int MAX_EXTERNAL = 255;
  private static final int MAX_ROLES = 255;
  private static final int MAX_REASON = 64;
  private static final int MAX_IP = 64;
  private static final String TRACE_MDC_KEY = "traceId";

  private final AuthEventRepository repository;

  /**
   * 一次认证事件的字段集；令牌内容/密码 NEVER 进入任何字段。
   *
   * @param eventType 事件类型（{@link #LOGIN_SUCCESS} 等常量）
   * @param authMethod 认证方式（{@link #METHOD_LOCAL} / {@link #METHOD_OIDC}）
   * @param username 映射后的 OryxOS 用户（失败/未映射时可空）
   * @param externalIssuer OIDC 事件的 IdP issuer（本地事件为空）
   * @param externalSubject OIDC 事件的 sub（跨登录审计串联锚点）
   * @param roles 本次生效角色快照 CSV（无角色语义的事件为空）
   * @param failureReason 失败分类枚举串（成功事件为空）
   * @param sourceIp 来源 IP
   */
  public record AuthEventData(
      String eventType,
      String authMethod,
      String username,
      String externalIssuer,
      String externalSubject,
      String roles,
      String failureReason,
      String sourceIp) {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图（镜像 AuthzEventRecorder）。")
  public AuthEventRecorder(AuthEventRepository repository) {
    this.repository = repository;
  }

  /** 记录一次认证事件；失败吞掉并记 ERROR（绝不阻断主链路）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "exception toString for diagnostics only; audit failure must not block login flow.")
  public void record(AuthEventData data) {
    if (data == null || data.eventType() == null || data.authMethod() == null) {
      return;
    }
    try {
      AuthEvent event = new AuthEvent();
      event.setEventType(data.eventType());
      event.setAuthMethod(data.authMethod());
      event.setUsername(truncate(data.username(), MAX_USERNAME));
      event.setExternalIssuer(truncate(data.externalIssuer(), MAX_EXTERNAL));
      event.setExternalSubject(truncate(data.externalSubject(), MAX_EXTERNAL));
      event.setRoles(truncate(data.roles(), MAX_ROLES));
      event.setFailureReason(truncate(data.failureReason(), MAX_REASON));
      event.setSourceIp(truncate(data.sourceIp(), MAX_IP));
      String trace = MDC.get(TRACE_MDC_KEY);
      if (trace != null && !trace.isBlank()) {
        event.setTraceId(truncate(trace, 64));
      }
      repository.save(event);
    } catch (RuntimeException ex) {
      LOG.error("auth_events 写入失败（登录主链路不回滚）：{}", ex.toString());
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
