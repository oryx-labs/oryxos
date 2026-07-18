package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.ExecutionView;
import io.oryxos.web.controller.dto.ScheduleView;
import io.oryxos.web.controller.dto.SetEnabledRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理端点（28 节）：把 Agent OS 里"到点自己跑"的任务做成可查可管的一等公民。四件事——列任务、查执行历史、立即执行一次、启用/停用。
 *
 * <p>只读查询走 {@link ScheduledTaskStore}（SQLite 里持久化的状态与历史，重启仍在）；"立即执行"与开关是对运行时的操作，走 {@link
 * AgentScheduler}（手动触发一次，无视启用状态）与 store 的开关。Controller 只做校验/包装/兜错，不夹带调度逻辑。
 *
 * <p>安全边界：核心阶段 Web API 无认证（假设内网）。"立即执行"等于远程触发一次真实 ReAct，务必靠网络层隔离兜底。
 */
@RestController
@RequestMapping("/api/v1/schedules")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "SPRING_ENDPOINT"},
    justification =
        "EI_EXPOSE_REP2：store/scheduler 是 Spring 注入的单例服务，共享引用正是意图。"
            + "SPRING_ENDPOINT：核心阶段 Web API 无认证是设计前提（假设内网 + 网络层兜底），"
            + "已在类 Javadoc 注明；端点鉴权属扩展阶段。")
public class ScheduleApiController {

  /** 执行历史默认返回条数上限（防呆，跟会话历史 100 条同一思路）。 */
  private static final int DEFAULT_EXECUTION_LIMIT = 100;

  private final ScheduledTaskStore taskStore;
  private final AgentScheduler scheduler;

  public ScheduleApiController(ScheduledTaskStore taskStore, AgentScheduler scheduler) {
    this.taskStore = taskStore;
    this.scheduler = scheduler;
  }

  /** 列出全部定时任务及其运行状态。 */
  @GetMapping
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(taskStore.list().stream().map(ScheduleView::from).toList());
  }

  /** 查某任务最近的执行历史（默认最多 100 条）。 */
  @GetMapping("/{id}/executions")
  public ApiResponse<List<ExecutionView>> executions(
      @PathVariable String id,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_EXECUTION_LIMIT) int limit) {
    int capped = limit <= 0 || limit > DEFAULT_EXECUTION_LIMIT ? DEFAULT_EXECUTION_LIMIT : limit;
    return ApiResponse.ok(
        taskStore.executions(id, capped).stream().map(ExecutionView::from).toList());
  }

  /** 立即执行一次（手动触发，无视启用状态）。任务不存在时 scheduler 抛 IllegalArgumentException→400。 */
  @PostMapping("/{id}/run")
  public ApiResponse<List<ExecutionView>> run(@PathVariable String id) {
    scheduler.runNow(id);
    // 执行完把这次（及历史）结果回给调用方，省一次二次查询
    return ApiResponse.ok(
        taskStore.executions(id, DEFAULT_EXECUTION_LIMIT).stream()
            .map(ExecutionView::from)
            .toList());
  }

  /** 启用/停用一条定时任务。 */
  @PutMapping("/{id}")
  public ApiResponse<List<ScheduleView>> setEnabled(
      @PathVariable String id, @RequestBody(required = false) SetEnabledRequest body) {
    if (body == null || body.enabled() == null) {
      throw new IllegalArgumentException("请求体缺少 enabled（true 启用 / false 停用）");
    }
    taskStore.setEnabled(id, body.enabled());
    return ApiResponse.ok(taskStore.list().stream().map(ScheduleView::from).toList());
  }
}
