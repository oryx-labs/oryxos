package io.oryxos.provider;

import io.oryxos.core.OryxTool;
import java.util.List;

/** 一次模型调用的输入：要发给模型的内容 + 可用工具列表（可空=不带工具）。 */
public record ProviderRequest(String content, List<OryxTool> availableTools) {

  public ProviderRequest {
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }

  public static ProviderRequest of(String content) {
    return new ProviderRequest(content, List.of());
  }
}
