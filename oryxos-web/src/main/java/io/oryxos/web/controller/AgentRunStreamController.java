package io.oryxos.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentRunEvent;
import io.oryxos.core.agent.AgentRunEventHub;
import io.oryxos.core.agent.AgentRunEventStore;
import io.oryxos.core.agent.AgentRunEventTypes;
import io.oryxos.web.controller.dto.AgentRunEventView;
import io.oryxos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC SSE：先补齐持久化事件，再等待新事件。心跳不写业务表。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "SSE 位于 Web 边界；共享 hub/store/executor 是有意的单例。")
@RestController
@RequestMapping("/api/v1/runs")
public class AgentRunStreamController {

  private static final long TIMEOUT_MS = 30 * 60 * 1000L;
  private static final long HEARTBEAT_SECONDS = 15;
  private static final int REPLAY_BATCH_SIZE = 500;
  static final int LIVE_QUEUE_CAPACITY = 256;

  private final AgentExecutionService executionService;
  private final AgentRunEventStore eventStore;
  private final AgentRunEventHub eventHub;
  private final ExecutorService executor;
  private final ObjectMapper objectMapper;

  public AgentRunStreamController(
      AgentExecutionService executionService,
      AgentRunEventStore eventStore,
      AgentRunEventHub eventHub,
      @Qualifier("agentRunStreamExecutor") ExecutorService agentRunStreamExecutor,
      ObjectMapper objectMapper) {
    this.executionService = executionService;
    this.eventStore = eventStore;
    this.eventHub = eventHub;
    this.executor = agentRunStreamExecutor;
    this.objectMapper = objectMapper;
  }

  @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable long runId,
      @RequestParam(required = false, defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    if (executionService.findById(runId).isEmpty()) {
      throw new ResourceNotFoundException("Run 不存在: " + runId);
    }
    long cursor = Math.max(after, parseLastEventId(lastEventId));
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    AtomicBoolean closed = new AtomicBoolean(false);
    executor.execute(() -> push(runId, cursor, emitter, closed));
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(error -> closed.set(true));
    return emitter;
  }

  static long parseLastEventId(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(lastEventId.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private void push(long runId, long after, SseEmitter emitter, AtomicBoolean closed) {
    BlockingQueue<AgentRunEvent> incoming = new LinkedBlockingQueue<>(LIVE_QUEUE_CAPACITY);
    AtomicBoolean overflowed = new AtomicBoolean(false);
    try (AutoCloseable ignored =
        eventHub.subscribe(
            runId,
            event -> {
              if (!incoming.offer(event)) {
                overflowed.set(true);
              }
            })) {
      long cursor = after;
      while (!closed.get()) {
        java.util.List<AgentRunEvent> batch =
            eventStore.readAfter(runId, cursor, REPLAY_BATCH_SIZE);
        if (batch.isEmpty()) {
          break;
        }
        for (AgentRunEvent event : batch) {
          if (closed.get()) {
            return;
          }
          send(emitter, event);
          cursor = event.sequence();
          if (AgentRunEventTypes.isTerminal(event.type())) {
            emitter.complete();
            return;
          }
        }
        if (batch.size() < REPLAY_BATCH_SIZE) {
          break;
        }
      }
      while (!closed.get()) {
        if (overflowed.get()) {
          emitter.completeWithError(new IllegalStateException("SSE live queue overflow"));
          return;
        }
        AgentRunEvent next = incoming.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        if (closed.get()) {
          return;
        }
        if (next == null) {
          emitter.send(SseEmitter.event().comment("keepalive"));
          continue;
        }
        if (next.sequence() <= cursor) {
          continue;
        }
        send(emitter, next);
        cursor = next.sequence();
        if (AgentRunEventTypes.isTerminal(next.type())) {
          emitter.complete();
          return;
        }
      }
    } catch (Exception e) {
      if (!closed.get()) {
        emitter.completeWithError(e);
      }
    }
  }

  private void send(SseEmitter emitter, AgentRunEvent event) throws IOException {
    AgentRunEventView view = AgentRunEventView.from(event, parsePayload(event.payloadJson()));
    emitter.send(
        SseEmitter.event()
            .id(String.valueOf(event.sequence()))
            .name(event.type())
            .data(view, MediaType.APPLICATION_JSON));
  }

  private JsonNode parsePayload(String json) {
    try {
      return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (JsonProcessingException e) {
      return objectMapper.createObjectNode();
    }
  }
}
