package io.oryxos.web.controller;

import io.oryxos.core.flow.FlowDraftAuthor;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.FlowDraftView;
import io.oryxos.web.controller.dto.GenerateFlowRequest;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direction C：自然语言 → Flow 草稿（不落盘）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects author; same pattern as TeamTaskApiController.")
@RestController
@RequestMapping("/api/v1/flows")
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，端点 404）。
// 它依赖的 bean 无条件创建，所以这里也不设条件。
public class FlowApiController {

  private final FlowDraftAuthor author;

  public FlowApiController(FlowDraftAuthor author) {
    this.author = Objects.requireNonNull(author);
  }

  @PostMapping("/generate")
  public ApiResponse<FlowDraftView> generate(@RequestBody GenerateFlowRequest req) {
    if (req == null || req.goal() == null || req.goal().isBlank()) {
      throw new IllegalArgumentException("goal is required");
    }
    return ApiResponse.ok(FlowDraftView.from(author.generate(req.id(), req.goal())));
  }
}
