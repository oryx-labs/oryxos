package io.oryxos.web.controller.dto;

import io.oryxos.core.provider.ProviderDef;

/**
 * Provider 视图（列表/详情返回）。
 *
 * <p>api-key 只回显掩码（保留末 4 位），杜绝明文经 /api/v1/**（无认证）泄露；掩码值被前端原样提交时由 {@code ProviderApiController}
 * 识别为"未修改"，保留原 key。mask 是确定性的，供该识别复用。
 */
public record ProviderView(String name, String apiKey, String baseUrl, String description) {

  public static ProviderView from(ProviderDef d) {
    return new ProviderView(d.name(), mask(d.apiKey()), d.baseUrl(), d.description());
  }

  /** 掩码保留的末尾明文位数：只回显末 4 位，其余打码。 */
  private static final int VISIBLE_SUFFIX = 4;

  /** 掩码：空/短 key 全打码，其余只留末 4 位。幂等——mask(mask(k)) == mask(k)，是"未修改"判定的基础。 */
  public static String mask(String key) {
    if (key == null || key.isBlank()) {
      return "";
    }
    if (key.length() <= VISIBLE_SUFFIX) {
      return "****";
    }
    return "****" + key.substring(key.length() - VISIBLE_SUFFIX);
  }
}
