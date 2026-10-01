package io.oryxos.web.controller;

import io.oryxos.core.capability.CapabilityCatalog;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CapabilityView;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direction C：统一能力目录（装配 / Flow 搭积木）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects catalog; same pattern as other list controllers.")
@RestController
@RequestMapping("/api/v1/capabilities")
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，端点 404）。
public class CapabilityApiController {

  private final CapabilityCatalog catalog;

  public CapabilityApiController(CapabilityCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  @GetMapping
  public ApiResponse<List<CapabilityView>> list() {
    return ApiResponse.ok(catalog.list().stream().map(CapabilityView::from).toList());
  }
}
