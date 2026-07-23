package io.oryxos.web;

import io.oryxos.core.profile.ProfileValidationException;
import io.oryxos.core.skill.SkillConflictException;
import io.oryxos.core.skill.SkillImportException;
import io.oryxos.core.skill.SkillPackageTooLargeException;
import io.oryxos.core.skill.SkillValidationException;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.AgentTimeoutException;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.error.SessionNotFoundException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates uncaught exceptions into the unified {@link ApiResponse} error envelope so clients
 * always receive a predictable JSON body with a stable error code.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 400 — malformed or invalid request arguments（含 AGENT.md 定义非法：ProfileValidationException）。 */
  @ExceptionHandler({
    IllegalArgumentException.class,
    ProfileValidationException.class,
    SkillValidationException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException ex) {
    LOG.warn("Bad request: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  /** 400 — malformed JSON is rejected without echoing parser input or internal details. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleMalformedJson(
      HttpMessageNotReadableException ignored) {
    LOG.warn("Malformed JSON request");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Malformed JSON request"));
  }

  /** 404 — no handler or static resource matched the request. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
    LOG.warn("Not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "Resource not found"));
  }

  /** 404 — 领域资源（会话 / Agent 等）不存在。消息可读、点名资源。 */
  @ExceptionHandler({
    SessionNotFoundException.class,
    ResourceNotFoundException.class,
    NoSuchElementException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(RuntimeException ex) {
    LOG.warn("Resource not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
  }

  /** 409 — an active, disabled, invalid or unmanaged directory already owns the Skill name. */
  @ExceptionHandler(SkillConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleSkillConflict(SkillConflictException ex) {
    LOG.warn("Skill import conflict: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.error(HttpStatus.CONFLICT.value(), ex.getMessage()));
  }

  /** 413 — core's authoritative streaming limits rejected the archive or expanded package. */
  @ExceptionHandler(SkillPackageTooLargeException.class)
  public ResponseEntity<ApiResponse<Void>> handleSkillPackageTooLarge(
      SkillPackageTooLargeException ex) {
    LOG.warn("Skill package exceeds core limit: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), ex.getMessage()));
  }

  /** 400 — ZIP shape, entry, path, metadata or content validation was rejected by core. */
  @ExceptionHandler(SkillImportException.class)
  public ResponseEntity<ApiResponse<Void>> handleSkillImport(SkillImportException ex) {
    LOG.warn("Invalid Skill package: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  /** 413 — servlet multipart guard rejected the upload before core was invoked. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(
      MaxUploadSizeExceededException ignored) {
    LOG.warn("Skill upload exceeds multipart limit");
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            ApiResponse.error(
                HttpStatus.PAYLOAD_TOO_LARGE.value(), "Skill package exceeds upload limits"));
  }

  /** 503 — an explicitly classified model provider dependency is unavailable. */
  @ExceptionHandler(ProviderUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnavailable(ProviderUnavailableException ex) {
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
    LOG.atError()
        .addKeyValue("event", "web.request.failed")
        .addKeyValue("exceptionType", sanitize(ex.getClass().getSimpleName()))
        .log("Unhandled request failure");
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error"));
  }

  /** Remove unsafe Unicode controls and cap attacker-controlled log fields (CWE-117). */
  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder safe = new StringBuilder(Math.min(value.length(), 256));
    value
        .codePoints()
        .limit(256)
        .forEach(
            codePoint -> {
              int type = Character.getType(codePoint);
              if (Character.isISOControl(codePoint)
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR) {
                safe.append('_');
              } else {
                safe.appendCodePoint(codePoint);
              }
            });
    return safe.toString();
  }
}
