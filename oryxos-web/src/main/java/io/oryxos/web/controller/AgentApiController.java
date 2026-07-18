package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.MessageRequest;
import io.oryxos.web.controller.dto.MessageResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 无状态调用：对某个 Agent 发一条消息、跑完返回。走跟会话发消息、CLI 完全一样的 {@link AgentService#process} 同一入口。29/30 节在本
 * Controller 上加 Agent 定义/管理的 CRUD——本节只此一个端点。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. profileRegistry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;
  private static final String INVOKE_CHANNEL = "invoke";
  private static final String DEFAULT_USER = "default";

  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;

  public AgentApiController(
      AgentService agentService, SessionManager sessionManager, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
  }

  @PostMapping("/{name}/invoke")
  public ApiResponse<MessageResponse> invoke(
      @PathVariable String name, @RequestBody MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    // 先查 Agent 存在，给 404（否则 AgentService 内部会以 IllegalState→503 报，语义不对）
    if (profileRegistry.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name); // → 404
    }
    Session session = sessionManager.getOrCreate(INVOKE_CHANNEL, DEFAULT_USER, name);
    String reply = agentService.process(session, req.content());
    return ApiResponse.ok(new MessageResponse(reply));
  }
}
