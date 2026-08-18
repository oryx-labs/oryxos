package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * Delivers a configured scheduler message through the same AgentService entry point as interactive
 * callers. Runtime identity is always the globally unique scheduleId; the profile key only locates
 * the current configuration definition.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are shared Spring services by design.")
public class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);
  private static final String SCHEDULER_CHANNEL = "scheduler";
  private static final String SCHEDULER_USER = "scheduler";

  private final TaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ScheduledTaskStore taskStore;
  private final AgentExecutionStore agentExecutionStore;

  /** scheduleId => in-process overlap lock. */
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  /** scheduleId => cancellable future. */
  private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

  /** scheduleId => configuration generation captured by each cron callback. */
  private final ConcurrentMap<String, Long> scheduleGenerations = new ConcurrentHashMap<>();

  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore taskStore) {
    this(taskScheduler, profileRegistry, agentService, sessionManager, taskStore, null);
  }

  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore taskStore,
      AgentExecutionStore agentExecutionStore) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.taskStore = taskStore;
    this.agentExecutionStore = agentExecutionStore;
  }

  /** Registers schedules for every currently loaded Agent. */
  public void registerAll() {
    for (Profile profile : profileRegistry.all()) {
      registerProfile(profile);
    }
  }

  /**
   * Reconciles each configured task before its trigger is installed. Reconciliation preserves the
   * stable scheduleId for the same (profileName, key), including its enabled state and history.
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every dynamic log value is passed through sanitizeLogValue; SpotBugs does not track the sanitizer across method calls.")
  public void registerProfile(Profile profile) {
    for (ScheduleConfig schedule : profile.schedules()) {
      try {
        CronTrigger trigger = new CronTrigger(schedule.cron(), resolveZone(schedule.zone()));
        String scheduleId =
            taskStore.reconcile(
                profile.name(),
                schedule.key(),
                schedule.name(),
                schedule.cron(),
                schedule.zone(),
                schedule.message(),
                nextExecution(schedule));
        Lock lock = lockFor(scheduleId);
        lock.lock();
        try {
          long generation = nextGeneration(scheduleId);
          ScheduledFuture<?> future =
              taskScheduler.schedule(
                  () -> runOnce(profile, schedule, scheduleId, generation), trigger);
          if (future != null) {
            ScheduledFuture<?> previous = scheduledTasks.put(scheduleId, future);
            if (previous != null && previous != future) {
              previous.cancel(false);
            }
          }
        } finally {
          lock.unlock();
        }
        LOG.info(
            "Registered schedule {} (profile={} key={} cron={} zone={})",
            sanitizeLogValue(scheduleId),
            sanitizeLogValue(profile.name()),
            sanitizeLogValue(schedule.key()),
            sanitizeLogValue(schedule.cron()),
            sanitizeLogValue(schedule.zone()));
      } catch (RuntimeException exception) {
        LOG.warn(
            "Invalid schedule {} was skipped: {}",
            sanitizeLogValue(schedule.key()),
            sanitizeLogValue(exception.getMessage()));
      }
    }
  }

  /** Returns whether the runtime handle for this scheduleId exists. */
  public boolean hasScheduledTask(String scheduleId) {
    return scheduledTasks.containsKey(scheduleId);
  }

  /** Cancels the registered futures for all definitions belonging to a Profile. */
  public void unregisterProfile(Profile profile) {
    for (ScheduleConfig schedule : profile.schedules()) {
      List<String> scheduleIds =
          taskStore.list().stream()
              .filter(
                  task ->
                      task.profileName().equals(profile.name())
                          && task.key().equals(schedule.key()))
              .map(ScheduledTaskView::scheduleId)
              .toList();
      for (String scheduleId : scheduleIds) {
        retire(scheduleId, profile.name(), schedule.key());
      }
      if (scheduleIds.isEmpty()) {
        taskStore.retire(profile.name(), schedule.key());
      }
    }
  }

  /** Runs a cron callback if its runtime task is enabled. */
  public void runOnce(Profile profile, ScheduleConfig schedule, String scheduleId) {
    runOnce(profile, schedule, scheduleId, currentGeneration(scheduleId));
  }

  /**
   * Manually runs exactly one persisted schedule. The persisted scheduleId identifies both the
   * owning profile and the current configuration key.
   */
  public void runNow(String scheduleId) {
    ScheduledTaskView task =
        taskStore.list().stream()
            .filter(candidate -> candidate.scheduleId().equals(scheduleId))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Schedule does not exist: " + scheduleId));
    Profile profile =
        profileRegistry
            .get(task.profileName())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Schedule owner does not exist: "
                            + task.profileName()
                            + " ("
                            + scheduleId
                            + ")"));
    ScheduleConfig schedule =
        profile.schedules().stream()
            .filter(candidate -> candidate.key().equals(task.key()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Schedule configuration does not exist: "
                            + task.profileName()
                            + "/"
                            + task.key()
                            + " ("
                            + scheduleId
                            + ")"));
    executeIfCurrent(profile, schedule, scheduleId, currentGeneration(scheduleId));
  }

  /** Manually runs one schedule identified by its configuration location. */
  public void runNow(String profileName, String key) {
    String scheduleId =
        taskStore.list().stream()
            .filter(task -> task.profileName().equals(profileName) && task.key().equals(key))
            .map(ScheduledTaskView::scheduleId)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Schedule does not exist: " + profileName + "/" + key));
    runNow(scheduleId);
  }

  /** Executes and records one scheduler delivery under its global scheduleId. */
  public void execute(Profile profile, ScheduleConfig schedule, String scheduleId) {
    executeIfCurrent(profile, schedule, scheduleId, currentGeneration(scheduleId));
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The scheduleId is stripped of CR and LF before logging.")
  private void runOnce(
      Profile profile, ScheduleConfig schedule, String scheduleId, long capturedGeneration) {
    Lock lock = lockFor(scheduleId);
    if (!lock.tryLock()) {
      LOG.info("Schedule {} is still running; skipping this trigger", sanitizeLogValue(scheduleId));
      return;
    }
    try {
      if (!isCurrentGeneration(scheduleId, capturedGeneration)) {
        LOG.debug(
            "Schedule {} callback belongs to a retired configuration; skipping",
            sanitizeLogValue(scheduleId));
        return;
      }
      if (!taskStore.isEnabled(scheduleId)) {
        LOG.info("Schedule {} is disabled; skipping this trigger", sanitizeLogValue(scheduleId));
        return;
      }
      executeLocked(profile, schedule, scheduleId);
    } finally {
      lock.unlock();
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The scheduleId is stripped of CR and LF before logging.")
  private void executeIfCurrent(
      Profile profile, ScheduleConfig schedule, String scheduleId, long capturedGeneration) {
    Lock lock = lockFor(scheduleId);
    if (!lock.tryLock()) {
      LOG.info("Schedule {} is still running; skipping this trigger", sanitizeLogValue(scheduleId));
      return;
    }
    try {
      if (!isCurrentGeneration(scheduleId, capturedGeneration)) {
        LOG.debug(
            "Schedule {} changed while it was being started; skipping",
            sanitizeLogValue(scheduleId));
        return;
      }
      executeLocked(profile, schedule, scheduleId);
    } finally {
      lock.unlock();
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The scheduleId is stripped of CR and LF before logging.")
  private void executeLocked(Profile profile, ScheduleConfig schedule, String scheduleId) {
    Instant startedAt = Instant.now();
    long start = System.currentTimeMillis();
    String sessionId = null;
    boolean success = false;
    String error = null;
    long agentExecutionId = startAgentExecution(profile, startedAt);
    try {
      Session session =
          sessionManager.getOrCreate(SCHEDULER_CHANNEL, SCHEDULER_USER, profile.name());
      sessionId = session.sessionId();
      agentService.process(session, schedule.message());
      success = true;
    } catch (Exception exception) {
      error = exception.getMessage();
      LOG.error("Schedule {} failed", sanitizeLogValue(scheduleId), exception);
    } finally {
      recordExecution(schedule, scheduleId, sessionId, startedAt, success, error, start);
      finishAgentExecution(agentExecutionId, sessionId, success, error);
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The scheduleId is stripped of CR and LF before logging.")
  private void retire(String scheduleId, String profileName, String key) {
    Lock lock = lockFor(scheduleId);
    lock.lock();
    try {
      nextGeneration(scheduleId);
      ScheduledFuture<?> future = scheduledTasks.remove(scheduleId);
      if (future != null) {
        future.cancel(false);
        LOG.info("Unregistered schedule {}", sanitizeLogValue(scheduleId));
      }
      taskStore.retire(profileName, key);
    } finally {
      lock.unlock();
    }
  }

  private long nextGeneration(String scheduleId) {
    return scheduleGenerations.merge(scheduleId, 1L, Long::sum);
  }

  private long currentGeneration(String scheduleId) {
    return scheduleGenerations.getOrDefault(scheduleId, 0L);
  }

  private boolean isCurrentGeneration(String scheduleId, long capturedGeneration) {
    return currentGeneration(scheduleId) == capturedGeneration;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The exception message is stripped of CR and LF before logging.")
  private long startAgentExecution(Profile profile, Instant startedAt) {
    if (agentExecutionStore == null) {
      return -1;
    }
    try {
      return agentExecutionStore.start(profile.name(), "schedule", startedAt);
    } catch (RuntimeException exception) {
      LOG.warn(
          "Could not create Agent execution record: {}", sanitizeLogValue(exception.getMessage()));
      return -1;
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "The scheduleId and exception message are stripped of CR and LF before logging.")
  private void recordExecution(
      ScheduleConfig schedule,
      String scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String error,
      long start) {
    try {
      taskStore.recordExecution(
          scheduleId,
          sessionId,
          startedAt,
          success,
          error,
          System.currentTimeMillis() - start,
          nextExecution(schedule));
    } catch (RuntimeException exception) {
      LOG.warn(
          "Could not record execution for schedule {}: {}",
          sanitizeLogValue(scheduleId),
          sanitizeLogValue(exception.getMessage()));
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "The exception message is stripped of CR and LF before logging.")
  private void finishAgentExecution(
      long agentExecutionId, String sessionId, boolean success, String error) {
    if (agentExecutionStore == null || agentExecutionId < 0) {
      return;
    }
    try {
      agentExecutionStore.finish(agentExecutionId, sessionId, success, error, Instant.now());
    } catch (RuntimeException exception) {
      LOG.warn(
          "Could not finish Agent execution record: {}", sanitizeLogValue(exception.getMessage()));
    }
  }

  private Instant nextExecution(ScheduleConfig schedule) {
    try {
      CronTrigger trigger = new CronTrigger(schedule.cron(), resolveZone(schedule.zone()));
      return trigger.nextExecution(new SimpleTriggerContext());
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /** Returns the in-process overlap lock associated with one globally unique scheduleId. */
  public Lock lockFor(String scheduleId) {
    return taskLocks.computeIfAbsent(scheduleId, ignored -> new ReentrantLock());
  }

  private ZoneId resolveZone(String zone) {
    return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
  }

  static String sanitizeLogValue(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
