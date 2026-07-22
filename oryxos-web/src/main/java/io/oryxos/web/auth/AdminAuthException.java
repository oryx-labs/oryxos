package io.oryxos.web.auth;

import org.springframework.http.HttpStatus;

/** Auth-specific exception carrying the HTTP status that should be returned to the client. */
public class AdminAuthException extends RuntimeException {

  private final HttpStatus status;

  public AdminAuthException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static AdminAuthException unauthorized() {
    return new AdminAuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
  }

  public static AdminAuthException locked() {
    return new AdminAuthException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts");
  }

  public static AdminAuthException unconfigured() {
    return new AdminAuthException(
        HttpStatus.SERVICE_UNAVAILABLE, "Admin credentials are not configured");
  }
}
