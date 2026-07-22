package io.oryxos.web.auth;

import io.oryxos.web.config.AdminSecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Local single-administrator identity source backed by environment-provided configuration. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "AdminSecurityProperties is a Spring-managed configuration bean; "
            + "constructor injection intentionally keeps the shared validated reference.")
public class LocalAdminIdentityService {

  private final AdminSecurityProperties properties;
  private final PasswordEncoder passwordEncoder;

  public LocalAdminIdentityService(
      AdminSecurityProperties properties, PasswordEncoder passwordEncoder) {
    this.properties = properties;
    this.passwordEncoder = passwordEncoder;
  }

  public boolean configured() {
    return properties.configured();
  }

  public String username() {
    return properties.normalizedUsername();
  }

  public boolean matches(String submittedUsername, String submittedPassword) {
    if (!configured()
        || submittedUsername == null
        || submittedPassword == null
        || !username().equals(submittedUsername.trim())) {
      return false;
    }
    try {
      return passwordEncoder.matches(submittedPassword, properties.getPasswordHash().trim());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
