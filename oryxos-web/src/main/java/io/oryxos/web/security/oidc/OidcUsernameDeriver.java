package io.oryxos.web.security.oidc;

import java.util.Locale;

/**
 * JIT 首登用户名派生（040 R5）：{@code preferred_username} → email local-part → {@code oidc-<sub>}。
 *
 * <p>只在首登派生一次（此后恒走 {@code oidc_identities} 映射表），IdP 侧改名/改邮箱不影响锚点。产出满足 {@code WebUserService}
 * 用户名约束：小写、无空白、只含 {@code [a-z0-9._-]}、≤60 字符（给冲突后缀留位）。
 */
public final class OidcUsernameDeriver {

  private static final int MAX_LENGTH = 60;
  private static final String FALLBACK_PREFIX = "oidc-";

  private OidcUsernameDeriver() {}

  /**
   * 派生用户名候选（冲突后缀由 {@code OidcIdentityService} 追加）。
   *
   * @param preferredUsername ID Token preferred_username claim（可空）
   * @param email email claim（可空）
   * @param subject sub claim（必有，最后兜底）
   */
  public static String derive(String preferredUsername, String email, String subject) {
    String fromPreferred = sanitize(preferredUsername);
    if (!fromPreferred.isEmpty()) {
      return fromPreferred;
    }
    if (email != null) {
      int at = email.indexOf('@');
      String local = at > 0 ? email.substring(0, at) : email;
      String fromEmail = sanitize(local);
      if (!fromEmail.isEmpty()) {
        return fromEmail;
      }
    }
    String fromSub = sanitize(subject);
    if (fromSub.isEmpty()) {
      fromSub = "user";
    }
    String candidate = FALLBACK_PREFIX + fromSub;
    return candidate.length() <= MAX_LENGTH ? candidate : candidate.substring(0, MAX_LENGTH);
  }

  /** 小写化 + 只保留 [a-z0-9._-] + 截断到 60；全非法字符时返回空串（触发下一级兜底）。 */
  private static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (char c : raw.strip().toLowerCase(Locale.ROOT).toCharArray()) {
      if (isAllowedChar(c)) {
        sb.append(c);
      }
      if (sb.length() >= MAX_LENGTH) {
        break;
      }
    }
    return sb.toString();
  }

  private static boolean isAllowedChar(char c) {
    boolean alphaNumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    return alphaNumeric || c == '.' || c == '_' || c == '-';
  }
}
