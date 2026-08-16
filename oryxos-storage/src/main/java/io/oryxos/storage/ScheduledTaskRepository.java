package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 读写通道。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {

  Optional<ScheduledTask> findByProfileNameAndScheduleKey(String profileName, String scheduleKey);

  List<ScheduledTask> findByRetiredFalse();

  List<ScheduledTask> findByScheduleKeyAndRetiredFalse(String scheduleKey);
}
