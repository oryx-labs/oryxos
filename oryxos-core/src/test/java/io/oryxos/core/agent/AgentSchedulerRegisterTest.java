package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

class AgentSchedulerRegisterTest {

  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";
  private static final String SCHEDULE_ID = "schedule-ops-morning";

  private TaskScheduler taskScheduler;
  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    taskStore = mock(ScheduledTaskStore.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    when(taskStore.reconcile(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(SCHEDULE_ID);
    when(taskStore.list())
        .thenReturn(
            List.of(
                new ScheduledTaskView(
                    SCHEDULE_ID,
                    "ops",
                    "morning",
                    "Morning run",
                    CRON,
                    ZONE,
                    "run now",
                    true,
                    null,
                    null,
                    null,
                    0)));
    scheduler =
        new AgentScheduler(
            taskScheduler,
            mock(ProfileRegistry.class),
            mock(AgentService.class),
            mock(SessionManager.class),
            taskStore);
  }

  private static Profile profileWithSchedule(String name, ScheduleConfig schedule) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedule),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  void registerProfileLeavesCancellableHandleByScheduleId() {
    scheduler.registerProfile(
        profileWithSchedule(
            "ops", new ScheduleConfig("morning", "Morning run", CRON, ZONE, "run now")));

    assertTrue(scheduler.hasScheduledTask(SCHEDULE_ID));
    assertFalse(scheduler.hasScheduledTask("morning"));
  }

  @Test
  void unregisterProfileCancelsAndRemovesTheScheduleIdHandle() {
    Profile profile =
        profileWithSchedule(
            "ops", new ScheduleConfig("morning", "Morning run", CRON, ZONE, "run now"));
    scheduler.registerProfile(profile);
    assertTrue(scheduler.hasScheduledTask(SCHEDULE_ID));

    scheduler.unregisterProfile(profile);

    assertFalse(scheduler.hasScheduledTask(SCHEDULE_ID));
  }

  @Test
  void reconcilesProfileKeyAndDefinitionBeforeScheduling() {
    scheduler.registerProfile(
        profileWithSchedule(
            "ops", new ScheduleConfig("morning", "Morning run", CRON, ZONE, "run now")));

    verify(taskStore)
        .reconcile(
            eq("ops"), eq("morning"), eq("Morning run"), eq(CRON), eq(ZONE), eq("run now"), any());
  }
}
