package io.oryxos.web.security.oidc;

import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.config.WebRbacProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * OIDC 启动校验（040 FR-002/FR-012，R12）：{@code oidc.enabled=true} 时 fail-fast。
 *
 * <ul>
 *   <li>抛错：issuer / client-id / client-secret / redirect-base-url 任一缺失（点名缺失项）； {@code
 *       auth.enabled=false}（OIDC 是管理台登录方式，认证关 = 无会话概念，误配组合启动即拒）。
 *   <li>WARN：{@code rbac.enabled=false}（任何 IdP 用户登录后即获全功能）；redirect-base-url 非 https 且非回环（生产 https
 *       要求）。
 * </ul>
 *
 * <p>刻意不碰网络：IdP 可达性不是启动前置（C2-A 并存档，IdP 宕机不得影响进程启动与本地登录）。 {@link ConditionalOnWebApplication} 限定
 * SERVLET，CLI 管理命令不受影响（三兄弟同款）。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OidcStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(OidcStartupCheck.class);

  static final String MSG_AUTH_REQUIRED =
      "OIDC enabled (oryxos.web.oidc.enabled=true) but web auth is off"
          + " (oryxos.web.auth.enabled=false). OIDC login produces admin console sessions;"
          + " enable oryxos.web.auth.enabled (and run 'oryxos user add' first).";

  static final String MSG_RBAC_OFF_WARN =
      "OIDC enabled but RBAC disabled (oryxos.web.rbac.enabled=false):"
          + " ANY IdP user who logs in gets full functionality."
          + " Enable oryxos.web.rbac.enabled to enforce role-based access (C1-A JIT default"
          + " provisions lowest role VIEWER).";

  private final WebOidcProperties oidcProperties;
  private final WebAuthProperties authProperties;
  private final WebRbacProperties rbacProperties;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "三个 properties 均为 Spring 注入共享单例，存同一引用正是意图（镜像 RbacStartupCheck）。")
  public OidcStartupCheck(
      WebOidcProperties oidcProperties,
      WebAuthProperties authProperties,
      WebRbacProperties rbacProperties) {
    this.oidcProperties = oidcProperties;
    this.authProperties = authProperties;
    this.rbacProperties = rbacProperties;
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志只含固定文案与配置键名常量，无外部可控内容。")
  public void run(ApplicationArguments args) {
    if (!oidcProperties.isEnabled()) {
      LOG.debug("OIDC disabled (oryxos.web.oidc.enabled=false), startup check skipped");
      return;
    }
    List<String> missing = new ArrayList<>();
    requireText(oidcProperties.getIssuer(), "oryxos.web.oidc.issuer", missing);
    requireText(oidcProperties.getClientId(), "oryxos.web.oidc.client-id", missing);
    requireText(oidcProperties.getClientSecret(), "oryxos.web.oidc.client-secret", missing);
    requireText(oidcProperties.getRedirectBaseUrl(), "oryxos.web.oidc.redirect-base-url", missing);
    if (!missing.isEmpty()) {
      String message =
          "OIDC enabled but required config missing: "
              + String.join(", ", missing)
              + ". Fill them (client-secret via ${OIDC_CLIENT_SECRET} env var recommended)"
              + " or set oryxos.web.oidc.enabled=false.";
      LOG.error(message);
      throw new IllegalStateException(message);
    }
    if (!authProperties.isEnabled()) {
      LOG.error(MSG_AUTH_REQUIRED);
      throw new IllegalStateException(MSG_AUTH_REQUIRED);
    }
    if (!rbacProperties.isEnabled()) {
      LOG.warn(MSG_RBAC_OFF_WARN);
    }
    warnIfPlainHttp(oidcProperties.getRedirectBaseUrl());
    LOG.debug(
        "OIDC startup check passed (issuer configured, auth on, rbac={})",
        rbacProperties.isEnabled());
  }

  private static void requireText(String value, String key, List<String> missing) {
    if (value == null || value.isBlank()) {
      missing.add(key);
    }
  }

  private static final String PLAIN_HTTP_PREFIX = "http://";

  private static void warnIfPlainHttp(String redirectBaseUrl) {
    String url = redirectBaseUrl.strip().toLowerCase(Locale.ROOT);
    boolean loopback =
        url.contains("://localhost") || url.contains("://127.0.0.1") || url.contains("://[::1]");
    if (url.startsWith(PLAIN_HTTP_PREFIX) && !loopback) {
      LOG.warn(
          "oryxos.web.oidc.redirect-base-url 使用明文 http 且非回环地址：生产环境必须走 https"
              + "（反向代理终止 TLS 时配 server.forward-headers-strategy）");
    }
  }
}
