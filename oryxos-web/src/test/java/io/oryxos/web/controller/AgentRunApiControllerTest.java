package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentRunEvent;
import io.oryxos.core.agent.AgentRunEventPayloads;
import io.oryxos.core.agent.AgentRunEventStore;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentStopReasons;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRunApiControllerTest {

  private AgentExecutionService executions;
  private AgentRunEventStore events;
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
    executions = mock(AgentExecutionService.class);
    events = mock(AgentRunEventStore.class);
    SessionManager sessions = mock(SessionManager.class);
    when(sessions.getOrCreate(any(), any(), any())).thenReturn(new Session("s-1", "ops"));
    ProfileRegistry registry = new ProfileRegistry(Map.of("ops", profile("ops")));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentRunApiController(
                    executions,
                    events,
                    mock(AgentService.class),
                    sessions,
                    registry,
                    new ObjectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void createReturnsRunWithoutWaiting() throws Exception {
    when(executions.triggerAsync(eq("ops"), eq("manual"), any(), any(), any())).thenReturn(11L);
    when(executions.findById(11L))
        .thenReturn(
            Optional.of(
                new AgentExecution(
                    11L,
                    "ops",
                    "manual",
                    "s-1",
                    Instant.parse("2026-08-23T04:00:00Z"),
                    null,
                    null,
                    null,
                    null,
                    Instant.parse("2026-08-23T04:00:00Z"),
                    "检查服务",
                    null,
                    "QUEUED")));
    when(events.lastSequence(11L)).thenReturn(0L);

    mvc.perform(
            post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentName\":\"ops\",\"content\":\"检查服务\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(11))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.cancellable").value(true));
  }

  @Test
  void missingRunReturns404() throws Exception {
    when(executions.findById(99L)).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/runs/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void eventsUseCursor() throws Exception {
    when(executions.findById(5L))
        .thenReturn(
            Optional.of(
                new AgentExecution(
                    5L, "ops", "manual", "s", Instant.now(), null, null, null, null)));
    when(events.readAfter(eq(5L), eq(2L), anyInt()))
        .thenReturn(
            List.of(
                new AgentRunEvent(
                    5L,
                    3L,
                    "MESSAGE_CONTENT",
                    Instant.parse("2026-08-23T04:00:01Z"),
                    "{\"schemaVersion\":1,\"delta\":\"hi\"}")));

    mvc.perform(get("/api/v1/runs/5/events?after=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.events[0].sequence").value(3))
        .andExpect(jsonPath("$.data.hasMore").value(false));
  }

  @Test
  void cancelDelegatesToService() throws Exception {
    AgentExecution cancelling =
        new AgentExecution(
            5L,
            "ops",
            "manual",
            "s",
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            "x",
            Instant.now(),
            "CANCELLING");
    when(executions.findById(5L)).thenReturn(Optional.of(cancelling));
    when(executions.cancel(5L)).thenReturn(cancelling);
    when(events.lastSequence(5L)).thenReturn(2L);

    mvc.perform(post("/api/v1/runs/5/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLING"));
    verify(executions).cancel(5L);
  }

  @Test
  void cancelReturnsStopReason() throws Exception {
    AgentExecution cancelled =
        new AgentExecution(
            6L,
            "ops",
            "manual",
            "s",
            Instant.parse("2026-08-23T04:00:00Z"),
            Instant.parse("2026-08-23T04:00:01Z"),
            false,
            1000L,
            AgentStopReasons.MESSAGE_NO_ACTIVE_WORKER,
            Instant.parse("2026-08-23T04:00:01Z"),
            "检查服务",
            Instant.parse("2026-08-23T04:00:01Z"),
            "CANCELLED",
            AgentStopReasons.NO_ACTIVE_WORKER);
    when(executions.findById(6L)).thenReturn(Optional.of(cancelled));
    when(executions.cancel(6L)).thenReturn(cancelled);
    when(events.lastSequence(6L)).thenReturn(3L);

    mvc.perform(post("/api/v1/runs/6/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"))
        .andExpect(jsonPath("$.data.stopReason").value(AgentStopReasons.NO_ACTIVE_WORKER));
  }

  @Test
  void createInputPreviewIsRedacted() throws Exception {
    String secret = "password=hunter2 apiKey=sk-live";
    String preview = AgentRunEventPayloads.summarizeText(secret);
    when(executions.triggerAsync(eq("ops"), eq("manual"), any(), any(), any())).thenReturn(12L);
    when(executions.findById(12L))
        .thenReturn(
            Optional.of(
                new AgentExecution(
                    12L,
                    "ops",
                    "manual",
                    "s-1",
                    Instant.parse("2026-08-23T04:00:00Z"),
                    null,
                    null,
                    null,
                    null,
                    Instant.parse("2026-08-23T04:00:00Z"),
                    preview,
                    null,
                    "QUEUED",
                    null)));
    when(events.lastSequence(12L)).thenReturn(0L);

    String body =
        mvc.perform(
                post("/api/v1/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentName\":\"ops\",\"content\":\"" + secret + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("hunter2"));
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("sk-live"));
    org.junit.jupiter.api.Assertions.assertTrue(preview.contains("***"));
  }
}
