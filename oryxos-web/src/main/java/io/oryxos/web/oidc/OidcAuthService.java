package io.oryxos.web.oidc;

import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.AuthEventType;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebOidcProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OIDC 授权码 + PKCE 登录编排（040 / #461）。
 *
 * <p><b>刻意不依赖</b>{@code AuthorizationService}——callback 只做认证、映射与建 {@link WebSession}；授权留给既有
 * session→Principal→Filter 路径（#462）。角色比对同样禁止出现在本类。
 */
public class OidcAuthService {

  private static final Logger LOG = LoggerFactory.getLogger(OidcAuthService.class);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final WebOidcProperties properties;
  private final OidcTokenClient tokenClient;
  private final OidcPendingStore pendingStore;
  private final IdentityMappingService mappingService;
  private final WebUserService userService;
  private final WebSessionService sessionService;
  private final AuthEventRecorder authEventRecorder;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder) {
    this.properties = properties;
    this.tokenClient = tokenClient;
    this.pendingStore = pendingStore;
    this.mappingService = mappingService;
    this.userService = userService;
    this.sessionService = sessionService;
    this.authEventRecorder = authEventRecorder;
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /** 生成 state+PKCE，返回 IdP authorize URL。 */
  public String beginLogin() {
    String state = randomUrlSafe(32);
    String verifier = randomUrlSafe(32);
    pendingStore.put(state, verifier);
    String challenge = s256Challenge(verifier);
    String authorize = tokenClient.resolveAuthorizationEndpoint(properties);
    String scopes =
        properties.getScopes() == null || properties.getScopes().isBlank()
            ? "openid profile email"
            : properties.getScopes().strip();
    return authorize
        + (authorize.contains("?") ? "&" : "?")
        + "response_type=code"
        + "&client_id="
        + enc(properties.getClientId())
        + "&redirect_uri="
        + enc(properties.getRedirectUri())
        + "&scope="
        + enc(scopes)
        + "&state="
        + enc(state)
        + "&code_challenge="
        + enc(challenge)
        + "&code_challenge_method=S256";
  }

  /**
   * 完成 callback：校验 state → 换码验 token → 查映射 → 验本地用户启用 → 建 session。
   *
   * <p>LOGIN_SUCCESS 审计走 {@link AuthEventRecorder#recordOrThrow}：写失败则拒绝建 session（fail-closed）。
   */
  public OidcLoginResult completeLogin(String code, String state) {
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      fail("missing_code_or_state", null);
      return OidcLoginResult.failure("missing code or state");
    }
    Optional<OidcPendingLogin> pending = pendingStore.take(state);
    if (pending.isEmpty()) {
      fail("invalid_or_expired_state", null);
      return OidcLoginResult.failure("invalid or expired state");
    }
    OidcIdTokenClaims claims;
    try {
      claims = tokenClient.exchangeAndValidate(code, pending.get().codeVerifier(), properties);
    } catch (RuntimeException ex) {
      LOG.warn("OIDC token 路径失败：{}", ex.toString());
      fail("token_exchange_or_validation_failed", null);
      return OidcLoginResult.failure("OIDC token exchange or validation failed");
    }
    Optional<IdentityMapping> mapping =
        mappingService.findByIssuerAndSubject(claims.issuer(), claims.subject());
    if (mapping.isEmpty()) {
      fail("unmapped_subject", claims.subject());
      return OidcLoginResult.failure("identity not mapped");
    }
    String username = mapping.get().getUsername();
    if (!userService.isEnabledUser(username)) {
      fail("user_missing_or_disabled", username);
      return OidcLoginResult.failure("user missing or disabled");
    }
    try {
      authEventRecorder.recordOrThrow(
          AuthEventType.LOGIN_SUCCESS, username, "oidc issuer=" + claims.issuer());
    } catch (RuntimeException ex) {
      LOG.error("LOGIN_SUCCESS 审计失败，拒绝建 session：{}", ex.toString());
      return OidcLoginResult.failure("auth audit failed");
    }
    WebSession session = sessionService.create(username);
    return OidcLoginResult.success(session, username);
  }

  private void fail(String detail, String principalId) {
    authEventRecorder.recordBestEffort(AuthEventType.LOGIN_FAILURE, principalId, detail);
  }

  private static String s256Challenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private static String randomUrlSafe(int bytes) {
    byte[] buf = new byte[bytes];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  /** callback 结果：成功带 session；失败带可读原因（对外可折叠为统一 401）。 */
  public static final class OidcLoginResult {
    private final boolean success;
    private final WebSession session;
    private final String username;
    private final String error;

    private OidcLoginResult(boolean success, WebSession session, String username, String error) {
      this.success = success;
      this.session = session;
      this.username = username;
      this.error = error;
    }

    public static OidcLoginResult success(WebSession session, String username) {
      return new OidcLoginResult(true, session, username, null);
    }

    public static OidcLoginResult failure(String error) {
      return new OidcLoginResult(false, null, null, error);
    }

    public boolean isSuccess() {
      return success;
    }

    public WebSession getSession() {
      return session;
    }

    public String getUsername() {
      return username;
    }

    public String getError() {
      return error;
    }

    @Override
    public String toString() {
      return "OidcLoginResult{success="
          + success
          + ", username='"
          + username
          + "', error='"
          + (error == null ? "" : error.toLowerCase(Locale.ROOT))
          + "'}";
    }
  }
}
