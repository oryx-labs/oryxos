package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 无状态调用切片：走 AgentService.process 同一入口；未知 Agent→404、超 32KB→400。 */
class AgentApiControllerTest {

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
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    ProfileRegistry registry = new ProfileRegistry(Map.of("ops", profile("ops")));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(agentService, sessionManager, registry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
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
