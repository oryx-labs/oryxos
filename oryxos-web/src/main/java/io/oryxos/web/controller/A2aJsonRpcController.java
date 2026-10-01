package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aMessageService;
import io.oryxos.web.config.WebSseProperties;
import io.oryxos.web.sse.SseWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A JSON-RPC endpoint (v0.3 {@code message/send} + {@code message/stream}). Bare JSON-RPC — not
 * ApiResponse wrapper. Stream uses sync {@link SseWriter} (constitution VII).
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "Spring MVC injects message service / SSE props; A2A JSON-RPC is intentionally public.")
@RestController
@RequestMapping("/api/v1/a2a")
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，四个端点全 404）。
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "oryxos.a2a",
    name = "enabled",
    havingValue = "true")
public class A2aJsonRpcController {

  private final A2aMessageService messageService;
  private final WebSseProperties sseProperties;

  public A2aJsonRpcController(A2aMessageService messageService, WebSseProperties sseProperties) {
    this.messageService = Objects.requireNonNull(messageService, "messageService");
    this.sseProperties = Objects.requireNonNull(sseProperties, "sseProperties");
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
  public ObjectNode handle(@RequestBody JsonNode body, HttpServletResponse response) {
    String method = body != null && body.hasNonNull("method") ? body.get("method").asText("") : "";
    if (A2aMessageService.METHOD_MESSAGE_STREAM.equals(method)) {
      Optional<ObjectNode> early = messageService.validateStream(body);
      if (early.isPresent()) {
        return early.get();
      }
      try (SseWriter writer = new SseWriter(response, sseProperties.getHeartbeatSeconds())) {
        messageService.emitStream(body, frame -> writer.event("message", frame.toString()));
      } catch (IOException e) {
        throw new UncheckedIOException("failed to open A2A SSE stream", e);
      }
      return null;
    }
    return messageService.handle(body);
  }
}
