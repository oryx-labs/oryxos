package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ScheduledTaskView;
import io.oryxos.core.agent.TaskExecutionView;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ScheduleApiControllerTest {

  private static final ScheduledTaskView ALPHA_DAILY =
      new ScheduledTaskView(
          "schedule-alpha-daily",
          "alpha",
          "daily",
          "Alpha daily report",
          "0 0 9 * * *",
          "Asia/Shanghai",
          "send alpha report",
          true,
          null,
          null,
          null,
          0);

  private static final ScheduledTaskView BETA_DAILY =
      new ScheduledTaskView(
          "schedule-beta-daily",
          "beta",
          "daily",
          "Beta daily report",
          "0 0 9 * * *",
          "Asia/Shanghai",
          "send beta report",
          true,
          null,
          null,
          null,
          0);

  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    taskStore = mock(ScheduledTaskStore.class);
    scheduler = mock(AgentScheduler.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ScheduleApiController(taskStore, scheduler),
                new ScheduleV2ApiController(taskStore, scheduler))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void v2RunTargetsTheExactScheduleId() throws Exception {
    when(taskStore.list()).thenReturn(List.of(ALPHA_DAILY));
    when(taskStore.executions("schedule-alpha-daily", 100))
        .thenReturn(
            List.of(
                new TaskExecutionView(
                    "schedule-alpha-daily",
                    null,
                    false,
                    "session-1",
                    Instant.parse("2026-08-14T00:00:00Z"),
                    true,
                    null,
                    12)));

    mvc.perform(post("/api/v2/schedules/schedule-alpha-daily/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].scheduleId").value("schedule-alpha-daily"));

    verify(scheduler).runNow("schedule-alpha-daily");
  }

  @Test
  void v2ListExposesScheduleIdentityAndConfigurationIdentity() throws Exception {
    when(taskStore.list()).thenReturn(List.of(ALPHA_DAILY));

    mvc.perform(get("/api/v2/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].scheduleId").value("schedule-alpha-daily"))
        .andExpect(jsonPath("$.data[0].profileName").value("alpha"))
        .andExpect(jsonPath("$.data[0].key").value("daily"))
        .andExpect(jsonPath("$.data[0].name").value("Alpha daily report"));
  }

  @Test
  void v2HistoryRemainsAvailableForRetiredScheduleId() throws Exception {
    when(taskStore.exists("retired-daily")).thenReturn(true);
    when(taskStore.executions("retired-daily", 100))
        .thenReturn(
            List.of(
                new TaskExecutionView(
                    "retired-daily",
                    null,
                    false,
                    "session-1",
                    Instant.parse("2026-08-14T00:00:00Z"),
                    true,
                    null,
                    12)));

    mvc.perform(get("/api/v2/schedules/retired-daily/executions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].scheduleId").value("retired-daily"));
  }

  @Test
  void v1RunRejectsAmbiguousLegacyKeyInsteadOfRunningTheFirstMatch() throws Exception {
    when(taskStore.findByKey("daily")).thenReturn(List.of(ALPHA_DAILY, BETA_DAILY));

    mvc.perform(post("/api/v1/schedules/daily/run"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    verify(scheduler, never()).runNow(anyString());
  }

  @Test
  void v1ListRejectsAmbiguousLegacyKeyInsteadOfReturningDuplicateTaskIds() throws Exception {
    when(taskStore.list()).thenReturn(List.of(ALPHA_DAILY, BETA_DAILY));

    mvc.perform(get("/api/v1/schedules"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));
  }
}
