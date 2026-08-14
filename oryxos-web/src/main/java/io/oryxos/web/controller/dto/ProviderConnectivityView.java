package io.oryxos.web.controller.dto;

import java.util.List;

/** Provider 连通性测试结果。 */
public record ProviderConnectivityView(boolean ok, int modelCount, List<String> sampleModels) {

  public ProviderConnectivityView {
    sampleModels = sampleModels == null ? List.of() : List.copyOf(sampleModels);
  }
}
