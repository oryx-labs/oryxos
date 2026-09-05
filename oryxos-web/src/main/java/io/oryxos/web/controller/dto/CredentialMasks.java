package io.oryxos.web.controller.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 凭证回显掩码工具（FR-012 口径的补齐）：webhook URL 与配置里的敏感值只回显掩码，杜绝明文经 {@code /api/v1/**} 泄露。
 *
 * <p>掩码全部确定性且幂等（mask(mask(x)) == mask(x)）——这是 {@code mergeUnchanged}「提交掩码 = 未修改」判定的基础， 与 {@code
 * ProviderView.mask} / {@code ProviderApiController} 的既有范式一致。
 */
public final class CredentialMasks {

  /** 敏感配置键：password/secret/token/authorization/api-key/pwd（大小写不敏感，子串命中即掩码）。 */
  private static final Pattern SENSITIVE_KEY =
      Pattern.compile("(?i).*(password|secret|token|authorization|api[-_]?key|pwd).*");

  private CredentialMasks() {}

  /** 单值掩码：复用 ProviderView.mask 的口径（留末 4 位，幂等）。 */
  public static String maskValue(String value) {
    return ProviderView.mask(value);
  }

  /** Map 中敏感键的值打码；非敏感键原样。返回新 Map，入参不变。 */
  public static Map<String, String> maskSensitiveValues(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }
    Map<String, String> masked = new LinkedHashMap<>(values);
    masked.replaceAll(
        (key, value) -> SENSITIVE_KEY.matcher(key).matches() ? maskValue(value) : value);
    return masked;
  }

  /**
   * webhook URL 掩码：URL 本身就是凭证（拿到即可推送）。query 整体打码（钉钉 access_token / 企微 key 在 query）； 多段 path
   * 的末段打码（飞书 hook id 在末段）；单段 path 与 scheme+host 保留，便于辨认渠道端点。幂等。
   */
  public static String maskWebhookUrl(String url) {
    if (url == null || url.isBlank()) {
      return "";
    }
    String working = url;
    String query = "";
    int q = working.indexOf('?');
    if (q >= 0) {
      query = "?****";
      working = working.substring(0, q);
    }
    int schemeEnd = working.indexOf("://");
    int pathStart = schemeEnd >= 0 ? working.indexOf('/', schemeEnd + 3) : -1;
    if (pathStart >= 0) {
      int lastSlash = working.lastIndexOf('/');
      if (lastSlash > pathStart) {
        working = working.substring(0, lastSlash + 1) + "****";
      }
    }
    return working + query;
  }

  /** update 路径的「未修改」归并：敏感键提交值等于既有值的掩码 → 视为前端原样回填，保留既有明文； 其余按提交值。非敏感键直接透传。 */
  public static Map<String, String> mergeUnchanged(
      Map<String, String> existing, Map<String, String> submitted) {
    if (submitted == null) {
      return Map.of();
    }
    if (existing == null || existing.isEmpty()) {
      return Map.copyOf(submitted);
    }
    Map<String, String> merged = new LinkedHashMap<>();
    submitted.forEach(
        (key, value) -> {
          String old = existing.get(key);
          boolean unchanged =
              old != null && SENSITIVE_KEY.matcher(key).matches() && maskValue(old).equals(value);
          merged.put(key, unchanged ? old : value);
        });
    return merged;
  }
}
