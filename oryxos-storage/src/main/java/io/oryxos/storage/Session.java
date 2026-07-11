package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * sessions 审计/持久化记录——表结构以手工 schema.sql 为唯一权威。
 *
 * <p>与 core 的领域对象 {@code io.oryxos.core.session.Session} 同名不同包：本类只管持久化形态， 领域行为（消息累积）在 core；两者互转由
 * {@link JpaSessionManager} 负责。
 */
@Entity
@Table(name = "sessions")
public class Session {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(nullable = false)
  private String channel;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "messages_json")
  private String messagesJson;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_active_at")
  private Instant lastActiveAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (status == null) {
      status = "active";
    }
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  public void setMessagesJson(String messagesJson) {
    this.messagesJson = messagesJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public void setLastActiveAt(Instant lastActiveAt) {
    this.lastActiveAt = lastActiveAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(Instant archivedAt) {
    this.archivedAt = archivedAt;
  }
}
