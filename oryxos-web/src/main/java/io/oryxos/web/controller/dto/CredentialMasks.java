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

  /**
   * 整串就是一条 {@code ${VAR}} 引用（与 {@code McpConfigLoader} 的占位符同形）。这种值本身就是配置文件里的文本，
   * 引用的真值在环境变量里——不含凭证本体，可以原样回显。
   */
  private static final Pattern ENV_REFERENCE = Pattern.compile("\\s*\\$\\{[A-Za-z0-9_]+}\\s*");

  private CredentialMasks() {}

  /** 单值掩码：复用 ProviderView.mask 的口径（留末 4 位，幂等）。 */
  public static String maskValue(String value) {
    return ProviderView.mask(value);
  }

  /**
   * 单个 env/headers 值的回显口径，两条判据取并集：
   *
   * <ul>
   *   <li>键名像凭证（password/secret/token/authorization/api-key/pwd）→ 掩码：既有口径不变。
   *   <li>值不是一条完整的 {@code ${VAR}} 引用（即字面量）→ 掩码。
   * </ul>
   *
   * <p>第二条是必需的那一半：凭证装进哪个键由配置的人决定，{@code DATABASE_URI} / {@code X-Auth} / {@code SSH_PRIVATE_KEY}
   * 这些键名一个敏感词都不含，只按键名判定就是明文回显。反过来，字面量就是「值直接写在文件里」——配置模板要求凭证写成 {@code ${ENV_VAR}}（见 {@code
   * config/mcp_servers.yaml.example} 的 CREDENTIALS 一节），所以字面量一律按凭证看待。
   *
   * <p>{@code mergeUnchanged} 用同一函数判定「提交的是不是回显出来的掩码」，两侧永远同一判据。
   */
  public static String maskCredential(String key, String value) {
    if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
      return maskValue(value);
    }
    if (value == null) {
      return null;
    }
    return ENV_REFERENCE.matcher(value).matches() ? value : maskValue(value);
  }

  /** Map 里的凭证值打码（口径见 {@link #maskCredential}）；返回新 Map，入参不变。 */
  public static Map<String, String> maskCredentialValues(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }
    Map<String, String> masked = new LinkedHashMap<>(values);
    masked.replaceAll(CredentialMasks::maskCredential);
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

  /**
   * update 路径的「未修改」归并：提交值等于既有值经 {@link #maskCredential} 之后的形态 → 视为前端原样回填，保留既有值； 其余按提交值。
   *
   * <p>判据与回显同一函数——回显掩码的键集合若比这里的判定大，多出来的那些键就会把掩码写回盘上，真实凭证被静默顶掉。
   *
   * <p>凑不出对应原值的掩码形状提交一律点名拒绝：掩码只可能是回显的产物，原配置里没有这个键（或取值期间文件已被改过）时，
   * 把它当凭证存下去就是「凭证被一个掩码顶掉」的另一种走法。文案只点名键，不回带提交值。
   */
  public static Map<String, String> mergeUnchanged(
      Map<String, String> existing, Map<String, String> submitted) {
    if (submitted == null) {
      return Map.of();
    }
    Map<String, String> stored = existing == null ? Map.of() : existing;
    Map<String, String> merged = new LinkedHashMap<>();
    submitted.forEach(
        (key, value) -> {
          String old = stored.get(key);
          if (old != null && maskCredential(key, old).equals(value)) {
            merged.put(key, old);
            return;
          }
          if (isMaskShaped(value)) {
            throw new IllegalArgumentException(
                "env/headers 的 " + key + " 提交的像是回显掩码（以 **** 开头），但原配置里没有与之对应的值，请提交真实值或 ${ENV} 占位");
          }
          merged.put(key, value);
        });
    return merged;
  }

  /** 掩码形状：{@link ProviderView#mask} 一律以 4 个星号开头（短值即 {@code ****}）。 */
  private static boolean isMaskShaped(String value) {
    return value != null && value.startsWith("****");
  }
}
