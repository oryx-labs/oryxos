package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.session.SessionSummary;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
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

  @Test
  @DisplayName("列出会话_返回摘要列表且status可过滤")
  void listSessions_returnsSummariesAndFiltersByStatus() throws Exception {
    when(sessionManager.listRecent(anyInt()))
        .thenReturn(
            List.of(
                new SessionSummary(
                    "web:default:ops",
                    "ops",
                    "web",
                    "default",
                    "active",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-02T00:00:00Z"),
                    4),
                new SessionSummary(
                    "cli:default:ops",
                    "ops",
                    "cli",
                    "default",
                    "archived",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T06:00:00Z"),
                    2)));

    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].sessionId").value("web:default:ops"))
        .andExpect(jsonPath("$.data[0].messageCount").value(4));

    // ?status=active 只留一条，且不含对话正文字段
    mvc.perform(get("/api/v1/sessions").param("status", "active"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].status").value("active"))
        .andExpect(jsonPath("$.data[0].messages").doesNotExist());
  }
}
