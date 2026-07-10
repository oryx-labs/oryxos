package io.oryxos.core;

import com.fasterxml.jackson.databind.JsonNode;

public interface OryxTool {
  String getName();

  String getDescription();

  /** JSON Schema 文本，描述本工具的输入参数；Provider 翻译 Function Calling 时消费。 */
  String getInputSchema();

  ToolResult execute(JsonNode input);
}
