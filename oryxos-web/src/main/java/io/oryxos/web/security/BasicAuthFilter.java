package io.oryxos.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 *       WebUserService#verify} → 放行。
 * </ol>
 *
 * <p>都无/都失败：浏览器（{@code Accept} 头含 {@code text/html}）→ 302 跳 {@code /admin/login}； curl/自动化 → 401
 * JSON（统一信封）+ {@code WWW-Authenticate: Basic realm="<配置值>"}。
 *
 * <p>{@code /admin/login} 路径（含其静态资源）放行——未登录也要能访问登录页（FR-017）。
 *
 * <p>{@code auth.enabled=false} 直接放行（默认关，SC-001 回归零破坏）。不抛异常——{@code @RestControllerAdvice} 捕不到
 * filter 异常（filter 在 DispatcherServlet 之前），故直接写响应。
 */
public class BasicAuthFilter extends OncePerRequestFilter {

  /** Basic Auth scheme 前缀（RFC 7617），含尾随空格——凭据紧随其后。P3C：提常量避免魔法值。 */
  private static final String BASIC_PREFIX = "Basic ";

  /** Basic Auth scheme 名（用于 WWW-Authenticate 挑战头）。 */
  private static final String BASIC_SCHEME = "Basic";

  /** 401 响应业务码（统一信封 ApiResponse.code）。 */
  private static final int UNAUTHORIZED_CODE = HttpStatus.UNAUTHORIZED.value();

  /** 401 响应文案。 */
  private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";

  /** session cookie 名。 */
  static final String SESSION_COOKIE = "oryxos_session";

  /** 登录页路径前缀（放行）。 */
  private static final String LOGIN_PATH = "/admin/login";

  private final WebUserService userService;
  private final WebSessionService sessionService;
  private final WebAuthProperties properties;
  private final ObjectMapper objectMapper;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "PZLA_PREFER_ZERO_LENGTH_ARRAYS"},
      justification =
          "userService/sessionService/properties/objectMapper 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像既有 Controller 的 SuppressFBWarnings 模式）；decode 返 null 表"
              + " \"Base64 解码或 user:pass 拆分失败\"，与零长数组语义不同。")
  public BasicAuthFilter(
      WebUserService userService,
      WebSessionService sessionService,
      WebAuthProperties properties,
      ObjectMapper objectMapper) {
    this.userService = userService;
    this.sessionService = sessionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }
    // 登录页路径放行（FR-017）
    if (isLoginPath(request.getRequestURI())) {
      filterChain.doFilter(request, response);
      return;
    }
    // (1) session cookie 路径
    if (authenticatedBySession(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    // (2) Basic Auth 路径
    if (authenticatedByBasic(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    // 都无/都失败：浏览器跳登录页，curl 401
    reject(request, response);
  }

  private boolean isLoginPath(String uri) {
    return uri != null && uri.startsWith(LOGIN_PATH);
  }

  private boolean authenticatedBySession(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    return Arrays.stream(cookies)
        .filter(c -> SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .flatMap(sessionService::findValid)
        .isPresent();
  }

  private boolean authenticatedByBasic(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BASIC_PREFIX)) {
      return false;
    }
    String[] credentials = decode(header.substring(BASIC_PREFIX.length()));
    return credentials != null
        && credentials.length == 2
        && userService.verify(credentials[0], credentials[1]);
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
