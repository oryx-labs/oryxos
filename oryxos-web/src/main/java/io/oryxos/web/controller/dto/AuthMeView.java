package io.oryxos.web.controller.dto;

import java.util.Set;

/**
 * 当前登录用户信息（012-web-auth US3，GET /api/v1/auth/me 返回）。
 *
 * <p>{@code roles} 来自 {@code WebUserService.rolesOf}，与 Filter 解析主体用的是同一来源。不在这里调用
 * AuthorizationService。认证关闭或未登录时为空集。
 */
public record AuthMeView(boolean authenticationEnabled, String username, Set<String> roles) {

  public AuthMeView {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** 不带角色的既有调用点。 */
  public AuthMeView(boolean authenticationEnabled, String username) {
    this(authenticationEnabled, username, Set.of());
  }
}
