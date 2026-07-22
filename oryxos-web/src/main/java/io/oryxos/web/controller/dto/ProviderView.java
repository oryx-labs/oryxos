package io.oryxos.web.controller.dto;

import io.oryxos.core.provider.ProviderDef;

/** Provider 视图（列表/详情返回）。按用户选择：api-key 明文回显。 */
public record ProviderView(String name, String apiKey, String baseUrl, String description) {

  public static ProviderView from(ProviderDef d) {
    return new ProviderView(d.name(), d.apiKey(), d.baseUrl(), d.description());
  }
}
