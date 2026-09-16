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
 * 资产治理变更审计（041）：只记侧车写入，不记每次 decide。表结构以 db/migration V11 为唯一权威。
 *
 * <p>追加型——无更新/删除业务路径。与 {@link AuthzEvent}（拒绝）分表，避免把变更流水写成拒绝流水。
 */
@Entity
@Table(name = "asset_governance_events")
public class AssetGovernanceEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 128)
  private String actor;

  @Column(name = "resource_type", nullable = false, length = 32)
  private String resourceType;

  @Column(name = "resource_id", nullable = false, length = 255)
  private String resourceId;

  @Column(name = "change_summary", nullable = false, length = 512)
  private String changeSummary;

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

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public void setResourceId(String resourceId) {
    this.resourceId = resourceId;
  }

  public String getChangeSummary() {
    return changeSummary;
  }

  public void setChangeSummary(String changeSummary) {
    this.changeSummary = changeSummary;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
