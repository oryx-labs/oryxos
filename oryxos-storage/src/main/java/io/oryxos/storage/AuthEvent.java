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
 * 认证事件审计（040）：登录成功/失败、登出、映射变更。表结构以 db/migration V10 为唯一权威。
 *
 * <p>追加型——无更新/删除业务路径。与 {@link AuthzEvent}（授权拒绝）分表，避免语义混用。
 */
@Entity
@Table(name = "auth_events")
public class AuthEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_type", nullable = false, length = 32)
  private String eventType;

  @Column(name = "principal_id", length = 128)
  private String principalId;

  @Column(length = 1024)
  private String detail;

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

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
  }

  public String getDetail() {
    return detail;
  }

  public void setDetail(String detail) {
    this.detail = detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
