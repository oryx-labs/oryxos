package io.oryxos.web.security.oidc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.oryxos.web.config.WebOidcProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 自研 OIDC 客户端（040 R2）：Discovery 惰性缓存、authorize URL 组装（PKCE S256）、code 换 token、JWKS 拉取。
 *
 * <p>出站 HTTP 走显式超时的 {@link RestClient}（{@code ProviderModelsService} 同款模式：默认 RestClient 无超时， IdP
 * 挂死会拖垮 Tomcat 工作线程；禁自动重定向防 SSRF 跳转）。Discovery 不在启动期拉取——C2-A 并存档要求 IdP 宕机不影响进程启动与本地登录；缓存 1
 * 小时，拉取失败沿用旧缓存（可用性优先）。
 *
 * <p>错误纪律：任何网络/协议失败只抛 {@link OidcFlowException}（分类级 message），IdP 响应体与令牌内容 NEVER 进入异常消息与日志。
 */
public class OidcClient {

  /** 回调路径：恒落在既有豁免前缀 /api/v1/auth/ 之下（契约 §1）。 */
  public static final String CALLBACK_PATH = "/api/v1/auth/oidc/callback";

  private static final Logger LOG = LoggerFactory.getLogger(OidcClient.class);

  private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
  private static final String SLASH = "/";
  private static final Duration DISCOVERY_TTL = Duration.ofHours(1);

  private final WebOidcProperties properties;
  private final RestClient restClient;

  private final Object discoveryLock = new Object();
  private volatile CachedDiscovery cached;

  /** Discovery 文档里本刀用到的端点子集。 */
  public record Discovery(
      String issuer,
      String authorizationEndpoint,
      String tokenEndpoint,
      String jwksUri,
      String endSessionEndpoint) {}

  private record CachedDiscovery(Discovery discovery, Instant fetchedAt) {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "properties 为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcClient(WebOidcProperties properties, RestClient.Builder restClientBuilder) {
    this.properties = properties;
    this.restClient = restClientBuilder.clone().requestFactory(timeoutFactory(properties)).build();
  }

  /** Discovery：缓存 1h；过期重拉失败时沿用旧缓存；无缓存且拉取失败抛 idp_unreachable。 */
  public Discovery discovery() {
    CachedDiscovery current = cached;
    Instant now = Instant.now();
    if (current != null && current.fetchedAt().plus(DISCOVERY_TTL).isAfter(now)) {
      return current.discovery();
    }
    synchronized (discoveryLock) {
      current = cached;
      if (current != null && current.fetchedAt().plus(DISCOVERY_TTL).isAfter(now)) {
        return current.discovery();
      }
      try {
        Discovery fetched = fetchDiscovery();
        cached = new CachedDiscovery(fetched, now);
        return fetched;
      } catch (RuntimeException ex) {
        if (current != null) {
          LOG.warn("OIDC discovery 刷新失败，沿用旧缓存：{}", ex.getClass().getSimpleName());
          return current.discovery();
        }
        throw new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "OIDC discovery 不可达", ex);
      }
    }
  }

  /** 组装 IdP authorize 端点跳转 URL（授权码 + PKCE S256 + state + nonce）。 */
  public String authorizeUrl(String state, String nonce, String codeChallenge) {
    Discovery discovery = discovery();
    return UriComponentsBuilder.fromUriString(discovery.authorizationEndpoint())
        .queryParam("response_type", "code")
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", redirectUri())
        .queryParam("scope", String.join(" ", properties.getScopes()))
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .encode()
        .build()
        .toUriString();
  }

  /** code 换 token（client_secret_post + PKCE verifier），只取 id_token；失败抛 token_exchange_failed。 */
  public String exchangeCodeForIdToken(String code, String codeVerifier) {
    Discovery discovery = discovery();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", redirectUri());
    form.add("client_id", properties.getClientId());
    form.add("client_secret", properties.getClientSecret());
    form.add("code_verifier", codeVerifier);
    TokenResponse token;
    try {
      token =
          restClient
              .post()
              .uri(discovery.tokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TokenResponse.class);
    } catch (RuntimeException ex) {
      // 不外泄 IdP 响应体：4xx/5xx/超时统一分类（服务端日志也只留异常类名）。
      LOG.warn("OIDC token 端点交换失败：{}", ex.getClass().getSimpleName());
      throw new OidcFlowException(OidcErrorCode.TOKEN_EXCHANGE_FAILED, "token 交换失败", ex);
    }
    if (token == null || token.idToken() == null || token.idToken().isBlank()) {
      throw new OidcFlowException(OidcErrorCode.TOKEN_EXCHANGE_FAILED, "token 响应缺少 id_token");
    }
    return token.idToken();
  }

  /** 拉取 JWKS 原文（供 {@link JwksCache} 解析缓存）；失败抛 idp_unreachable。 */
  public String fetchJwksJson() {
    Discovery discovery = discovery();
    try {
      String body = restClient.get().uri(discovery.jwksUri()).retrieve().body(String.class);
      if (body == null || body.isBlank()) {
        throw new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "JWKS 响应为空");
      }
      return body;
    } catch (OidcFlowException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "JWKS 不可达", ex);
    }
  }

  /** 回调地址：redirect-base-url（剥尾部 /）+ 固定回调路径。 */
  public String redirectUri() {
    String base =
        properties.getRedirectBaseUrl() == null ? "" : properties.getRedirectBaseUrl().strip();
    while (base.endsWith(SLASH)) {
      base = base.substring(0, base.length() - 1);
    }
    return base + CALLBACK_PATH;
  }

  private Discovery fetchDiscovery() {
    String issuer = properties.getIssuer() == null ? "" : properties.getIssuer().strip();
    while (issuer.endsWith(SLASH)) {
      issuer = issuer.substring(0, issuer.length() - 1);
    }
    DiscoveryResponse resp =
        restClient.get().uri(issuer + DISCOVERY_PATH).retrieve().body(DiscoveryResponse.class);
    if (resp == null
        || isBlank(resp.authorizationEndpoint())
        || isBlank(resp.tokenEndpoint())
        || isBlank(resp.jwksUri())) {
      throw new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "discovery 文档缺少必要端点");
    }
    return new Discovery(
        resp.issuer(),
        resp.authorizationEndpoint(),
        resp.tokenEndpoint(),
        resp.jwksUri(),
        resp.endSessionEndpoint());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** 带连接/读取超时的请求工厂（ProviderModelsService 同款；禁重定向防 SSRF 跳转）。 */
  private static JdkClientHttpRequestFactory timeoutFactory(WebOidcProperties properties) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(properties.getReadTimeout());
    return factory;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DiscoveryResponse(
      @JsonProperty("issuer") String issuer,
      @JsonProperty("authorization_endpoint") String authorizationEndpoint,
      @JsonProperty("token_endpoint") String tokenEndpoint,
      @JsonProperty("jwks_uri") String jwksUri,
      @JsonProperty("end_session_endpoint") String endSessionEndpoint) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenResponse(@JsonProperty("id_token") String idToken) {}
}
