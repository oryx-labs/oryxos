package io.oryxos.web.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.oryxos.web.config.WebOidcProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真 IdP 实现：token 端点换码 + JWKS 验 id_token（040）。
 *
 * <p>client_secret 只出现在 POST body，NEVER 入日志。discovery 结果可被 properties 覆盖。
 */
public class HttpOidcTokenClient implements OidcTokenClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpOidcTokenClient.class);

  /** Inclusive lower bound for successful HTTP status codes (2xx). */
  private static final int HTTP_OK_MIN = 200;
  /** Exclusive upper bound for successful HTTP status codes (2xx). */
  private static final int HTTP_OK_MAX_EXCLUSIVE = 300;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "httpClient/objectMapper 为共享依赖，存同一引用正是意图。")
  public HttpOidcTokenClient(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public HttpOidcTokenClient(ObjectMapper objectMapper) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        objectMapper);
  }

  @Override
  public String resolveAuthorizationEndpoint(WebOidcProperties properties) {
    try {
      return resolveEndpoints(properties).authorizationEndpoint();
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new OidcTokenException("OIDC discovery failed", ex);
    }
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "仅记录 HTTP 状态与端点名；不含 code/secret/token。")
  public OidcIdTokenClaims exchangeAndValidate(
      String code, String codeVerifier, WebOidcProperties properties) {
    try {
      DiscoveryEndpoints endpoints = resolveEndpoints(properties);
      String idToken = exchangeCode(code, codeVerifier, properties, endpoints.tokenEndpoint());
      return validateIdToken(idToken, properties, endpoints.jwksUri());
    } catch (OidcTokenException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      LOG.error("OIDC token 交换或校验失败：{}", ex.toString());
      throw new OidcTokenException("OIDC token exchange or validation failed", ex);
    } catch (Exception ex) {
      LOG.error("OIDC token 交换或校验失败：{}", ex.toString());
      throw new OidcTokenException("OIDC token exchange or validation failed", ex);
    }
  }

  private DiscoveryEndpoints resolveEndpoints(WebOidcProperties properties)
      throws IOException, InterruptedException {
    String auth = blankToNull(properties.getAuthorizationEndpoint());
    String token = blankToNull(properties.getTokenEndpoint());
    String jwks = blankToNull(properties.getJwksUri());
    if (auth != null && token != null && jwks != null) {
      return new DiscoveryEndpoints(auth, token, jwks);
    }
    String issuer = require(properties.getIssuer(), "issuer");
    String discoveryUrl =
        issuer.endsWith("/")
            ? issuer + ".well-known/openid-configuration"
            : issuer + "/.well-known/openid-configuration";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(discoveryUrl))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .header("Accept", "application/json")
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
      throw new OidcTokenException("OIDC discovery HTTP " + response.statusCode());
    }
    JsonNode root = objectMapper.readTree(response.body());
    return new DiscoveryEndpoints(
        firstNonBlank(auth, text(root, "authorization_endpoint")),
        firstNonBlank(token, text(root, "token_endpoint")),
        firstNonBlank(jwks, text(root, "jwks_uri")));
  }

  private String exchangeCode(
      String code, String codeVerifier, WebOidcProperties properties, String tokenEndpoint)
      throws IOException, InterruptedException {
    String body =
        "grant_type=authorization_code"
            + "&code="
            + enc(code)
            + "&redirect_uri="
            + enc(require(properties.getRedirectUri(), "redirectUri"))
            + "&client_id="
            + enc(require(properties.getClientId(), "clientId"))
            + "&code_verifier="
            + enc(codeVerifier);
    if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) {
      body = body + "&client_secret=" + enc(properties.getClientSecret());
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(tokenEndpoint))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
      throw new OidcTokenException("OIDC token endpoint HTTP " + response.statusCode());
    }
    JsonNode root = objectMapper.readTree(response.body());
    String idToken = text(root, "id_token");
    if (idToken == null || idToken.isBlank()) {
      throw new OidcTokenException("OIDC token response missing id_token");
    }
    return idToken;
  }

  private OidcIdTokenClaims validateIdToken(
      String idToken, WebOidcProperties properties, String jwksUri) throws Exception {
    JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(URI.create(jwksUri).toURL());
    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    JWSKeySelector<SecurityContext> keySelector =
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
    processor.setJWSKeySelector(keySelector);
    JWTClaimsSet claims = processor.process(idToken, null);

    String expectedIssuer = require(properties.getIssuer(), "issuer");
    if (!Objects.equals(claims.getIssuer(), expectedIssuer)) {
      throw new OidcTokenException("id_token iss mismatch");
    }
    String clientId = require(properties.getClientId(), "clientId");
    List<String> audience = claims.getAudience();
    if (audience == null || audience.stream().noneMatch(clientId::equals)) {
      throw new OidcTokenException("id_token aud mismatch");
    }
    Date exp = claims.getExpirationTime();
    if (exp == null || exp.before(new Date())) {
      throw new OidcTokenException("id_token expired");
    }
    String subject = claims.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new OidcTokenException("id_token missing sub");
    }
    String email = claims.getStringClaim("email");
    return new OidcIdTokenClaims(claims.getIssuer(), subject, email);
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  private static String firstNonBlank(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    if (fallback == null || fallback.isBlank()) {
      throw new OidcTokenException("missing OIDC endpoint configuration");
    }
    return fallback;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new OidcTokenException("oryxos.web.oidc." + name + " is required");
    }
    return value.strip();
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record DiscoveryEndpoints(
      String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

  /** OIDC 协议层失败（映射到 401），不含凭证明文。 */
  public static final class OidcTokenException extends RuntimeException {
    public OidcTokenException(String message) {
      super(message);
    }

    public OidcTokenException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
