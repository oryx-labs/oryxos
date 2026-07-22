package io.oryxos.web.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the local administrator login protecting the management API. */
@ConfigurationProperties(prefix = "oryxos.admin")
public class AdminSecurityProperties {

  private String username;
  private String passwordHash;
  private Duration sessionIdleTimeout = Duration.ofMinutes(30);
  private boolean secureCookie = true;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Duration getSessionIdleTimeout() {
    return sessionIdleTimeout;
  }

  public void setSessionIdleTimeout(Duration sessionIdleTimeout) {
    this.sessionIdleTimeout =
        sessionIdleTimeout == null ? Duration.ofMinutes(30) : sessionIdleTimeout;
  }

  public boolean isSecureCookie() {
    return secureCookie;
  }

  public void setSecureCookie(boolean secureCookie) {
    this.secureCookie = secureCookie;
  }

  public boolean configured() {
    return hasText(username) && hasText(passwordHash) && passwordHash.trim().startsWith("{");
  }

  public String normalizedUsername() {
    return username == null ? "" : username.trim();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
