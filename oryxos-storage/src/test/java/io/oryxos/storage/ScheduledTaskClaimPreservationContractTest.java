package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 旧实体快照写入普通日程状态时，不得撤销其他副本已经成功的到点认领。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class ScheduledTaskClaimPreservationContractTest {

  @Autowired private ScheduledTaskRepository tasks;
  @Autowired private TaskExecutionRepository executions;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void savingAConfigurationSnapshotDoesNotEraseTheFirstFireTimeClaim() {
    String scheduleId = seed("first-claim");
    ScheduledTask snapshot = tasks.findById(scheduleId).orElseThrow();
    Instant fireTime = Instant.parse("2026-10-08T08:00:00Z");

    assertThat(tasks.claimFireTime(scheduleId, fireTime, "replica-a")).isEqualTo(1);
    snapshot.setDisplayName("配置已更新");
    tasks.saveAndFlush(snapshot);

    assertThat(tasks.findById(scheduleId).orElseThrow().getDisplayName()).isEqualTo("配置已更新");
    assertThat(tasks.claimFireTime(scheduleId, fireTime, "replica-b"))
        .as("普通配置写入不得让同一到点被第二次认领")
        .isZero();
    assertClaimOwner(scheduleId, "replica-a");
  }

  @Test
  void savingAnExecutionSnapshotDoesNotRollBackANewerFireTimeClaim() {
    String scheduleId = seed("newer-claim");
    Instant firstFire = Instant.parse("2026-10-08T08:00:00Z");
    Instant nextFire = firstFire.plusSeconds(2);
    assertThat(tasks.claimFireTime(scheduleId, firstFire, "replica-a")).isEqualTo(1);
    ScheduledTask snapshot = tasks.findById(scheduleId).orElseThrow();

    assertThat(tasks.claimFireTime(scheduleId, nextFire, "replica-b")).isEqualTo(1);
    snapshot.setRunCount(1L);
    tasks.saveAndFlush(snapshot);

    assertThat(tasks.findById(scheduleId).orElseThrow().getRunCount()).isEqualTo(1L);
    assertThat(tasks.claimFireTime(scheduleId, nextFire, "replica-c"))
        .as("旧执行状态写入不得回退已认领的较新到点")
        .isZero();
    assertClaimOwner(scheduleId, "replica-b");
    assertThat(tasks.claimFireTime(scheduleId, nextFire.plusSeconds(2), "replica-c")).isEqualTo(1);
  }

  private String seed(String key) {
    return new JpaScheduledTaskStore(tasks, executions)
        .reconcile("cron-agent", key, key, "*/2 * * * * *", "UTC", "tick", Instant.now());
  }

  private void assertClaimOwner(String scheduleId, String expectedOwner) {
    assertThat(
            jdbc.queryForObject(
                "SELECT claimed_by FROM scheduled_tasks WHERE schedule_id = ?",
                String.class,
                scheduleId))
        .isEqualTo(expectedOwner);
  }
}
