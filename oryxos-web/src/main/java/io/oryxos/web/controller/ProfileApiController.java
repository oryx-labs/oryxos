package io.oryxos.web.controller;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.ProfileView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读：列出已加载的 Profile（投影出可展示字段）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase")
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileApiController {

  private final ProfileRegistry profileRegistry;

  public ProfileApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  @GetMapping
  public ApiResponse<List<ProfileView>> list() {
    List<ProfileView> views =
        profileRegistry.all().stream().map(ProfileApiController::toView).toList();
    return ApiResponse.ok(views);
  }

  private static ProfileView toView(Profile p) {
    Profile.ProviderRef provider = p.provider();
    return new ProfileView(
        p.name(),
        p.description(),
        provider == null ? null : provider.name(),
        provider == null ? null : provider.model(),
        p.tools(),
        p.skills());
  }
}
