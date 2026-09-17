package io.oryxos.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理台认证过滤器（012-web-auth）。
 *
 * <p>仅拦 {@code /admin/**}（由 {@link AuthFilterConfig} 的 {@code FilterRegistrationBean} 限定 URL 模式）；
 * {@code /api/v1/**} 不在模式内、天然不受影响（FR-002），{@code /api/v1/health} 亦免认证（FR-008）。
 *
 * <p>校验两条路径（任一通过即放行，FR-005）：
 *
 * <ol>
 *   <li>Session——取 {@code oryxos_session} cookie → {@link WebSessionService#findValid} → 有效放行 （不查
 *       user enabled，Clarifications Q1 B：改密/禁用后旧 session 仍有效到过期）。
 *   <li>Basic Auth——取 {@code Authorization: Basic} 头 → Base64 解码拆 user/pass → {@link
 *       WebUserService#verify} → 放行。与表单登录共用 {@link LoginAttemptService}：同「用户名|IP」连续失败超限返回 429，成功清零。
 * </ol>
 *
 * <p>都无/都失败：浏览器（{@code Accept} 头含 {@code text/html}）→ 302 跳 {@code /admin/login}； curl/自动化 → 401
 * JSON（统一信封）+ {@code WWW-Authenticate: Basic realm="<配置值>"}。锁定期内 Basic 失败/已锁 → 429（与 {@code
 * /api/v1/auth/login} 对齐），避免只锁表单、Basic 无限试密。
 *
 * <p>{@code /admin/login} 路径（含其静态资源）放行——未登录也要能访问登录页（FR-017）。
 *
 * <p>{@code auth.enabled=false} 直接放行（默认关，SC-001 回归零破坏）。不抛异常——{@code @RestControllerAdvice} 捕不到
 * filter 异常（filter 在 DispatcherServlet 之前），故直接写响应。
 *
 * <p>039 / #462：认证成功时置 {@link Principal}（角色来自 {@code WebUserService.rolesOf}），供后续特性读取。 {@code
 * /admin/**} 仍只认证不裁决——本类不调用 {@code AuthorizationService} / {@code RbacEnforcer}（数据面走 {@code
 * /api/v1/**}）。
 */
public class BasicAuthFilter extends OncePerRequestFilter {

  /** Basic Auth scheme 前缀（RFC 7617），含尾随空格——凭据紧随其后。P3C：提常量避免魔法值。 */
  private static final String BASIC_PREFIX = "Basic ";

  /** Basic Auth scheme 名（用于 WWW-Authenticate 挑战头）。 */
  private static final String BASIC_SCHEME = "Basic";

  /** user:pass 拆分后的段数。 */
  private static final int BASIC_CREDENTIAL_PARTS = 2;

  /** 401 响应业务码（统一信封 ApiResponse.code）。 */
  private static final int UNAUTHORIZED_CODE = HttpStatus.UNAUTHORIZED.value();

  /** 401 响应文案。 */
  private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";

  /** 锁定期内的 429 文案：与 AuthApiController 对齐，不透露阀值。 */
  private static final String TOO_MANY_ATTEMPTS_MESSAGE =
      "Too many failed login attempts, try again later";

  /** session cookie 名。 */
  static final String SESSION_COOKIE = "oryxos_session";

  /** 登录页路径前缀（放行）。 */
  private static final String LOGIN_PATH = "/admin/login";

  /**
   * SPA 静态资源前缀（放行）：登录页与 SPA 共用一个 shell（{@code index.html} + content-hash 命名的 JS/CSS bundle），
   * 未登录渲染登录表单同样要加载 {@code /admin/assets/**}——拦下它登录页白屏，任何人都登不进去（018 SC-006 浏览器走查发现的缺陷）。bundle
   * 是公开前端代码不含数据，数据面由 {@code /api/v1/**} 各自的认证把守。
   */
  private static final String ASSETS_PATH = "/admin/assets/";

  /** Basic Auth 校验结果（无头/解码失败不计失败次数）。 */
  private enum BasicOutcome {
    NONE,
    LOCKED,
    OK,
    BAD
  }

  private record BasicAuthAttempt(BasicOutcome outcome, String username) {
    static BasicAuthAttempt none() {
      return new BasicAuthAttempt(BasicOutcome.NONE, null);
    }

    static BasicAuthAttempt locked() {
      return new BasicAuthAttempt(BasicOutcome.LOCKED, null);
    }

    static BasicAuthAttempt ok(String username) {
      return new BasicAuthAttempt(BasicOutcome.OK, username);
    }

    static BasicAuthAttempt bad() {
      return new BasicAuthAttempt(BasicOutcome.BAD, null);
    }
  }

  private final WebUserService userService;
  private final WebSessionService sessionService;
  private final WebAuthProperties properties;
  private final ObjectMapper objectMapper;
  private final LoginAttemptService loginAttemptService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "PZLA_PREFER_ZERO_LENGTH_ARRAYS"},
      justification =
          "userService/sessionService/properties/objectMapper/loginAttemptService 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像既有 Controller 的 SuppressFBWarnings 模式）；decode 返 null 表"
              + " \"Base64 解码或 user:pass 拆分失败\"，与零长数组语义不同。")
  public BasicAuthFilter(
      WebUserService userService,
      WebSessionService sessionService,
      WebAuthProperties properties,
      ObjectMapper objectMapper,
      LoginAttemptService loginAttemptService) {
    this.userService = userService;
    this.sessionService = sessionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.loginAttemptService = loginAttemptService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }
    // 登录页路径与 SPA 静态资源放行（FR-017：未登录也要能渲染登录页）
    if (isLoginPath(request.getRequestURI()) || isAssetPath(request.getRequestURI())) {
      filterChain.doFilter(request, response);
      return;
    }
    // (1) session cookie 路径
    Optional<String> sessionUser = sessionUsername(request);
    if (sessionUser.isPresent()) {
      attachUserPrincipal(request, sessionUser.get());
      filterChain.doFilter(request, response);
      return;
    }
    // (2) Basic Auth 路径（含暴力破解锁定）
    BasicAuthAttempt basic = authenticateByBasic(request);
    if (basic.outcome() == BasicOutcome.OK) {
      attachUserPrincipal(request, basic.username());
      filterChain.doFilter(request, response);
      return;
    }
    if (basic.outcome() == BasicOutcome.LOCKED) {
      rejectTooMany(response);
      return;
    }
    // NONE / BAD：浏览器跳登录页，curl 401
    reject(request, response);
  }

  private boolean isLoginPath(String uri) {
    return uri != null && uri.startsWith(LOGIN_PATH);
  }

  private static boolean isAssetPath(String uri) {
    return uri != null && uri.startsWith(ASSETS_PATH);
  }

  private Optional<String> sessionUsername(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(c -> SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .flatMap(sessionService::findValid)
        .map(io.oryxos.storage.WebSession::getUsername);
  }

  private BasicAuthAttempt authenticateByBasic(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BASIC_PREFIX)) {
      return BasicAuthAttempt.none();
    }
    String[] credentials = decode(header.substring(BASIC_PREFIX.length()));
    if (credentials == null || credentials.length != BASIC_CREDENTIAL_PARTS) {
      return BasicAuthAttempt.none();
    }
    String attemptKey = credentials[0] + "|" + ClientIp.peerAddress(request);
    if (loginAttemptService.isBlocked(attemptKey)) {
      return BasicAuthAttempt.locked();
    }
    if (userService.verify(credentials[0], credentials[1])) {
      loginAttemptService.onSuccess(attemptKey);
      return BasicAuthAttempt.ok(credentials[0]);
    }
    loginAttemptService.onFailure(attemptKey);
    return BasicAuthAttempt.bad();
  }

  /** 置主体，不裁决。角色每请求重解析，与 ApiKeyAuthFilter session 分支同源。 */
  private void attachUserPrincipal(HttpServletRequest request, String username) {
    if (username == null || username.isBlank()) {
      return;
    }
    Set<Role> roles = userService.rolesOf(username);
    PrincipalHolder.set(request, Principal.user(username, username, roles));
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (isBrowser(request)) {
      response.setStatus(HttpStatus.FOUND.value());
      response.setHeader(HttpHeaders.LOCATION, LOGIN_PATH);
      return;
    }
    response.setStatus(UNAUTHORIZED_CODE);
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE, BASIC_SCHEME + " realm=\"" + properties.getRealm() + "\"");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            objectMapper.writeValueAsString(
                ApiResponse.error(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE)));
  }

  private void rejectTooMany(HttpServletResponse response) throws IOException {
    int code = HttpStatus.TOO_MANY_REQUESTS.value();
    response.setStatus(code);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(ApiResponse.error(code, TOO_MANY_ATTEMPTS_MESSAGE)));
  }

  /** 浏览器判定：Accept 头含 text/html（Clarifications Q2 A 分流）。 */
  private static boolean isBrowser(HttpServletRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null && accept.contains("text/html");
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
      justification = "decode 返 null 表\"Base64 解码或 user:pass 拆分失败\"，与零长数组语义不同；" + "调用方已判 null。")
  private static String[] decode(String base64) {
    try {
      byte[] decoded = Base64.getDecoder().decode(base64);
      String pair = new String(decoded, StandardCharsets.UTF_8);
      int idx = pair.indexOf(':');
      if (idx < 0) {
        return null;
      }
      return new String[] {pair.substring(0, idx), pair.substring(idx + 1)};
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
