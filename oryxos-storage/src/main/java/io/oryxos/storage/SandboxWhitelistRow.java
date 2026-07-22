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
 * Sandbox 白名单持久化记录——表结构以手工 schema.sql 为唯一权威。(category, entry_value) 唯一；entry_value 存"入内存的规范形"（FILE
 * 为归一后的绝对路径），由 WhitelistSandbox 写穿前算好。
 */
@Entity
@Table(name = "sandbox_whitelist")
public class SandboxWhitelistRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String category; // FILE / SHELL / HTTP

  @Column(name = "entry_value", nullable = false)
  private String entryValue;

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

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getEntryValue() {
    return entryValue;
  }

  public void setEntryValue(String entryValue) {
    this.entryValue = entryValue;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
