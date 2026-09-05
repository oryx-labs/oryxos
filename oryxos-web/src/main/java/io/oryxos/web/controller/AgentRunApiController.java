package io.oryxos.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentRunEvent;
import io.oryxos.core.agent.AgentRunEventStore;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AgentRunEventPage;
import io.oryxos.web.controller.dto.AgentRunEventView;
import io.oryxos.web.controller.dto.AgentRunView;
import io.oryxos.web.controller.dto.CreateRunRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Run 查询 / 创建 / 取消。现有 trigger 仍可用，创建的 execution 与 Run ID 相同。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design; collaborators are shared singletons.")
@RestController
@RequestMapping("/api/v1/runs")
public class AgentRunApiController {

  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;
  private static final int DEFAULT_LIST_LIMIT = 100;
  private static final int MAX_LIST_LIMIT = 200;
  private static final int DEFAULT_EVENT_LIMIT = 500;
  private static final int MAX_EVENT_LIMIT = 500;
  private static final String CONSOLE_CHANNEL = "admin";
  private static final String CONSOLE_USER = "console";
  private static final String TRIGGER_SOURCE_MANUAL = "manual";
  private static final Set<String> STATUSES =
      Set.of("QUEUED", "RUNNING", "CANCELLING", "SUCCESS", "FAILED", "CANCELLED");

  private final AgentExecutionService executionService;
  private final AgentRunEventStore eventStore;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final ObjectMapper objectMapper;

  public AgentRunApiController(
      AgentExecutionService executionService,
      AgentRunEventStore eventStore,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      ObjectMapper objectMapper) {
    this.executionService = executionService;
    this.eventStore = eventStore;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ApiResponse<AgentRunView> create(@RequestBody CreateRunRequest req) {
    String agentName = req == null ? null : req.agentName();
    String content = req == null ? null : req.content();
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("agentName 不能为空");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("消息为空");
    }
    if (content.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限");
    }
    requireAgent(agentName);
    Session session = sessionManager.getOrCreate(CONSOLE_CHANNEL, CONSOLE_USER, agentName);
    long id =
        executionService.triggerAsync(
            agentName,
            TRIGGER_SOURCE_MANUAL,
            session.sessionId(),
            content,
            () -> agentService.process(session, content));
    return ApiResponse.ok(toView(requireRun(id)));
  }

  @GetMapping
  public ApiResponse<List<AgentRunView>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit) {
    if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
      throw new IllegalArgumentException("不支持的 status: " + status);
    }
    int size = clamp(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
    return ApiResponse.ok(
        executionService.listRecent(status, size).stream().map(this::toView).toList());
  }

  @GetMapping("/{runId}")
  public ApiResponse<AgentRunView> get(@PathVariable long runId) {
    return ApiResponse.ok(toView(requireRun(runId)));
  }

  @GetMapping("/{runId}/events")
  public ApiResponse<AgentRunEventPage> events(
      @PathVariable long runId,
      @RequestParam(required = false, defaultValue = "0") long after,
      @RequestParam(required = false) Integer limit) {
    requireRun(runId);
    int size = clamp(limit, DEFAULT_EVENT_LIMIT, MAX_EVENT_LIMIT);
    List<AgentRunEvent> rows = eventStore.readAfter(runId, after, size + 1);
    boolean hasMore = rows.size() > size;
    List<AgentRunEventView> page =
        (hasMore ? rows.subList(0, size) : rows).stream().map(this::toEventView).toList();
    long nextAfter = page.isEmpty() ? after : page.get(page.size() - 1).sequence();
    return ApiResponse.ok(new AgentRunEventPage(page, hasMore, nextAfter));
  }

  @PostMapping("/{runId}/cancel")
  public ApiResponse<AgentRunView> cancel(@PathVariable long runId) {
    requireRun(runId);
    return ApiResponse.ok(toView(executionService.cancel(runId)));
  }

  private AgentRunView toView(AgentExecution execution) {
    return AgentRunView.from(execution, eventStore.lastSequence(execution.id()));
  }

  private AgentRunEventView toEventView(AgentRunEvent event) {
    return AgentRunEventView.from(event, parsePayload(event.payloadJson()));
  }

  private JsonNode parsePayload(String json) {
    try {
      return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (JsonProcessingException e) {
      return objectMapper.createObjectNode();
    }
  }

  private AgentExecution requireRun(long runId) {
    return executionService
        .findById(runId)
        .orElseThrow(() -> new ResourceNotFoundException("Run 不存在: " + runId));
  }

  private void requireAgent(String name) {
    if (profileRegistry.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
  }

  private static int clamp(Integer limit, int defaultValue, int max) {
    if (limit == null) {
      return defaultValue;
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit 必须为正整数");
    }
    return Math.min(limit, max);
  }
}
