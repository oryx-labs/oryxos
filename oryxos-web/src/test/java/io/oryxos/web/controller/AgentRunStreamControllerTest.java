package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentRunEvent;
import io.oryxos.core.agent.AgentRunEventHub;
import io.oryxos.core.agent.AgentRunEventStore;
import io.oryxos.core.agent.AgentRunEventTypes;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRunStreamControllerTest {

  @Test
  void replaysTwelveHundredPersistedEventsInMultipleBatches() throws Exception {
    AgentExecutionService executions = mock(AgentExecutionService.class);
    when(executions.findById(7L))
        .thenReturn(
            Optional.of(
                new AgentExecution(
                    7L,
                    "ops",
                    "manual",
                    "s",
                    Instant.parse("2026-08-23T04:00:00Z"),
                    null,
                    null,
                    null,
                    null)));
    AgentRunEventStore store = mock(AgentRunEventStore.class);
    when(store.readAfter(eq(7L), eq(0L), eq(500))).thenReturn(events(7L, 1, 500, false));
    when(store.readAfter(eq(7L), eq(500L), eq(500))).thenReturn(events(7L, 501, 500, false));
    when(store.readAfter(eq(7L), eq(1000L), eq(500))).thenReturn(events(7L, 1001, 200, true));

    DeferredExecutor executor = new DeferredExecutor();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentRunStreamController(
                    executions, store, new AgentRunEventHub(), executor, new ObjectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    MvcResult started =
        mvc.perform(get("/api/v1/runs/7/stream?after=0"))
            .andExpect(request().asyncStarted())
            .andReturn();
    executor.runQueued();

    MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
    String body = done.getResponse().getContentAsString();
    assertTrue(body.contains("\"sequence\":1"));
    assertTrue(body.contains("\"sequence\":500"));
    assertTrue(body.contains("\"sequence\":501"));
    assertTrue(body.contains("\"sequence\":1200"));
    assertEquals(1200, body.split("\"sequence\":").length - 1);
    assertTrue(body.contains(AgentRunEventTypes.RUN_FINISHED));
  }

  private static List<AgentRunEvent> events(
      long runId, long start, int count, boolean terminalLast) {
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    List<AgentRunEvent> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long sequence = start + i;
      String type =
          terminalLast && i == count - 1
              ? AgentRunEventTypes.RUN_FINISHED
              : AgentRunEventTypes.MESSAGE_CONTENT;
      rows.add(new AgentRunEvent(runId, sequence, type, now, "{\"schemaVersion\":1}"));
    }
    return rows;
  }

  private static final class DeferredExecutor extends AbstractExecutorService {
    private final List<Runnable> queued = new ArrayList<>();
    private boolean shutdown;

    @Override
    public synchronized void execute(Runnable command) {
      queued.add(command);
    }

    synchronized void runQueued() {
      List<Runnable> snapshot = List.copyOf(queued);
      queued.clear();
      snapshot.forEach(Runnable::run);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
