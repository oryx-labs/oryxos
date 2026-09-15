package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 认证事件审计（040 US3）：登录成功/失败、登出、身份映射建立、角色变化。表结构以 db/migration V10 为唯一权威。
 *
 * <p>与 {@link AuthzEvent}（授权拒绝）分族分表——认证是生命周期事件，授权是裁决事件。追加型审计， 无更新/删除路径；写失败不得阻断登录主链路（见 {@link
 * AuthEventRecorder}）。字段绝不含令牌内容。
 */
@Entity
@Table(name = "auth_events")
public class AuthEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_type", nullable = false, length = 32)
  private String eventType;

  @Column(name = "auth_method", nullable = false, length = 16)
  private String authMethod;

  @Column(name = "username", length = 64)
  private String username;

  @Column(name = "external_issuer", length = 255)
  private String externalIssuer;

  @Column(name = "external_subject", length = 255)
  private String externalSubject;

  @Column(name = "roles", length = 255)
  private String roles;

  @Column(name = "failure_reason", length = 64)
  private String failureReason;

  @Column(name = "source_ip", length = 64)
  private String sourceIp;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getAuthMethod() {
    return authMethod;
  }

  public void setAuthMethod(String authMethod) {
    this.authMethod = authMethod;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getExternalIssuer() {
    return externalIssuer;
  }

  public void setExternalIssuer(String externalIssuer) {
    this.externalIssuer = externalIssuer;
  }

  public String getExternalSubject() {
    return externalSubject;
  }

  public void setExternalSubject(String externalSubject) {
    this.externalSubject = externalSubject;
  }

  public String getRoles() {
    return roles;
  }

  public void setRoles(String roles) {
    this.roles = roles;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public String getSourceIp() {
    return sourceIp;
  }

  public void setSourceIp(String sourceIp) {
    this.sourceIp = sourceIp;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
