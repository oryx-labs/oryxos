package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** task_executions 读写通道；按任务查历史（开始时间倒序）。 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

  List<TaskExecution> findByTaskIdOrderByStartedAtDesc(String taskId);
}
