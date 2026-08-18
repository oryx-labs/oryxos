package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ScheduledTaskView;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaScheduledTaskStoreTest {

  @Mock private ScheduledTaskRepository tasks;
  @Mock private TaskExecutionRepository executions;

  private final Map<String, ScheduledTask> persistedTasks = new HashMap<>();
  private JpaScheduledTaskStore store;

  @BeforeEach
  void setUp() {
    store = new JpaScheduledTaskStore(tasks, executions);
  }

  @Test
  void sameScheduleKeyInDifferentProfilesKeepsTwoIndependentRows() {
    stubReconcile();
    stubFindByKey();
    Instant nextRun = Instant.parse("2026-08-15T01:00:00Z");

    String alphaId =
        store.reconcile("alpha", "daily", "Alpha morning", "0 0 9 * * *", null, "a", nextRun);
    String betaId =
        store.reconcile("beta", "daily", "Beta morning", "0 0 9 * * *", null, "b", nextRun);
    String alphaReloadedId =
        store.reconcile(
            "alpha", "daily", "Renamed Alpha", "0 0 10 * * *", null, "changed", nextRun);

    assertThat(alphaId).isNotBlank().isNotEqualTo(betaId);
    assertThat(alphaReloadedId).isEqualTo(alphaId);
    assertThat(persistedTasks).hasSize(2);
    assertThat(persistedTasks.get(identity("alpha", "daily")).getDisplayName())
        .isEqualTo("Renamed Alpha");
    assertThat(store.findByKey("daily"))
        .extracting(view -> view.scheduleId())
        .containsExactlyInAnyOrder(alphaId, betaId);
  }

  @Test
  void unknownScheduleIdDoesNotSilentlyEnableOrUpdateHistory() {
    stubFindById();

    assertThatThrownBy(() -> store.isEnabled("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("missing");
    assertThatThrownBy(() -> store.setEnabled("missing", false))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("missing");
    assertThatThrownBy(
            () ->
                store.recordExecution(
                    "missing", "session", Instant.now(), true, null, 1L, Instant.now()))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void recordsNewExecutionAgainstScheduleIdAndNeverMarksItAsLegacy() {
    stubReconcile();
    stubFindById();
    String scheduleId =
        store.reconcile("alpha", "daily", "Alpha morning", "0 0 9 * * *", null, "a", Instant.now());

    store.recordExecution(scheduleId, "session", Instant.now(), true, null, 12L, Instant.now());

    ArgumentCaptor<TaskExecution> captured = ArgumentCaptor.forClass(TaskExecution.class);
    verify(executions).save(captured.capture());
    assertThat(captured.getValue().getScheduleId()).isEqualTo(scheduleId);
    assertThat(captured.getValue().isLegacyMigrated()).isFalse();
    assertThat(captured.getValue().getLegacyTaskKey()).isNull();
  }

  @Test
  void retiringAKeyHidesItFromRuntimeLookupsButReconcileReactivatesItsStableId() {
    stubReconcile();
    stubFindByKey();
    stubActiveList();
    stubFindById();
    String originalId =
        store.reconcile("alpha", "daily", "Daily", "0 0 9 * * *", null, "daily", Instant.now());

    store.retire("alpha", "daily");

    assertThat(store.list()).isEmpty();
    assertThat(store.findByKey("daily")).isEmpty();
    assertThat(store.isEnabled(originalId)).isFalse();

    String reactivatedId =
        store.reconcile(
            "alpha", "daily", "Daily restored", "0 0 9 * * *", null, "daily", Instant.now());

    assertThat(reactivatedId).isEqualTo(originalId);
    assertThat(store.list())
        .singleElement()
        .extracting(ScheduledTaskView::name)
        .isEqualTo("Daily restored");
  }

  private void stubReconcile() {
    when(tasks.findByProfileNameAndScheduleKey(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(
                    persistedTasks.get(
                        identity(
                            invocation.getArgument(0, String.class),
                            invocation.getArgument(1, String.class)))));
    when(tasks.save(any(ScheduledTask.class)))
        .thenAnswer(
            invocation -> {
              ScheduledTask task = invocation.getArgument(0, ScheduledTask.class);
              persistedTasks.put(identity(task.getProfileName(), task.getScheduleKey()), task);
              return task;
            });
  }

  private void stubFindById() {
    when(tasks.findById(anyString()))
        .thenAnswer(
            invocation ->
                persistedTasks.values().stream()
                    .filter(
                        task ->
                            task.getScheduleId().equals(invocation.getArgument(0, String.class)))
                    .findFirst());
  }

  private void stubFindByKey() {
    when(tasks.findByScheduleKeyAndRetiredFalse(anyString()))
        .thenAnswer(
            invocation ->
                persistedTasks.values().stream()
                    .filter(
                        task ->
                            !task.isRetired()
                                && task.getScheduleKey()
                                    .equals(invocation.getArgument(0, String.class)))
                    .toList());
  }

  private void stubActiveList() {
    when(tasks.findByRetiredFalse())
        .thenAnswer(
            invocation ->
                persistedTasks.values().stream().filter(task -> !task.isRetired()).toList());
  }

  private static String identity(String profileName, String key) {
    return profileName + '\0' + key;
  }
}
