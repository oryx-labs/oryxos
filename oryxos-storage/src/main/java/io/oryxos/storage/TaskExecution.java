package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** task_executions：定时任务每次执行的历史（28 节，成功失败都记）——表结构以手工 schema.sql 为唯一权威。 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "schedule_id")
  private String scheduleId;

  @Column(name = "legacy_task_key")
  private String legacyTaskKey;

  @Column(name = "legacy_migrated", nullable = false)
  private boolean legacyMigrated;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  public Long getId() {
    return id;
  }

  public String getScheduleId() {
    return scheduleId;
  }

  public void setScheduleId(String scheduleId) {
    this.scheduleId = scheduleId;
  }

  public String getLegacyTaskKey() {
    return legacyTaskKey;
  }

  public void setLegacyTaskKey(String legacyTaskKey) {
    this.legacyTaskKey = legacyTaskKey;
  }

  public boolean isLegacyMigrated() {
    return legacyMigrated;
  }

  public void setLegacyMigrated(boolean legacyMigrated) {
    this.legacyMigrated = legacyMigrated;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }
}
