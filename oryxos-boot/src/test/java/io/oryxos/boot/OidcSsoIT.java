package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.AuthEventRepository;
import io.oryxos.storage.OidcIdentityRepository;
import io.oryxos.storage.WebUserService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * 040 端到端（进 #453 integration-tests 门禁）：真实 HTTP + SQLite + <b>内嵌 mock IdP</b>（discovery/JWKS/token
 * 三端点 + 测试侧 RSA 签发 ID Token）——授权码 + PKCE 全流程、五类协议错误注入、映射稳定性、条件权威角色刷新、 认证审计、默认档零回归。无外部依赖（Keycloak
 * 真机走查见 quickstart.md）。
 *
 * <p>开关策略镜像 {@code ApiKeyAuthE2ETest}：启动时全关（默认档），运行期置 properties 单例——filter/controller 每请求读 {@code
 * isEnabled()}，与生产开启态行为一致；避免 Auth/Oidc StartupCheck 在无账号/无配置时 fail-fast。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcSsoIT {

  private static final Path ROOT = seedWorkspace();
  private static final MockIdp IDP = MockIdp.start();

  @Autowired private io.oryxos.web.config.WebAuthProperties authProperties;
  @Autowired private io.oryxos.web.config.WebOidcProperties oidcProperties;
  @Autowired private OidcIdentityRepository identityRepository;
  @Autowired private AuthEventRepository authEventRepository;
  @Autowired private WebUserService userService;
  @LocalServerPort private int port;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-oidc-it");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents"));
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oidc-it.db"));
  }

  @AfterAll
  static void stopIdp() {
    IDP.stop();
  }

  // ---------------------------------------------------------------- helpers

  private String base() {
    return "http://localhost:" + port;
  }

  /** 不追重定向的 RestTemplate：302 的 Location 是断言对象本身。 */
  private RestTemplate noRedirect() {
    SimpleClientHttpRequestFactory factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String httpMethod)
              throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
          }
        };
    RestTemplate template = new RestTemplate(factory);
    template.setErrorHandler(
        new org.springframework.web.client.DefaultResponseErrorHandler() {
          @Override
          public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
          }
        });
    return template;
  }

  private void enableOidc() {
    authProperties.setEnabled(true);
    oidcProperties.setEnabled(true);
    oidcProperties.setIssuer(IDP.issuer());
    oidcProperties.setClientId("oryxos-admin");
    oidcProperties.setClientSecret("it-secret");
    oidcProperties.setRedirectBaseUrl(base());
    oidcProperties.setRolesClaim("groups");
    oidcProperties.setRoleMappings(Map.of("oryxos-admins", "ADMIN", "oryxos-viewers", "VIEWER"));
  }

  private static Map<String, String> queryParams(String url) {
    Map<String, String> params = new HashMap<>();
    String query = URI.create(url).getRawQuery();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      params.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  /** 走完「发起 → 解析授权参数 → 模拟 IdP 放行 → 回调」的完整往返，返回回调响应。 */
  private ResponseEntity<String> loginRoundTrip(String sub, String preferred, List<String> groups) {
    ResponseEntity<String> initiate =
        noRedirect().getForEntity(base() + "/api/v1/auth/oidc/login", String.class);
    assertEquals(HttpStatus.FOUND, initiate.getStatusCode());
    String location = initiate.getHeaders().getFirst("Location");
    assertNotNull(location);
    assertTrue(location.startsWith(IDP.issuer() + "/authorize"), "应跳 IdP authorize：" + location);
    Map<String, String> params = queryParams(location);
    assertEquals("S256", params.get("code_challenge_method"));
    assertEquals("oryxos-admin", params.get("client_id"));
    IDP.arm(params.get("nonce"), params.get("code_challenge"), sub, preferred, groups);
    return noRedirect()
        .getForEntity(
            params.get("redirect_uri") + "?code=mock-code&state=" + params.get("state"),
            String.class);
  }

  private String sessionCookie(ResponseEntity<String> response) {
    List<String> cookies = response.getHeaders().get("Set-Cookie");
    assertNotNull(cookies);
    return cookies.stream()
        .filter(c -> c.startsWith("oryxos_session="))
        .findFirst()
        .map(c -> c.split(";")[0])
        .orElseThrow();
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.add("Cookie", cookie);
    return noRedirect()
        .exchange(
            base() + path,
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(headers),
            String.class);
  }

  // ---------------------------------------------------------------- tests

  @Test
  @Order(1)
  void defaultOff_zeroChange() {
    ResponseEntity<String> login =
        noRedirect().getForEntity(base() + "/api/v1/auth/oidc/login", String.class);
    assertEquals(HttpStatus.NOT_FOUND, login.getStatusCode());
    ResponseEntity<String> options =
        noRedirect().getForEntity(base() + "/api/v1/auth/login-options", String.class);
    assertEquals(HttpStatus.OK, options.getStatusCode());
    assertTrue(options.getBody().contains("\"oidcEnabled\":false"));
  }

  @Test
  @Order(2)
  void fullLogin_adminGroup_sessionAndAuditAndRoles() {
    enableOidc();
    ResponseEntity<String> callback =
        loginRoundTrip("sub-alice", "Alice", List.of("oryxos-admins"));

    // 成功：200 站内中转页（非 302——SameSite=Strict 跨站链坑），带 session cookie
    assertEquals(HttpStatus.OK, callback.getStatusCode());
    assertTrue(callback.getBody().contains("/admin/"));
    String cookie = sessionCookie(callback);

    // 凭 cookie 访问 /auth/me = 已登录，身份为映射用户
    ResponseEntity<String> me = getWithCookie("/api/v1/auth/me", cookie);
    assertEquals(HttpStatus.OK, me.getStatusCode());
    assertTrue(me.getBody().contains("\"username\":\"alice\""));

    // claim 命中 → ADMIN 落 web_users（SC-003 与 #462 衔接的角色落点）
    assertEquals(Set.of(Role.ADMIN), userService.rolesOf("alice"));

    // 映射行 + 审计（login_success + mapping_created）
    assertEquals(1, identityRepository.findByUsername("alice").size());
    assertTrue(
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("login_success").stream()
            .anyMatch(
                e ->
                    "alice".equals(e.getUsername()) && "sub-alice".equals(e.getExternalSubject())));
    assertEquals(
        1,
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("mapping_created").stream()
            .filter(e -> "alice".equals(e.getUsername()))
            .count());
  }

  @Test
  @Order(3)
  void secondLogin_stableMapping_noDuplicateMappingEvent() {
    ResponseEntity<String> callback =
        loginRoundTrip("sub-alice", "Alice", List.of("oryxos-admins"));
    assertEquals(HttpStatus.OK, callback.getStatusCode());
    // 同 sub 恒同用户：映射行不增
    assertEquals(1, identityRepository.findByUsername("alice").size());
    assertEquals(
        1,
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("mapping_created").stream()
            .filter(e -> "alice".equals(e.getUsername()))
            .count());
  }

  @Test
  @Order(4)
  void rolesRefresh_conditionalAuthority() {
    // 未命中（组清空）→ 保留本地 ADMIN（R14 本地权威分支）
    assertEquals(HttpStatus.OK, loginRoundTrip("sub-alice", "Alice", List.of()).getStatusCode());
    assertEquals(Set.of(Role.ADMIN), userService.rolesOf("alice"));

    // 命中 VIEWER → 覆写降权（IdP 权威撤组即降权）+ roles_changed 审计
    assertEquals(
        HttpStatus.OK,
        loginRoundTrip("sub-alice", "Alice", List.of("oryxos-viewers")).getStatusCode());
    assertEquals(Set.of(Role.VIEWER), userService.rolesOf("alice"));
    assertTrue(
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("roles_changed").stream()
            .anyMatch(e -> "alice".equals(e.getUsername()) && "VIEWER".equals(e.getRoles())));
  }

  @Test
  @Order(5)
  void jitProvision_noGroups_defaultViewer() {
    ResponseEntity<String> callback = loginRoundTrip("sub-bob", "Bob", List.of());
    assertEquals(HttpStatus.OK, callback.getStatusCode());
    assertEquals(Set.of(Role.VIEWER), userService.rolesOf("bob"));
  }

  @Test
  @Order(6)
  void replayedState_rejected() {
    ResponseEntity<String> initiate =
        noRedirect().getForEntity(base() + "/api/v1/auth/oidc/login", String.class);
    Map<String, String> params = queryParams(initiate.getHeaders().getFirst("Location"));
    IDP.arm(params.get("nonce"), params.get("code_challenge"), "sub-alice", "Alice", List.of());
    String callbackUrl =
        params.get("redirect_uri") + "?code=mock-code&state=" + params.get("state");

    assertEquals(
        HttpStatus.OK, noRedirect().getForEntity(callbackUrl, String.class).getStatusCode());
    // 同 state 重放：CAS 单次消费拒绝
    ResponseEntity<String> replay = noRedirect().getForEntity(callbackUrl, String.class);
    assertEquals(HttpStatus.FOUND, replay.getStatusCode());
    assertTrue(replay.getHeaders().getFirst("Location").endsWith("error=invalid_state"));
  }

  @Test
  @Order(7)
  void protocolErrors_categorized() {
    // 伪造 state
    ResponseEntity<String> forged =
        noRedirect()
            .getForEntity(
                base() + "/api/v1/auth/oidc/callback?code=x&state=forged-state", String.class);
    assertTrue(forged.getHeaders().getFirst("Location").endsWith("error=invalid_state"));

    // IdP 拒绝（error 参数）
    ResponseEntity<String> denied =
        noRedirect()
            .getForEntity(
                base() + "/api/v1/auth/oidc/callback?error=access_denied&state=s", String.class);
    assertTrue(denied.getHeaders().getFirst("Location").endsWith("error=idp_error"));

    // 异钥签名
    IDP.scenario = MockIdp.Scenario.ROGUE_KEY;
    ResponseEntity<String> badSig = loginRoundTrip("sub-alice", "Alice", List.of());
    assertEquals(HttpStatus.FOUND, badSig.getStatusCode());
    assertTrue(badSig.getHeaders().getFirst("Location").endsWith("error=invalid_signature"));

    // 过期令牌
    IDP.scenario = MockIdp.Scenario.EXPIRED;
    ResponseEntity<String> expired = loginRoundTrip("sub-alice", "Alice", List.of());
    assertTrue(expired.getHeaders().getFirst("Location").endsWith("error=expired_token"));

    // nonce 被篡改
    IDP.scenario = MockIdp.Scenario.WRONG_NONCE;
    ResponseEntity<String> badNonce = loginRoundTrip("sub-alice", "Alice", List.of());
    assertTrue(badNonce.getHeaders().getFirst("Location").endsWith("error=invalid_claims"));

    IDP.scenario = MockIdp.Scenario.NORMAL;
    // 各失败路径均落分类审计
    assertTrue(
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("login_failure").stream()
            .anyMatch(e -> "invalid_signature".equals(e.getFailureReason())));
    assertTrue(
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("login_failure").stream()
            .anyMatch(e -> "expired_token".equals(e.getFailureReason())));
  }

  @Test
  @Order(8)
  void logout_invalidatesSession_andAudits() {
    ResponseEntity<String> callback = loginRoundTrip("sub-alice", "Alice", List.of());
    String cookie = sessionCookie(callback);
    assertEquals(HttpStatus.OK, getWithCookie("/api/v1/auth/me", cookie).getStatusCode());

    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.add("Cookie", cookie);
    ResponseEntity<String> logout =
        noRedirect()
            .exchange(
                base() + "/api/v1/auth/logout",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers),
                String.class);
    assertEquals(HttpStatus.OK, logout.getStatusCode());

    assertEquals(HttpStatus.UNAUTHORIZED, getWithCookie("/api/v1/auth/me", cookie).getStatusCode());
    assertTrue(
        authEventRepository.findByEventTypeOrderByCreatedAtDesc("logout").stream()
            .anyMatch(e -> "alice".equals(e.getUsername()) && "oidc".equals(e.getAuthMethod())));
  }

  @Test
  @Order(9)
  void auditFields_neverContainTokens() {
    assertFalse(
        authEventRepository.findAllByOrderByCreatedAtDesc().stream()
            .anyMatch(
                e ->
                    contains(e.getFailureReason(), "eyJ")
                        || contains(e.getRoles(), "eyJ")
                        || contains(e.getUsername(), "eyJ")),
        "审计字段不得出现 JWT 片段");
  }

  @Test
  @Order(10)
  void loginOptions_advertisesOidc() {
    ResponseEntity<String> options =
        noRedirect().getForEntity(base() + "/api/v1/auth/login-options", String.class);
    assertTrue(options.getBody().contains("\"oidcEnabled\":true"));
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.contains(needle);
  }

  // ---------------------------------------------------------------- mock IdP

  /** 内嵌 mock IdP：discovery/JWKS/token 三端点；authorize 不真跳（测试直接解析授权 URL 短路）。 */
  static final class MockIdp {

    enum Scenario {
      NORMAL,
      ROGUE_KEY,
      EXPIRED,
      WRONG_NONCE
    }

    volatile Scenario scenario = Scenario.NORMAL;

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey rogueKey;

    private volatile String expectedNonce;
    private volatile String expectedChallenge;
    private volatile String sub;
    private volatile String preferredUsername;
    private volatile List<String> groups = List.of();

    private MockIdp(HttpServer server, RSAKey signingKey, RSAKey rogueKey) {
      this.server = server;
      this.signingKey = signingKey;
      this.rogueKey = rogueKey;
    }

    static MockIdp start() {
      try {
        RSAKey signing = new RSAKeyGenerator(2048).keyID("it-kid").generate();
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("rogue-kid").generate();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockIdp idp = new MockIdp(server, signing, rogue);
        server.createContext(
            "/realm/.well-known/openid-configuration", ex -> idp.json(ex, idp.discoveryJson()));
        server.createContext(
            "/realm/jwks", ex -> idp.json(ex, new JWKSet(signing.toPublicJWK()).toString()));
        server.createContext("/realm/token", idp::handleToken);
        server.start();
        return idp;
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    void stop() {
      server.stop(0);
    }

    String issuer() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/realm";
    }

    /** 设定下一次 token 签发的期望：nonce/PKCE 挑战来自测试解析的授权 URL。 */
    void arm(String nonce, String challenge, String sub, String preferred, List<String> groups) {
      this.expectedNonce = nonce;
      this.expectedChallenge = challenge;
      this.sub = sub;
      this.preferredUsername = preferred;
      this.groups = groups;
    }

    private String discoveryJson() {
      return "{\"issuer\":\""
          + issuer()
          + "\"," //
          + "\"authorization_endpoint\":\""
          + issuer()
          + "/authorize\"," //
          + "\"token_endpoint\":\""
          + issuer()
          + "/token\"," //
          + "\"jwks_uri\":\""
          + issuer()
          + "/jwks\"}";
    }

    private void handleToken(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Map<String, String> form = new HashMap<>();
      for (String pair : body.split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          form.put(
              URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
              URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      // PKCE 校验：S256(verifier) 必须等于授权请求里的挑战——校验不过 = 客户端实现有假
      String verifier = form.get("code_verifier");
      if (verifier == null || !s256(verifier).equals(expectedChallenge)) {
        exchange.sendResponseHeaders(400, -1);
        exchange.close();
        return;
      }
      try {
        json(exchange, "{\"id_token\":\"" + signIdToken() + "\",\"token_type\":\"Bearer\"}");
      } catch (Exception e) {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
      }
    }

    private String signIdToken() throws Exception {
      Instant now = Instant.now();
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer())
              .subject(sub)
              .audience("oryxos-admin")
              .issueTime(Date.from(now))
              .expirationTime(
                  Date.from(
                      scenario == Scenario.EXPIRED ? now.minusSeconds(600) : now.plusSeconds(300)))
              .claim("nonce", scenario == Scenario.WRONG_NONCE ? "tampered" : expectedNonce)
              .claim("preferred_username", preferredUsername)
              .claim(
                  "email", preferredUsername.toLowerCase(java.util.Locale.ROOT) + "@corp.example")
              .claim("groups", groups);
      RSAKey key = scenario == Scenario.ROGUE_KEY ? rogueKey : signingKey;
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              claims.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    }

    private static String s256(String verifier) {
      try {
        byte[] digest =
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    private void json(com.sun.net.httpserver.HttpExchange exchange, String body)
        throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }
}
