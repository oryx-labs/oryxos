package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** agent_run_events：一次 Run 的按序展示事件。表结构以手工 schema.sql 为唯一权威。 */
@Entity
@Table(name = "agent_run_events")
public class AgentRunEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(nullable = false)
  private Long sequence;

  @Column(nullable = false)
  private String type;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "payload_json", nullable = false)
  private String payloadJson;

  public Long getId() {
    return id;
  }

  public Long getRunId() {
    return runId;
  }

  public void setRunId(Long runId) {
    this.runId = runId;
  }

  public Long getSequence() {
    return sequence;
  }

  public void setSequence(Long sequence) {
    this.sequence = sequence;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getPayloadJson() {
    return payloadJson;
  }

  public void setPayloadJson(String payloadJson) {
    this.payloadJson = payloadJson;
  }
}
