package io.oryxos.web.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC/SSO 配置（040）：{@code oryxos.web.oidc.*}。默认关——现状零变化（SC-002）。
 *
 * <p>开启时 issuer / client-id / client-secret / redirect-base-url 必填（{@code OidcStartupCheck}
 * fail-fast 点名缺失项）。client-secret 推荐 {@code ${OIDC_CLIENT_SECRET}} 环境变量注入，不明文写配置文件。
 */
@ConfigurationProperties(prefix = "oryxos.web.oidc")
public class WebOidcProperties {

  /** 总开关。默认关：Basic Auth / API Key 现状零变化。 */
  private boolean enabled = false;

  /** IdP issuer URL（OIDC Discovery 基址，如 https://idp.example.com/realms/main）。 */
  private String issuer;

  /** IdP 注册的 client_id。 */
  private String clientId;

  /** IdP 注册的 client_secret；推荐环境变量注入。 */
  private String clientSecret;

  /** OryxOS 对外可达基址（回调 = base + /api/v1/auth/oidc/callback）。生产要求 https。 */
  private String redirectBaseUrl;

  /** 授权请求 scope。 */
  private List<String> scopes = List.of("openid", "profile", "email");

  /** 承载角色的 claim 名（如 groups）；空 = 不启用 claim→角色映射（R14 本地权威分支）。 */
  private String rolesClaim;

  /** claim 值 → OryxOS 角色（VIEWER/EDITOR/ADMIN）映射表。 */
  private Map<String, String> roleMappings = new LinkedHashMap<>();

  /** JIT 首登无 claim 命中时的默认供给角色（C1-A，缺省最低档 VIEWER）。 */
  private Set<String> provisionDefaultRoles = Set.of("VIEWER");

  /** ID Token 时间类校验（exp/iat）允许的时钟偏移窗。 */
  private Duration clockSkew = Duration.ofSeconds(60);

  /** 登出联动 IdP 端（RP-Initiated Logout）。默认关：登出响应与现状逐字节一致。 */
  private boolean rpInitiatedLogout = false;

  /** 出站 HTTP（discovery/token/JWKS）连接超时。 */
  private Duration connectTimeout = Duration.ofSeconds(5);

  /** 出站 HTTP 读取超时。 */
  private Duration readTimeout = Duration.ofSeconds(10);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getRedirectBaseUrl() {
    return redirectBaseUrl;
  }

  public void setRedirectBaseUrl(String redirectBaseUrl) {
    this.redirectBaseUrl = redirectBaseUrl;
  }

  public List<String> getScopes() {
    return List.copyOf(scopes);
  }

  public void setScopes(List<String> scopes) {
    this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }

  public String getRolesClaim() {
    return rolesClaim;
  }

  public void setRolesClaim(String rolesClaim) {
    this.rolesClaim = rolesClaim;
  }

  public Map<String, String> getRoleMappings() {
    return Map.copyOf(roleMappings);
  }

  public void setRoleMappings(Map<String, String> roleMappings) {
    this.roleMappings =
        roleMappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roleMappings);
  }

  public Set<String> getProvisionDefaultRoles() {
    return Set.copyOf(provisionDefaultRoles);
  }

  public void setProvisionDefaultRoles(Set<String> provisionDefaultRoles) {
    this.provisionDefaultRoles =
        provisionDefaultRoles == null ? Set.of() : Set.copyOf(provisionDefaultRoles);
  }

  public Duration getClockSkew() {
    return clockSkew;
  }

  public void setClockSkew(Duration clockSkew) {
    this.clockSkew = clockSkew;
  }

  public boolean isRpInitiatedLogout() {
    return rpInitiatedLogout;
  }

  public void setRpInitiatedLogout(boolean rpInitiatedLogout) {
    this.rpInitiatedLogout = rpInitiatedLogout;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }
}
