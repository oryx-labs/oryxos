package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

class AgentSchedulerTest {

  private static final String PROFILE_NAME = "ops-agent";
  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";

  private TaskScheduler taskScheduler;
  private ProfileRegistry profileRegistry;
  private AgentService agentService;
  private SessionManager sessionManager;
  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    profileRegistry = mock(ProfileRegistry.class);
    taskStore = mock(ScheduledTaskStore.class);
    when(taskStore.isEnabled(any())).thenReturn(true);
    when(taskStore.reconcile(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0) + "-" + invocation.getArgument(1));
    scheduler =
        new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionManager, taskStore);
  }

  private static ScheduleConfig sc(String key) {
    return new ScheduleConfig(key, key, CRON, ZONE, "summarize today's PR progress");
  }

  private static String scheduleId(String key) {
    return PROFILE_NAME + "-" + key;
  }

  private static Profile profileWith(List<ScheduleConfig> schedules) {
    return profileNamed(PROFILE_NAME, schedules.toArray(new ScheduleConfig[0]));
  }

  private static Profile profileNamed(String name, ScheduleConfig... schedules) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  void registerPassesCronAndZoneToTrigger() {
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of(sc("daily")))));

    scheduler.registerAll();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    CronTrigger trigger = assertInstanceOf(CronTrigger.class, captor.getValue());
    assertEquals(CRON, trigger.getExpression());
    SimpleTriggerContext context =
        new SimpleTriggerContext(Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    assertEquals(
        new CronTrigger(CRON, ZoneId.of(ZONE)).nextExecution(context),
        trigger.nextExecution(context));
    assertNotEquals(
        new CronTrigger(CRON, ZoneId.of("America/New_York")).nextExecution(context),
        trigger.nextExecution(context));
  }

  @Test
  void previousRunStillActiveSkipsCurrentTrigger() throws InterruptedException {
    Profile profile = profileWith(List.of(sc("task-1")));
    Lock lock = scheduler.lockFor(scheduleId("task-1"));
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              locked.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();
    locked.await();

    try {
      scheduler.runOnce(profile, sc("task-1"), scheduleId("task-1"));
      verify(agentService, never()).process(any(), any());
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  void taskFailureIsNotRethrownAndLockIsReleased() {
    Profile profile = profileWith(List.of(sc("task-1")));
    when(sessionManager.getOrCreate(any(), any(), any()))
        .thenReturn(new Session("sched-sid", PROFILE_NAME));
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> scheduler.runOnce(profile, sc("task-1"), scheduleId("task-1")));

    scheduler.runOnce(profile, sc("task-1"), scheduleId("task-1"));
    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  void sessionIdentityIsFixedForRepeatedSchedulerRuns() {
    Profile profile = profileWith(List.of(sc("task-1")));
    Session shared = new Session("sched-sid", PROFILE_NAME);
    when(sessionManager.getOrCreate("scheduler", "scheduler", PROFILE_NAME)).thenReturn(shared);

    scheduler.runOnce(profile, sc("task-1"), scheduleId("task-1"));
    scheduler.runOnce(profile, sc("task-1"), scheduleId("task-1"));

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", PROFILE_NAME);
    ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
    verify(agentService, times(2)).process(captor.capture(), eq("summarize today's PR progress"));
    assertSame(shared, captor.getAllValues().get(0));
    assertSame(shared, captor.getAllValues().get(1));
  }

  @Test
  void multipleAgentsUseDifferentSchedulerSessions() {
    Profile alpha = profileNamed("alpha-agent", sc("alpha-task"));
    Profile beta = profileNamed("beta-agent", sc("beta-task"));
    Session alphaSession = new Session("alpha-sid", "alpha-agent");
    Session betaSession = new Session("beta-sid", "beta-agent");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "alpha-agent"))
        .thenReturn(alphaSession);
    when(sessionManager.getOrCreate("scheduler", "scheduler", "beta-agent"))
        .thenReturn(betaSession);

    scheduler.runOnce(alpha, sc("alpha-task"), "alpha-schedule");
    scheduler.runOnce(beta, sc("beta-task"), "beta-schedule");

    ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
    verify(agentService, times(2)).process(captor.capture(), eq("summarize today's PR progress"));
    assertSame(alphaSession, captor.getAllValues().get(0));
    assertSame(betaSession, captor.getAllValues().get(1));
    assertNotEquals(alphaSession.sessionId(), betaSession.sessionId());
  }

  @Test
  void invalidCronIsSkippedWithoutBlockingOtherSchedules() {
    ScheduleConfig bad = new ScheduleConfig("bad", "Bad", "not-a-cron", ZONE, "x");
    ScheduleConfig good = sc("good");
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of(bad, good))));

    assertDoesNotThrow(scheduler::registerAll);

    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    verify(taskStore, never())
        .reconcile(eq(PROFILE_NAME), eq("bad"), any(), any(), any(), any(), any());
  }

  @Test
  void logValuesCannotInjectAdditionalLines() {
    assertEquals("daily__forged-entry", AgentScheduler.sanitizeLogValue("daily\r\nforged-entry"));
  }

  @Test
  void noSchedulesMakesRegistrationANoop() {
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of())));

    assertDoesNotThrow(scheduler::registerAll);

    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
  }
}
