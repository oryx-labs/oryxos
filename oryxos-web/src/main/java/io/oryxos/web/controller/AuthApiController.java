package io.oryxos.web.controller;

import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AuthMeView;
import io.oryxos.web.controller.dto.LoginRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理台认证端点（012-web-auth US3）：浏览器登录页配套的 session/cookie 认证。
 *
 * <p>归 {@code /api/v1/auth/**} 子树（REST API 不受 {@code /admin/**} filter 拦，这些端点靠 controller 自身校验
 * session）。复用 {@link WebUserService#verify} 验账密 + {@link WebSessionService} 管 session。
 *
 * <p>cookie 属性：{@code oryxos_session} = session id（UUID），HttpOnly + SameSite=Strict + Path=/（HTTPS
 * 时加 Secure）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "auth 端点是有意暴露的 Spring Controller（012-web-auth US3，浏览器登录页配套）；"
            + "userService/sessionService 为 Spring 注入的共享单例，构造注入存同一引用正是意图"
            + "（镜像既有 Controller 的 SuppressFBWarnings 模式）。")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

  /** cookie 名。 */
  static final String SESSION_COOKIE = "oryxos_session";

  private final WebUserService userService;
  private final WebSessionService sessionService;

  public AuthApiController(WebUserService userService, WebSessionService sessionService) {
    this.userService = userService;
    this.sessionService = sessionService;
  }

  /** 登录：验账密 → 建 session → 设 cookie。失败 401（"Invalid username or password"，不区分原因防枚举）。 */
  @PostMapping("/login")
  public ApiResponse<AuthMeView> login(
      @RequestBody LoginRequest loginRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (loginRequest == null
        || loginRequest.username() == null
        || loginRequest.password() == null
        || loginRequest.username().isBlank()
        || loginRequest.password().isBlank()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return ApiResponse.error(
          HttpStatus.BAD_REQUEST.value(), "username and password are required");
    }
    if (!userService.verify(loginRequest.username(), loginRequest.password())) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "Invalid username or password");
    }
    WebSession session = sessionService.create(loginRequest.username());
    response.addHeader(
        HttpHeaders.SET_COOKIE, buildCookie(session.getSessionId(), -1, request.isSecure()));
    return ApiResponse.ok(new AuthMeView(loginRequest.username()));
  }

  /** 登出：清当前 session + 清 cookie。幂等（无 session 也成功）。 */
  @PostMapping("/logout")
  public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    findSessionId(request).ifPresent(sessionService::delete);
    response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", 0, request.isSecure()));
    return ApiResponse.ok(null);
  }

  /** 查当前登录用户。已登录 200 返用户名；未登录 401。 */
  @GetMapping("/me")
  public ApiResponse<AuthMeView> me(HttpServletRequest request, HttpServletResponse response) {
    Optional<String> sessionId = findSessionId(request);
    if (sessionId.isEmpty()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "Not authenticated");
    }
    Optional<WebSession> session = sessionService.findValid(sessionId.get());
    if (session.isEmpty()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "Not authenticated");
    }
    return ApiResponse.ok(new AuthMeView(session.get().getUsername()));
  }

  /** 从请求 cookie 里取 session id。 */
  private Optional<String> findSessionId(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(c -> SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst();
  }

  /** 构 Set-Cookie 头。maxAge=0 表示清 cookie；HTTPS 请求附加 Secure。 */
  private static String buildCookie(String sessionId, int maxAge, boolean secure) {
    ResponseCookie cookie =
        ResponseCookie.from(SESSION_COOKIE, sessionId)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .maxAge(maxAge)
            .build();
    return cookie.toString();
  }
}
