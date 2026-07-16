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
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 课件《第26节》验收 harness：SessionApiControllerTest——Controller 切片（standalone MockMvc，mock 核心服务，
 * 不碰模型不碰库）。守三点：超 32KB→400、会话不存在→404、正常请求编排入口恰被调一次（Controller 不夹带私货）。
 */
class SessionApiControllerTest {

  private AgentService agentService;
  private SessionManager sessionManager;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SessionApiController(agentService, sessionManager))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("消息超32KB_返回400且不触发编排")
  void messageOver32kb_returns400() throws Exception {
    String huge = "x".repeat(32 * 1024 + 1);
    mvc.perform(
            post("/api/v1/sessions/s-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + huge + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("会话不存在_返回404")
  void sessionNotFound_returns404() throws Exception {
    when(sessionManager.get("missing")).thenReturn(Optional.empty());

    mvc.perform(
            post("/api/v1/sessions/missing/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("正常发消息_编排入口恰被调一次")
  void normalMessage_callsProcessExactlyOnce() throws Exception {
    Session session = new Session("web:default:ops", "ops");
    when(sessionManager.get("web:default:ops")).thenReturn(Optional.of(session));
    when(agentService.process(eq(session), eq("今天天气"))).thenReturn("晴，适合穿短袖");

    mvc.perform(
            post("/api/v1/sessions/web:default:ops/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"今天天气\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("晴，适合穿短袖"));

    verify(agentService).process(eq(session), eq("今天天气")); // 恰一次；Controller 不夹带私货
  }
}
