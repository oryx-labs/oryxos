package io.oryxos.web.controller;

import io.oryxos.core.task.TeamTaskOrchestrator;
import io.oryxos.core.task.TeamTaskResult;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.TeamTaskRequest;
import io.oryxos.web.controller.dto.TeamTaskView;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Direction I MVP: natural-language goal → coordinator plan → specialist fan-out. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects orchestrator; same pattern as other controllers.")
@RestController
@RequestMapping("/api/v1/team-tasks")
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，端点 404）。
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "oryxos.task.team",
    name = "enabled",
    havingValue = "true")
public class TeamTaskApiController {

  private final TeamTaskOrchestrator orchestrator;

  public TeamTaskApiController(TeamTaskOrchestrator orchestrator) {
    this.orchestrator = Objects.requireNonNull(orchestrator);
  }

  @PostMapping
  public ApiResponse<TeamTaskView> create(@RequestBody TeamTaskRequest req) {
    if (req == null || req.goal() == null || req.goal().isBlank()) {
      throw new IllegalArgumentException("goal is required");
    }
    TeamTaskResult result = orchestrator.run(req.goal(), req.coordinator());
    return ApiResponse.ok(TeamTaskView.from(result));
  }

  @GetMapping
  public ApiResponse<List<TeamTaskView>> list(
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    List<TeamTaskView> views =
        orchestrator.listRecent(limit).stream().map(TeamTaskView::from).toList();
    return ApiResponse.ok(views);
  }

  @GetMapping("/{id}")
  public ApiResponse<TeamTaskView> get(@PathVariable("id") String id) {
    return ApiResponse.ok(
        TeamTaskView.from(
            orchestrator
                .find(id)
                .orElseThrow(() -> new ResourceNotFoundException("team-task not found: " + id))));
  }
}
