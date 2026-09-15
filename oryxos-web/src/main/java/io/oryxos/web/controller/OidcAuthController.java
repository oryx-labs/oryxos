package io.oryxos.web.controller;

import com.nimbusds.jwt.JWTClaimsSet;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.OidcAuthRequestStore;
import io.oryxos.storage.OidcIdentityService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.security.ClientIp;
import io.oryxos.web.security.oidc.IdTokenValidator;
import io.oryxos.web.security.oidc.OidcClient;
import io.oryxos.web.security.oidc.OidcErrorCode;
import io.oryxos.web.security.oidc.OidcFlowException;
import io.oryxos.web.security.oidc.OidcRoleResolver;
import io.oryxos.web.security.oidc.OidcUsernameDeriver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC 登录端点（040 US1）：授权码 + PKCE。归 {@code /api/v1/auth/**} 既有豁免子树——ApiKeyAuthFilter 豁免清单与
 * RequestActionResolver skip 表零改动（契约 §1）。
 *
 * <p>浏览器上下文：成败均以 302 收尾——成功建 session 后跳 {@code /admin/}；任何失败跳 {@code
 * /admin/login?error=<分类>}（分类枚举是对外错误的全部信息量，令牌内容 NEVER 外泄）并落 login_failure 审计。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2", "UNVALIDATED_REDIRECT", "CRLF_INJECTION_LOGS"},
    justification =
        "OIDC 端点是有意暴露的 Controller（040 US1）；协作对象为 Spring 注入共享单例；"
            + "重定向目标只有两类：固定站内路径（/admin/ 前缀 + 枚举错误码）与 discovery 解析出的"
            + " IdP authorize 端点（issuer 来自管理员配置，非用户输入）；"
            + "日志只含 OidcErrorCode 枚举串，无外部可控内容。")
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcAuthController {

  static final String SESSION_COOKIE = "oryxos_session";

  private static final Logger LOG = LoggerFactory.getLogger(OidcAuthController.class);

  private static final String LOGIN_ERROR_PREFIX = "/admin/login?error=";

  private final WebOidcProperties properties;
  private final OidcClient oidcClient;
  private final IdTokenValidator idTokenValidator;
  private final OidcRoleResolver roleResolver;
  private final OidcAuthRequestStore authRequestStore;
  private final OidcIdentityService identityService;
  private final WebSessionService sessionService;
  private final AuthEventRecorder authEventRecorder;

  public OidcAuthController(
      WebOidcProperties properties,
      OidcClient oidcClient,
      IdTokenValidator idTokenValidator,
      OidcRoleResolver roleResolver,
      OidcAuthRequestStore authRequestStore,
      OidcIdentityService identityService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder) {
    this.properties = properties;
    this.oidcClient = oidcClient;
    this.idTokenValidator = idTokenValidator;
    this.roleResolver = roleResolver;
    this.authRequestStore = authRequestStore;
    this.identityService = identityService;
    this.sessionService = sessionService;
    this.authEventRecorder = authEventRecorder;
  }

  /** 发起登录：生成 state/nonce/PKCE 落库 → 302 到 IdP authorize 端点。未启用 404。 */
  @GetMapping("/login")
  public void login(HttpServletResponse response) throws IOException {
    if (!properties.isEnabled()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return;
    }
    String url;
    try {
      OidcAuthRequestStore.PendingAuth pending = authRequestStore.create();
      url =
          oidcClient.authorizeUrl(
              pending.state(), pending.nonce(), codeChallengeS256(pending.pkceVerifier()));
    } catch (OidcFlowException ex) {
      LOG.warn("OIDC 登录发起失败：{}", ex.code().code());
      response.sendRedirect(LOGIN_ERROR_PREFIX + ex.code().code());
      return;
    }
    response.sendRedirect(url);
  }

  /** 回调：契约七步处理序；任一步失败 302 登录页带分类 + login_failure 审计。 */
  @GetMapping("/callback")
  public void callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (!properties.isEnabled()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return;
    }
    String sourceIp = ClientIp.peerAddress(request);
    try {
      if (error != null && !error.isBlank()) {
        throw new OidcFlowException(OidcErrorCode.IDP_ERROR, "IdP 返回错误参数");
      }
      OidcAuthRequestStore.PendingAuth pending =
          authRequestStore
              .consume(state)
              .orElseThrow(
                  () -> new OidcFlowException(OidcErrorCode.INVALID_STATE, "state 无效或已消费"));
      if (code == null || code.isBlank()) {
        throw new OidcFlowException(OidcErrorCode.IDP_ERROR, "回调缺少授权码");
      }
      String idToken = oidcClient.exchangeCodeForIdToken(code, pending.pkceVerifier());
      JWTClaimsSet claims = idTokenValidator.validate(idToken, pending.nonce());
      completeLogin(claims, request, response, sourceIp);
    } catch (OidcFlowException ex) {
      LOG.warn("OIDC 登录失败：{}", ex.code().code());
      authEventRecorder.record(
          new AuthEventRecorder.AuthEventData(
              AuthEventRecorder.LOGIN_FAILURE,
              AuthEventRecorder.METHOD_OIDC,
              null,
              properties.getIssuer(),
              null,
              null,
              ex.code().code(),
              sourceIp));
      response.sendRedirect(LOGIN_ERROR_PREFIX + ex.code().code());
    }
  }

  private void completeLogin(
      JWTClaimsSet claims,
      HttpServletRequest request,
      HttpServletResponse response,
      String sourceIp)
      throws IOException {
    String subject = claims.getSubject();
    String issuer = claims.getIssuer();
    String email = optionalStringClaim(claims, "email");
    String preferredUsername = optionalStringClaim(claims, "preferred_username");
    Set<Role> claimRoles = roleResolver.rolesFrom(claims);
    OidcIdentityService.OidcLoginResult result;
    try {
      result =
          identityService.login(
              issuer,
              subject,
              email,
              OidcUsernameDeriver.derive(preferredUsername, email, subject),
              claimRoles,
              roleResolver.provisionDefaults());
    } catch (RuntimeException ex) {
      LOG.warn("OIDC 身份映射/供给失败：{}", ex.getClass().getSimpleName());
      throw new OidcFlowException(OidcErrorCode.PROVISIONING_FAILED, "身份映射失败", ex);
    }
    // 重新登录废旧 session（镜像 AuthApiController.login）。
    findSessionId(request).ifPresent(sessionService::delete);
    WebSession session = sessionService.create(result.username());
    response.addHeader(
        HttpHeaders.SET_COOKIE, buildCookie(session.getSessionId(), -1, request.isSecure()));
    String rolesCsv = rolesCsv(result.roles());
    authEventRecorder.record(
        new AuthEventRecorder.AuthEventData(
            AuthEventRecorder.LOGIN_SUCCESS,
            AuthEventRecorder.METHOD_OIDC,
            result.username(),
            issuer,
            subject,
            rolesCsv,
            null,
            sourceIp));
    if (result.firstLogin()) {
      authEventRecorder.record(
          new AuthEventRecorder.AuthEventData(
              AuthEventRecorder.MAPPING_CREATED,
              AuthEventRecorder.METHOD_OIDC,
              result.username(),
              issuer,
              subject,
              rolesCsv,
              null,
              sourceIp));
    } else if (result.rolesChanged()) {
      authEventRecorder.record(
          new AuthEventRecorder.AuthEventData(
              AuthEventRecorder.ROLES_CHANGED,
              AuthEventRecorder.METHOD_OIDC,
              result.username(),
              issuer,
              subject,
              rolesCsv,
              null,
              sourceIp));
    }
    writeInterstitial(response);
  }

  /**
   * 成功落地用站内中转页而非 302：回调处于「IdP → OryxOS」跨站重定向链上，SameSite=Strict 的 session cookie 不随跨站链上的后续跳转发送（经典
   * OAuth 回调坑——用户落地 /admin/ 看似未登录， 刷新才生效）。中转页由本站发起下一跳（location.replace），导航变同站，Strict cookie 正常随行——
   * 不为 OIDC 降级 cookie 的 SameSite 策略。页面零用户数据。
   */
  private static void writeInterstitial(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.OK.value());
    response.setContentType("text/html;charset=utf-8");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response
        .getWriter()
        .write(
            "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=/admin/\">"
                + "<title>OryxOS</title></head><body>"
                + "<script>location.replace('/admin/');</script>"
                + "<p>登录成功，正在进入管理台…<a href=\"/admin/\">点此继续</a></p>"
                + "</body></html>");
  }

  /** PKCE S256：challenge = BASE64URL(SHA-256(ASCII(verifier)))。 */
  static String codeChallengeS256(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException ex) {
      // JDK 必有 SHA-256；防御性转不可达异常。
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /** 固定 VIEWER→EDITOR→ADMIN（枚举声明序）输出，与 WebUserService 序列化同序。 */
  private static String rolesCsv(Set<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return "";
    }
    return roles.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
  }

  private static String optionalStringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException ex) {
      return null;
    }
  }

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
