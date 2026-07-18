package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 读写通道。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {}
