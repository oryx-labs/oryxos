package io.oryxos.web.oidc;

import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.AuthApiController;
import io.oryxos.web.controller.dto.AuthMeView;
import io.oryxos.web.oidc.OidcAuthService.OidcLoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC 登录端点（040 / #461）：挂在 {@code /api/v1/auth/oidc/**}，已由 {@code ApiKeyAuthFilter} 豁免。
 *
 * <p>本 Controller 不注入 {@code AuthorizationService}——callback 绝不做授权裁决。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "OIDC 登录端点有意暴露；oidcAuthService 为 Spring 注入共享单例，存同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcAuthController {

  private static final String ADMIN_LANDING = "/admin/";

  private final OidcAuthService oidcAuthService;

  public OidcAuthController(OidcAuthService oidcAuthService) {
    this.oidcAuthService = oidcAuthService;
  }

  /** 开始 SSO：关闭时 404；开启时 302 到 IdP。 */
  @GetMapping("/login")
  public void login(HttpServletResponse response) throws IOException {
    if (!oidcAuthService.isEnabled()) {
      response.sendError(HttpStatus.NOT_FOUND.value());
      return;
    }
    response.sendRedirect(oidcAuthService.beginLogin());
  }

  /**
   * IdP 回调：失败 401；成功设 {@code oryxos_session}。浏览器默认 302 {@code /admin/}；{@code Accept:
   * application/json} 时返回 JSON（测试友好）。
   */
  @GetMapping("/callback")
  public Object callback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (!oidcAuthService.isEnabled()) {
      response.sendError(HttpStatus.NOT_FOUND.value());
      return null;
    }
    OidcLoginResult result = oidcAuthService.completeLogin(code, state);
    if (!result.isSuccess()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "OIDC login failed");
    }
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        buildCookie(result.getSession().getSessionId(), -1, request.isSecure()));
    if (wantsJson(accept)) {
      return ApiResponse.ok(new AuthMeView(true, result.getUsername()));
    }
    response.sendRedirect(ADMIN_LANDING);
    return null;
  }

  private static boolean wantsJson(String accept) {
    if (accept == null || accept.isBlank()) {
      return false;
    }
    return accept.toLowerCase(java.util.Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE);
  }

  /** 与 {@link AuthApiController} 登录 cookie 属性对齐。 */
  private static String buildCookie(String sessionId, int maxAge, boolean secure) {
    ResponseCookie cookie =
        ResponseCookie.from(AuthApiController.SESSION_COOKIE, sessionId)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .maxAge(maxAge)
            .build();
    return cookie.toString();
  }
}
