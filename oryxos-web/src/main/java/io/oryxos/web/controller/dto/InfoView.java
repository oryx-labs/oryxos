package io.oryxos.web.controller.dto;

import java.util.List;

/** GET /info 视图：应用名 + 已配置的 Provider 名单（"已配置"状态，不做 live ping）。 */
public record InfoView(String application, List<String> providers) {

  public InfoView {
    providers = providers == null ? List.of() : List.copyOf(providers);
  }
}
