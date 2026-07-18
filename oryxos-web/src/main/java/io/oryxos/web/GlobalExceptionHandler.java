package io.oryxos.web;

import io.oryxos.core.profile.ProfileValidationException;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.AgentTimeoutException;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.error.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates uncaught exceptions into the unified {@link ApiResponse} error envelope so clients
 * always receive a predictable JSON body with a stable error code.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 400 — malformed or invalid request arguments（含 AGENT.md 定义非法：ProfileValidationException）。 */
  @ExceptionHandler({IllegalArgumentException.class, ProfileValidationException.class})
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException ex) {
    LOG.warn("Bad request: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  /** 404 — no handler or static resource matched the request. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
    LOG.warn("Not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "Resource not found"));
  }

  /** 404 — 领域资源（会话 / Agent 等）不存在。消息可读、点名资源。 */
  @ExceptionHandler({SessionNotFoundException.class, ResourceNotFoundException.class})
  public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(RuntimeException ex) {
    LOG.warn("Resource not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
  }

  /** 503 — a downstream dependency (provider, tool, storage) is unavailable. */
  @ExceptionHandler({IllegalStateException.class, ProviderUnavailableException.class})
  public ResponseEntity<ApiResponse<Void>> handleUnavailable(RuntimeException ex) {
    LOG.error("Service unavailable: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
  }

  /** 504 — Agent 调用超过 60 秒上限。 */
  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ApiResponse<Void>> handleTimeout(AgentTimeoutException ex) {
    LOG.error("Agent call timed out: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ApiResponse.error(HttpStatus.GATEWAY_TIMEOUT.value(), ex.getMessage()));
  }

  /** 500 — catch-all for everything else. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleInternalError(Exception ex) {
    LOG.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error"));
  }

  /** Strip CR/LF so attacker-controlled values cannot forge log lines (CWE-117). */
  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("[\r\n]", "_");
  }
}
