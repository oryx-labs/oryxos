package io.oryxos.web.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC/SSO 配置（040-oidc-sso / #461）。
 *
 * <p>{@code oryxos.web.oidc.enabled} 默认 {@code false}——关闭时 login 端点 404，零行为变化。client-secret 永不入日志。
 */
@ConfigurationProperties(prefix = "oryxos.web.oidc")
public class WebOidcProperties {

  private static final String DEFAULT_GROUP_CLAIM = "groups";

  /** 是否启用 OIDC 登录。默认关。 */
  private boolean enabled = false;

  /** IdP issuer（亦用于校验 id_token iss）。 */
  private String issuer = "";

  /** OAuth2/OIDC client id。 */
  private String clientId = "";

  /** OAuth2 client secret（机密客户端）；日志禁出。 */
  private String clientSecret = "";

  /** 授权回调绝对 URI（须与 IdP 登记一致）。 */
  private String redirectUri = "";

  /** 授权 scope，空格分隔；默认 openid profile email。 */
  private String scopes = "openid profile email";

  /** 可选：覆盖 discovery 得到的 authorization endpoint。 */
  private String authorizationEndpoint = "";

  /** 可选：覆盖 discovery 得到的 token endpoint。 */
  private String tokenEndpoint = "";

  /** 可选：覆盖 discovery 得到的 JWKS URI。 */
  private String jwksUri = "";

  /** id_token 里的组声明名。只在 {@link #groupRoles} 非空时读取。 */
  private String groupClaim = DEFAULT_GROUP_CLAIM;

  /**
   * IdP 组名 → 角色名（VIEWER/EDITOR/ADMIN）。默认空 = 登录不改 {@code web_users.roles}。非空时命中才
   * setRoles，未命中保留原角色。不引入第二套授权路径。
   */
  private Map<String, String> groupRoles = new LinkedHashMap<>();

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

  public String getRedirectUri() {
    return redirectUri;
  }

  public void setRedirectUri(String redirectUri) {
    this.redirectUri = redirectUri;
  }

  public String getScopes() {
    return scopes;
  }

  public void setScopes(String scopes) {
    this.scopes = scopes;
  }

  public String getAuthorizationEndpoint() {
    return authorizationEndpoint;
  }

  public void setAuthorizationEndpoint(String authorizationEndpoint) {
    this.authorizationEndpoint = authorizationEndpoint;
  }

  public String getTokenEndpoint() {
    return tokenEndpoint;
  }

  public void setTokenEndpoint(String tokenEndpoint) {
    this.tokenEndpoint = tokenEndpoint;
  }

  public String getJwksUri() {
    return jwksUri;
  }

  public void setJwksUri(String jwksUri) {
    this.jwksUri = jwksUri;
  }

  public String getGroupClaim() {
    return groupClaim == null || groupClaim.isBlank() ? DEFAULT_GROUP_CLAIM : groupClaim.strip();
  }

  public void setGroupClaim(String groupClaim) {
    this.groupClaim = groupClaim;
  }

  /** 返回防御性拷贝（SpotBugs EI_EXPOSE_REP）。 */
  public Map<String, String> getGroupRoles() {
    return Map.copyOf(groupRoles);
  }

  public void setGroupRoles(Map<String, String> groupRoles) {
    this.groupRoles = groupRoles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(groupRoles);
  }
}
