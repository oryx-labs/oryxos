package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** agent_executions：Agent 维度每次执行的历史（第 32 节）——表结构以 db/migration 迁移目录为唯一权威。 */
@Entity
@Table(name = "agent_executions")
public class AgentExecutionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "agent_name", nullable = false)
  private String agentName;

  @Column(nullable = false)
  private String source;

  @Column(name = "session_id")
  private String sessionId;

  /** 单轮处理串联标识（021）：触发时主线程生成，与本轮审计记录同值；升级前旧行为 null。 */
  @Column(name = "trace_id")
  private String traceId;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "input_preview")
  private String inputPreview;

  @Column(name = "cancel_requested_at")
  private Instant cancelRequestedAt;

  @Column(name = "status")
  private String status;

  @Column(name = "stop_reason")
  private String stopReason;

  public Long getId() {
    return id;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getInputPreview() {
    return inputPreview;
  }

  public void setInputPreview(String inputPreview) {
    this.inputPreview = inputPreview;
  }

  public Instant getCancelRequestedAt() {
    return cancelRequestedAt;
  }

  public void setCancelRequestedAt(Instant cancelRequestedAt) {
    this.cancelRequestedAt = cancelRequestedAt;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getStopReason() {
    return stopReason;
  }

  public void setStopReason(String stopReason) {
    this.stopReason = stopReason;
  }
}
