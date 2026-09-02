package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateSessionRequest;
import io.oryxos.web.controller.dto.MessageRequest;
import io.oryxos.web.controller.dto.MessageResponse;
import io.oryxos.web.controller.dto.SessionStatsView;
import io.oryxos.web.controller.dto.SessionSummaryView;
import io.oryxos.web.controller.dto.SessionView;
import io.oryxos.web.error.SessionNotFoundException;
import io.oryxos.web.sse.SseStreamSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理四端点。发消息走跟 CLI 完全一样的 {@link AgentService#process} 同一入口；Controller 只做校验/包装/兜错， 不夹带业务逻辑。会话身份
 * channel 固定 "web"（人推的另一入口，与 CLI 共享同一份会话存储）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase; AgentService/SessionManager 为 Runtime 单例共享引用")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

  /** 单条消息上限（课件防呆位）。 */
  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;

  /** 历史返回最多最近 N 条（课件防呆位）。 */
  private static final int MAX_HISTORY = 100;

  /** 会话列表默认返回最近 N 条摘要。 */
  private static final int MAX_LIST = 100;

  private static final String WEB_CHANNEL = "web";
  private static final String DEFAULT_USER = "default";

  private final AgentService agentService;
  private final SessionManager sessionManager;

  /** SSE 编排（019）：默认实例保 telescoping 构造与测试直构可用，运行时由 @Autowired setter 覆盖为容器单例。 */
  private SseStreamSupport sseStreamSupport = SseStreamSupport.defaultSupport();

  @org.springframework.beans.factory.annotation.Autowired
  public void setSseStreamSupport(SseStreamSupport sseStreamSupport) {
    this.sseStreamSupport = sseStreamSupport;
  }

  public SessionApiController(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 创建会话：绑定 Profile（+ 可选用户），返回会话标识。 */
  @PostMapping
  public ApiResponse<Map<String, String>> create(@RequestBody CreateSessionRequest req) {
    if (req == null || req.profile() == null || req.profile().isBlank()) {
      throw new IllegalArgumentException("创建会话缺少 profile"); // → 400
    }
    String userId = req.userId() == null || req.userId().isBlank() ? DEFAULT_USER : req.userId();
    Session session = sessionManager.getOrCreate(WEB_CHANNEL, userId, req.profile());
    return ApiResponse.ok(Map.of("sessionId", session.sessionId()));
  }

  /**
   * 发消息：触发一次完整 ReAct（与 oryxos chat 同一入口）。019：Accept 含 text/event-stream 时走 SSE 流式 （校验前置于流开始，失败仍返
   * JSON 状态码，FR-009）；否则一次性 JSON 路径零改动（FR-001 回归零破坏）。
   */
  @PostMapping("/{id}/messages")
  public ApiResponse<MessageResponse> send(
      @PathVariable String id,
      @RequestBody MessageRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    String content = requireContent(req);
    Session session =
        sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id)); // → 404
    // 021：controller 先 open 拿 ID 回传调用方；AgentService 兜底 openIfAbsent 复用同一 ID
    try (TraceContext.Scope traceScope = TraceContext.openIfAbsent()) {
      if (SseStreamSupport.wantsEventStream(request)) {
        sseStreamSupport.stream(
            response, listener -> agentService.process(session, content, listener));
        return null; // 响应已由 SSE 流写出并提交（trace 事件由 SseStreamSupport 发出）
      }
      String reply = agentService.process(session, content); // 同一编排入口；审计在 process 内
      return ApiResponse.ok(new MessageResponse(reply, traceScope.traceId()));
    }
  }

  /** 列出会话摘要（最近 ≤100 条、按活跃倒序）；可选 {@code ?status=active} 过滤。三面同源里的"列表视图"。 */
  @GetMapping
  public ApiResponse<List<SessionSummaryView>> list(
      @RequestParam(name = "status", required = false) String status) {
    List<SessionSummaryView> views =
        sessionManager.listRecent(MAX_LIST).stream()
            .filter(s -> status == null || status.isBlank() || status.equals(s.status()))
            .map(SessionSummaryView::from)
            .toList();
    return ApiResponse.ok(views);
  }

  /** 会话统计：返回活跃、归档、总计数量，供管理台概览页使用。 */
  @GetMapping("/stats")
  public ApiResponse<SessionStatsView> stats() {
    io.oryxos.core.session.SessionStats s = sessionManager.stats();
    return ApiResponse.ok(new SessionStatsView(s.active(), s.archived(), s.total()));
  }

  /** 查历史：返回最近 ≤100 条。 */
  @GetMapping("/{id}")
  public ApiResponse<SessionView> history(@PathVariable String id) {
    Session session = sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id));
    List<Message> all = session.messages();
    List<Message> recent =
        all.size() > MAX_HISTORY
            ? List.copyOf(all.subList(all.size() - MAX_HISTORY, all.size()))
            : List.copyOf(all);
    return ApiResponse.ok(new SessionView(session.sessionId(), session.profileName(), recent));
  }

  /** 归档：不存在 → 404。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Map<String, Boolean>> archive(@PathVariable String id) {
    if (!sessionManager.archive(id)) {
      throw new SessionNotFoundException(id); // → 404
    }
    return ApiResponse.ok(Map.of("archived", true));
  }

  private static String requireContent(MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    return req.content();
  }
}
