package io.oryxos.core.provider;

import java.util.List;

/** 一次模型调用的输出：文本回复 + 原样透传的工具调用请求 + token 用量。 */
public record ProviderResponse(String text, List<ToolCallRequest> toolCalls, Usage usage) {

  public ProviderResponse {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
