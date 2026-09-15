package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * OIDC 授权流程临时状态（040）：state 主键，携带 nonce 与 PKCE verifier。表结构以 db/migration V10 为唯一权威。
 *
 * <p>多副本共享事实源：登录发起与回调可落不同副本（FR-008）。消费走 {@link OidcAuthRequestRepository#consume} 的原子
 * UPDATE（CAS）——同 state 重放第二次必然失败。TTL 10 分钟，惰性清理（{@link OidcAuthRequestStore}）。
 */
@Entity
@Table(name = "oidc_auth_requests")
public class OidcAuthRequestEntity {

  @Id
  @Column(name = "state", nullable = false, length = 64)
  private String state;

  @Column(name = "nonce", nullable = false, length = 64)
  private String nonce;

  @Column(name = "pkce_verifier", nullable = false, length = 128)
  private String pkceVerifier;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getNonce() {
    return nonce;
  }

  public void setNonce(String nonce) {
    this.nonce = nonce;
  }

  public String getPkceVerifier() {
    return pkceVerifier;
  }

  public void setPkceVerifier(String pkceVerifier) {
    this.pkceVerifier = pkceVerifier;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public void setConsumedAt(Instant consumedAt) {
    this.consumedAt = consumedAt;
  }
}
