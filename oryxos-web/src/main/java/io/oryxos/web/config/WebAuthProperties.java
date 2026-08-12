package io.oryxos.web.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * 管理台 Basic Auth 配置（012-web-auth）。
 *
 * <p>{@code oryxos.web.auth.enabled} 默认 {@code false}——假设内网现状不变（宪法核心阶段边界）； 置 {@code true} 后 {@code
 * /admin/**} 启用认证（浏览器走 session/cookie + 登录页，curl 走 Basic Auth）， {@code /api/v1/**} 不受影响。
 */
@ConfigurationProperties(prefix = "oryxos.web.auth")
public class WebAuthProperties {

  /** 是否启用管理台认证。默认关：保持"假设内网"现状。 */
  private boolean enabled = false;

  /** Basic Auth realm 文案（curl 401 时 WWW-Authenticate 用）。默认 "OryxOS"。 */
  private String realm = "OryxOS";

  /** session 过期时间（web_sessions.expires_at = created_at + ttl）。默认 12 小时。 */
  @DurationUnit(ChronoUnit.HOURS)
  private Duration sessionTtl = Duration.ofHours(12);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getRealm() {
    return realm;
  }

  public void setRealm(String realm) {
    this.realm = realm;
  }

  public Duration getSessionTtl() {
    return sessionTtl;
  }

  public void setSessionTtl(Duration sessionTtl) {
    this.sessionTtl = sessionTtl;
  }
}
