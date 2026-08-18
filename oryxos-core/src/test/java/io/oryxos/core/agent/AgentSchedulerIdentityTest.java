package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

class AgentSchedulerIdentityTest {

  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";

  @Test
  void sameScheduleKeyInDifferentProfilesKeepsIndependentScheduleIdsAndLocks() {
    InMemoryTaskStore store = new InMemoryTaskStore();
    Profile alpha = profile("alpha", schedule("daily", "Alpha daily", "alpha message"));
    Profile beta = profile("beta", schedule("daily", "Beta daily", "beta message"));
    AgentScheduler scheduler =
        scheduler(store, new ProfileRegistry(Map.of("alpha", alpha, "beta", beta)));

    scheduler.registerAll();

    String alphaId = store.scheduleId("alpha", "daily");
    String betaId = store.scheduleId("beta", "daily");
    assertNotEquals(alphaId, betaId);
    assertNotSame(scheduler.lockFor(alphaId), scheduler.lockFor(betaId));
  }

  @Test
  void reloadingSameProfileAndKeyKeepsIdEnabledStateAndHistoryWhenNameChanges() {
    InMemoryTaskStore store = new InMemoryTaskStore();
    Profile initial = profile("alpha", schedule("daily", "Original name", "first message"));
    AgentScheduler scheduler = scheduler(store, new ProfileRegistry(Map.of("alpha", initial)));

    scheduler.registerProfile(initial);
    String scheduleId = store.scheduleId("alpha", "daily");
    store.setEnabled(scheduleId, false);
    store.recordExecution(
        scheduleId, "legacy-session", Instant.EPOCH, true, null, 1, Instant.EPOCH);

    scheduler.unregisterProfile(initial);
    Profile reloaded = profile("alpha", schedule("daily", "Renamed daily", "second message"));
    scheduler.registerProfile(reloaded);

    assertEquals(scheduleId, store.scheduleId("alpha", "daily"));
    ScheduledTaskView task = store.onlyTask();
    assertEquals("Renamed daily", task.name());
    assertFalse(task.enabled());
    assertEquals(1, store.executions(scheduleId, 10).size());
  }

  @Test
  void changingKeyRetiresTheOldScheduleWithoutDiscardingItsHistory() {
    InMemoryTaskStore store = new InMemoryTaskStore();
    Profile original = profile("alpha", schedule("daily", "Daily", "daily message"));
    AgentScheduler scheduler = scheduler(store, new ProfileRegistry(Map.of("alpha", original)));
    scheduler.registerProfile(original);
    String dailyId = store.scheduleId("alpha", "daily");
    store.recordExecution(dailyId, "daily-session", Instant.EPOCH, true, null, 1, Instant.EPOCH);

    scheduler.unregisterProfile(original);
    scheduler.registerProfile(profile("alpha", schedule("weekly", "Weekly", "weekly message")));

    assertEquals(List.of("weekly"), store.list().stream().map(ScheduledTaskView::key).toList());
    assertFalse(store.isEnabled(dailyId));
    assertEquals(1, store.executions(dailyId, 10).size());
  }

  @Test
  void staleCronCallbackCannotRunThePreviousConfigurationAfterReload() {
    InMemoryTaskStore store = new InMemoryTaskStore();
    Profile initial = profile("alpha", schedule("daily", "Daily", "old message"));
    Profile reloaded = profile("alpha", schedule("daily", "Daily", "new message"));
    AgentService service = mock(AgentService.class);
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    List<Runnable> callbacks = new ArrayList<>();
    SessionManager sessions = mock(SessionManager.class);
    when(sessions.getOrCreate(any(), any(), any())).thenReturn(new Session("s", "alpha"));
    when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
        .thenAnswer(
            invocation -> {
              callbacks.add(invocation.getArgument(0, Runnable.class));
              return future;
            });
    AgentScheduler scheduler =
        new AgentScheduler(
            taskScheduler, new ProfileRegistry(Map.of("alpha", initial)), service, sessions, store);

    scheduler.registerProfile(initial);
    scheduler.unregisterProfile(initial);
    scheduler.registerProfile(reloaded);
    callbacks.getFirst().run();

    verify(service, never()).process(any(Session.class), any());
  }

  @Test
  void runNowByScheduleIdTargetsTheMatchingProfileWhenKeysAreShared() {
    InMemoryTaskStore store = new InMemoryTaskStore();
    Profile alpha = profile("alpha", schedule("daily", "Alpha daily", "alpha message"));
    Profile beta = profile("beta", schedule("daily", "Beta daily", "beta message"));
    ProfileRegistry profiles = new ProfileRegistry(Map.of("alpha", alpha, "beta", beta));
    AgentService service = mock(AgentService.class);
    SessionManager sessions = mock(SessionManager.class);
    when(sessions.getOrCreate(any(), any(), any()))
        .thenAnswer(invocation -> new Session("s", invocation.getArgument(2)));
    AgentScheduler scheduler = scheduler(store, profiles, service, sessions);
    scheduler.registerAll();

    scheduler.runNow(store.scheduleId("beta", "daily"));

    verify(service).process(any(Session.class), org.mockito.ArgumentMatchers.eq("beta message"));
  }

  private static AgentScheduler scheduler(InMemoryTaskStore store, ProfileRegistry profiles) {
    return scheduler(store, profiles, mock(AgentService.class), mock(SessionManager.class));
  }

  private static AgentScheduler scheduler(
      InMemoryTaskStore store,
      ProfileRegistry profiles,
      AgentService service,
      SessionManager sessions) {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    return new AgentScheduler(taskScheduler, profiles, service, sessions, store);
  }

  private static Profile profile(String name, ScheduleConfig schedule) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("test", "test", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedule),
        List.of(),
        Profile.Settings.defaults());
  }

  private static ScheduleConfig schedule(String key, String name, String message) {
    return new ScheduleConfig(key, name, CRON, ZONE, message);
  }

  private static final class InMemoryTaskStore implements ScheduledTaskStore {
    private final Map<String, ScheduledTaskView> tasksById = new LinkedHashMap<>();
    private final Map<String, String> idsByProfileAndKey = new LinkedHashMap<>();
    private final Map<String, List<TaskExecutionView>> executions = new LinkedHashMap<>();
    private final Set<String> retiredIds = new HashSet<>();

    @Override
    public String reconcile(
        String profileName,
        String key,
        String name,
        String cron,
        String zone,
        String message,
        Instant nextRunAt) {
      String profileAndKey = profileName + "/" + key;
      String scheduleId =
          idsByProfileAndKey.computeIfAbsent(
              profileAndKey, ignored -> "schedule-" + (idsByProfileAndKey.size() + 1));
      retiredIds.remove(scheduleId);
      ScheduledTaskView old = tasksById.get(scheduleId);
      tasksById.put(
          scheduleId,
          new ScheduledTaskView(
              scheduleId,
              profileName,
              key,
              name,
              cron,
              zone,
              message,
              old == null || old.enabled(),
              nextRunAt,
              old == null ? null : old.lastRunAt(),
              old == null ? null : old.lastStatus(),
              old == null ? 0 : old.runCount()));
      return scheduleId;
    }

    @Override
    public void recordExecution(
        String scheduleId,
        String sessionId,
        Instant startedAt,
        boolean success,
        String errorMessage,
        long durationMs,
        Instant nextRunAt) {
      ScheduledTaskView task = tasksById.get(scheduleId);
      tasksById.put(
          scheduleId,
          new ScheduledTaskView(
              task.scheduleId(),
              task.profileName(),
              task.key(),
              task.name(),
              task.cron(),
              task.zone(),
              task.message(),
              task.enabled(),
              nextRunAt,
              startedAt,
              success ? "SUCCESS" : "FAILED",
              task.runCount() + 1));
      executions
          .computeIfAbsent(scheduleId, ignored -> new ArrayList<>())
          .add(
              new TaskExecutionView(
                  scheduleId,
                  null,
                  false,
                  sessionId,
                  startedAt,
                  success,
                  errorMessage,
                  durationMs));
    }

    @Override
    public boolean isEnabled(String scheduleId) {
      return !retiredIds.contains(scheduleId) && tasksById.get(scheduleId).enabled();
    }

    @Override
    public void setEnabled(String scheduleId, boolean enabled) {
      ScheduledTaskView task = tasksById.get(scheduleId);
      tasksById.put(
          scheduleId,
          new ScheduledTaskView(
              task.scheduleId(),
              task.profileName(),
              task.key(),
              task.name(),
              task.cron(),
              task.zone(),
              task.message(),
              enabled,
              task.nextRunAt(),
              task.lastRunAt(),
              task.lastStatus(),
              task.runCount()));
    }

    @Override
    public void retire(String profileName, String key) {
      String scheduleId = idsByProfileAndKey.get(profileName + "/" + key);
      if (scheduleId != null) {
        retiredIds.add(scheduleId);
      }
    }

    @Override
    public List<ScheduledTaskView> list() {
      return tasksById.values().stream()
          .filter(task -> !retiredIds.contains(task.scheduleId()))
          .toList();
    }

    @Override
    public List<ScheduledTaskView> findByKey(String key) {
      return tasksById.values().stream()
          .filter(task -> !retiredIds.contains(task.scheduleId()) && task.key().equals(key))
          .toList();
    }

    @Override
    public boolean exists(String scheduleId) {
      return tasksById.containsKey(scheduleId);
    }

    @Override
    public List<TaskExecutionView> executions(String scheduleId, int limit) {
      return executions.getOrDefault(scheduleId, List.of()).stream().limit(limit).toList();
    }

    String scheduleId(String profileName, String key) {
      return idsByProfileAndKey.get(profileName + "/" + key);
    }

    ScheduledTaskView onlyTask() {
      return tasksById.values().iterator().next();
    }
  }
}
