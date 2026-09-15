package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 外部身份映射（040 FR-004）：IdP {@code iss+sub} ↔ OryxOS 用户的持久关联。表结构以 db/migration V10 为唯一权威。
 *
 * <p>锚点是 {@code (issuer, subject)} 唯一键——同一外部身份恒映射同一用户；用户名只在首登派生一次， IdP 侧改名/改邮箱不影响映射。email
 * 仅作展示与排查，不参与身份判定。
 */
@Entity
@Table(name = "oidc_identities")
public class OidcIdentity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "issuer", nullable = false, length = 255)
  private String issuer;

  @Column(name = "subject", nullable = false, length = 255)
  private String subject;

  @Column(name = "username", nullable = false, length = 64)
  private String username;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "first_login_at", nullable = false)
  private Instant firstLoginAt;

  @Column(name = "last_login_at", nullable = false)
  private Instant lastLoginAt;

  public Long getId() {
    return id;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public Instant getFirstLoginAt() {
    return firstLoginAt;
  }

  public void setFirstLoginAt(Instant firstLoginAt) {
    this.firstLoginAt = firstLoginAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }
}
