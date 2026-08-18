package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ScheduledTaskView;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.ExecutionView;
import io.oryxos.web.controller.dto.ScheduleView;
import io.oryxos.web.controller.dto.SetEnabledRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime schedule API that addresses every operation by the stable scheduleId. */
@RestController
@RequestMapping("/api/v2")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "SPRING_ENDPOINT"},
    justification =
        "The injected services are shared Spring singletons and the endpoint is internal-only.")
public class ScheduleV2ApiController {

  private static final int DEFAULT_EXECUTION_LIMIT = 100;

  private final ScheduledTaskStore taskStore;
  private final AgentScheduler scheduler;

  public ScheduleV2ApiController(ScheduledTaskStore taskStore, AgentScheduler scheduler) {
    this.taskStore = taskStore;
    this.scheduler = scheduler;
  }

  @GetMapping("/schedules")
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(taskStore.list().stream().map(ScheduleView::from).toList());
  }

  @GetMapping("/schedules/{scheduleId}/executions")
  public ApiResponse<List<ExecutionView>> executions(
      @PathVariable String scheduleId,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_EXECUTION_LIMIT) int limit) {
    if (!taskStore.exists(scheduleId)) {
      throw new ResourceNotFoundException("Schedule not found: " + scheduleId);
    }
    return ApiResponse.ok(
        taskStore.executions(scheduleId, capExecutionLimit(limit)).stream()
            .map(ExecutionView::from)
            .toList());
  }

  @PostMapping("/schedules/{scheduleId}/run")
  public ApiResponse<List<ExecutionView>> run(@PathVariable String scheduleId) {
    requireSchedule(scheduleId);
    scheduler.runNow(scheduleId);
    return ApiResponse.ok(
        taskStore.executions(scheduleId, DEFAULT_EXECUTION_LIMIT).stream()
            .map(ExecutionView::from)
            .toList());
  }

  @PutMapping("/schedules/{scheduleId}")
  public ApiResponse<List<ScheduleView>> setEnabled(
      @PathVariable String scheduleId, @RequestBody(required = false) SetEnabledRequest body) {
    if (body == null || body.enabled() == null) {
      throw new IllegalArgumentException("Request body must contain enabled");
    }
    requireSchedule(scheduleId);
    taskStore.setEnabled(scheduleId, body.enabled());
    return list();
  }

  @PostMapping("/agents/{profileName}/schedules/{key}/run")
  public ApiResponse<List<ExecutionView>> runByProfileAndKey(
      @PathVariable String profileName, @PathVariable String key) {
    ScheduledTaskView schedule =
        taskStore.findByKey(key).stream()
            .filter(candidate -> candidate.profileName().equals(profileName))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Schedule key not found for profile: " + profileName + "/" + key));
    scheduler.runNow(profileName, key);
    return ApiResponse.ok(
        taskStore.executions(schedule.scheduleId(), DEFAULT_EXECUTION_LIMIT).stream()
            .map(ExecutionView::from)
            .toList());
  }

  private ScheduledTaskView requireSchedule(String scheduleId) {
    return taskStore.list().stream()
        .filter(schedule -> schedule.scheduleId().equals(scheduleId))
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
  }

  private static int capExecutionLimit(int limit) {
    return limit <= 0 || limit > DEFAULT_EXECUTION_LIMIT ? DEFAULT_EXECUTION_LIMIT : limit;
  }
}
