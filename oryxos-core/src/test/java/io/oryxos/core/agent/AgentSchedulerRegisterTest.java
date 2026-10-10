package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

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

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "  ")
  @ResourceLock("java.util.TimeZone.default")
  void zoneLessScheduleUsesSameFireTimeAcrossReplicaTimeZones(String zone) {
    Instant expected = Instant.parse("2026-10-06T09:00:00Z");
    assertEquals(List.of(expected, expected), registeredFireTimes(zone, expected));
  }

  @ParameterizedTest
  @CsvSource({"Asia/Shanghai, 2026-10-06T01:00:00Z", "UTC, 2026-10-06T09:00:00Z"})
  @ResourceLock("java.util.TimeZone.default")
  void explicitZoneKeepsItsFireTimeAcrossReplicaTimeZones(String zone, String expectedFireTime) {
    Instant expected = Instant.parse(expectedFireTime);
    assertEquals(List.of(expected, expected), registeredFireTimes(zone, expected));
  }

  private List<Instant> registeredFireTimes(String zone, Instant expectedFireTime) {
    TimeZone previous = TimeZone.getDefault();
    try {
      Profile profile =
          profileWithSchedule(
              "ops", new ScheduleConfig("morning", "Morning run", CRON, zone, "run now"));

      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
      scheduler.registerProfile(profile);
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      scheduler.registerProfile(profile);

      ArgumentCaptor<Instant> nextRunAt = ArgumentCaptor.forClass(Instant.class);
      verify(taskStore, times(2))
          .reconcile(
              eq("ops"),
              eq("morning"),
              eq("Morning run"),
              eq(CRON),
              eq(zone),
              eq("run now"),
              nextRunAt.capture());
      for (Instant next : nextRunAt.getAllValues()) {
        assertEquals(
            expectedFireTime.atOffset(ZoneOffset.UTC).toLocalTime(),
            next.atOffset(ZoneOffset.UTC).toLocalTime(),
            "存储层 nextRunAt 必须与配置的有效时区一致");
      }
      ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
      verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());

      Clock fixed = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
      Instant shanghaiFireTime =
          triggers.getAllValues().get(0).nextExecution(new SimpleTriggerContext(fixed));
      Instant utcFireTime =
          triggers.getAllValues().get(1).nextExecution(new SimpleTriggerContext(fixed));
      return List.of(shanghaiFireTime, utcFireTime);
    } finally {
      TimeZone.setDefault(previous);
    }
  }
}
