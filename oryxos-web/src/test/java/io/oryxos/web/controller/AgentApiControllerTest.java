package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 端点切片：动态管理 CRUD 薄转发（冲突→400、不存在→404）；invoke 走 AgentService.process 同一入口。 */
class AgentApiControllerTest {

  private AgentLifecycleService lifecycle;
  private AgentService agentService;
  private SessionManager sessionManager;
  private MockMvc mvc;

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @BeforeEach
  void setUp() {
    lifecycle = mock(AgentLifecycleService.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    ProfileRegistry registry = new ProfileRegistry(Map.of("ops", profile("ops")));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(
                    lifecycle,
                    agentService,
                    sessionManager,
                    registry,
                    mock(io.oryxos.core.memory.MemoryService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create 成功_返回 AgentView")
  void create_success_returnsAgentView() throws Exception {
    when(lifecycle.create(eq("demo"), any())).thenReturn(profile("demo"));

    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"demo\",\"description\":\"一个测试 Agent\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"));
  }

  @Test
  @DisplayName("create name 冲突_返回400")
  void create_conflict_returns400() throws Exception {
    when(lifecycle.create(eq("dup"), any()))
        .thenThrow(new IllegalArgumentException("Agent 已存在: dup"));

    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"description\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("get 不存在_返回404")
  void get_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("delete 不存在_返回404、不触发删除")
  void delete_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(delete("/api/v1/agents/ghost")).andExpect(status().isNotFound());
    verify(lifecycle, never()).delete(any());
  }

  @Test
  @DisplayName("update 不存在_返回404")
  void update_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            put("/api/v1/agents/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":\"x\"}"))
        .andExpect(status().isNotFound());
    verify(lifecycle, never()).update(any(), any());
  }

  @Test
  @DisplayName("invoke 已存在 Agent_走编排入口返回回复")
  void invokeKnownAgent_callsProcess() throws Exception {
    Session session = new Session("invoke:default:ops", "ops");
    when(sessionManager.getOrCreate("invoke", "default", "ops")).thenReturn(session);
    when(agentService.process(eq(session), eq("查天气"))).thenReturn("晴");

    mvc.perform(
            post("/api/v1/agents/ops/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"查天气\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value("晴"));
    verify(agentService).process(eq(session), eq("查天气"));
  }

  @Test
  @DisplayName("invoke 不存在的 Agent_返回404")
  void invokeUnknownAgent_returns404() throws Exception {
    mvc.perform(
            post("/api/v1/agents/ghost/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("invoke 消息超32KB_返回400")
  void invokeOver32kb_returns400() throws Exception {
    String huge = "x".repeat(32 * 1024 + 1);
    mvc.perform(
            post("/api/v1/agents/ops/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + huge + "\"}"))
        .andExpect(status().isBadRequest());
    verify(agentService, never()).process(any(), any());
  }
}
