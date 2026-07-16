package io.oryxos.web.controller;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.InfoView;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态：健康检查 + 运行信息。providers 取"已加载 Profile 引用到的 Provider 名单"（core-only 可见口径； 核心阶段不做 live
 * 探活——连通性以"已配置"为准，真实探活留扩展阶段）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase")
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {

  private final ProfileRegistry profileRegistry;

  public SystemApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  @GetMapping("/health")
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.ok(Map.of("status", "ok"));
  }

  @GetMapping("/info")
  public ApiResponse<InfoView> info() {
    List<String> providers =
        profileRegistry.all().stream()
            .map(Profile::provider)
            .filter(p -> p != null && p.name() != null)
            .map(Profile.ProviderRef::name)
            .distinct()
            .sorted()
            .toList();
    return ApiResponse.ok(new InfoView("oryxos", providers));
  }
}
