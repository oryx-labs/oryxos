package io.oryxos.web;

import io.oryxos.core.profile.ProfileValidationException;
import io.oryxos.core.session.SessionUpdateConflictException;
import io.oryxos.core.skill.SkillReferencedException;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.SkillReferenceConflictView;
import io.oryxos.web.error.AgentTimeoutException;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.error.ScheduleKeyAmbiguityException;
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

  /**
   * 400 — malformed or invalid request arguments（含 AGENT.md
   * 定义非法：ProfileValidationException；文档导入同步校验失败：KnowledgeImportException）。
   */
  @ExceptionHandler({
    IllegalArgumentException.class,
    ProfileValidationException.class,
    io.oryxos.core.knowledge.KnowledgeImportException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException ex) {
    LOG.warn("Bad request: {}", sanitize(ex.getMessage()));
    // 显式 JSON content-type（019 FR-009）：客户端 Accept 只有 text/event-stream 时（SSE 流式调用的
    // 流前失败）内容协商会失败并把原异常重新抛出——预设具体 content-type 跳过协商，错误 JSON 任何 Accept 都可达。
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  /** 400 — Spring MVC 层的客户端错误：请求体缺失/JSON 语法错、参数类型不匹配、缺必填参数。 */
  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleClientError(Exception ex) {
    // 这类异常此前落进 catch-all 变成 500 + ERROR 堆栈——客户端错误不应污染服务端告警
    LOG.warn("Client error: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Bad request"));
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
    // 显式 JSON content-type：同 handleBadRequest——SSE 流前失败（FR-009）需在 Accept: text/event-stream 下可达
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
  }

  /** 503 — a downstream dependency (provider, tool, storage) is unavailable. */
  @ExceptionHandler({IllegalStateException.class, ProviderUnavailableException.class})
  public ResponseEntity<ApiResponse<Void>> handleUnavailable(RuntimeException ex) {
    LOG.error("Service unavailable: {}", sanitize(ex.getMessage()));
    // 显式 JSON content-type：同 handleBadRequest——SSE 流前失败（FR-009）需在 Accept: text/event-stream 下可达
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
  }

  /** 409 — 删除知识库被 Agent 引用保护拦下（FR-011）：携带引用 Agent 清单。 */
  @ExceptionHandler(io.oryxos.core.knowledge.KnowledgeReferencedException.class)
  public ResponseEntity<ApiResponse<io.oryxos.web.controller.dto.KnowledgeReferenceConflictView>>
      handleKnowledgeReferenced(io.oryxos.core.knowledge.KnowledgeReferencedException ex) {
    io.oryxos.web.controller.dto.KnowledgeReferenceConflictView data =
        io.oryxos.web.controller.dto.KnowledgeReferenceConflictView.from(
            ex.kbName(), ex.references());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiResponse<>(HttpStatus.CONFLICT.value(), ex.getMessage(), data));
  }

  /** 409 — Skill archive is blocked by active or archived Agent references. */
  @ExceptionHandler(SkillReferencedException.class)
  public ResponseEntity<ApiResponse<SkillReferenceConflictView>> handleSkillReferenced(
      SkillReferencedException ex) {
    SkillReferenceConflictView data =
        SkillReferenceConflictView.from(ex.skillName(), ex.references());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiResponse<>(HttpStatus.CONFLICT.value(), ex.getMessage(), data));
  }

  /** 409 — 会话在执行期间已被另一请求更新，拒绝旧快照覆盖新历史。 */
  @ExceptionHandler(SessionUpdateConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleSessionUpdateConflict(
      SessionUpdateConflictException ex) {
    LOG.warn("Session update conflict: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.error(HttpStatus.CONFLICT.value(), ex.getMessage()));
  }

  /** 409 - a deprecated v1 schedule key identifies multiple schedules. */
  @ExceptionHandler(ScheduleKeyAmbiguityException.class)
  public ResponseEntity<ApiResponse<Void>> handleScheduleKeyAmbiguity(
      ScheduleKeyAmbiguityException ex) {
    LOG.warn("Ambiguous schedule key: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.error(HttpStatus.CONFLICT.value(), ex.getMessage()));
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
