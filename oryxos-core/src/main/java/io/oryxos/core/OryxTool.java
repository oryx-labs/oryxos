package io.oryxos.core;

import com.fasterxml.jackson.databind.JsonNode;

public interface OryxTool {
  String getName();

  String getDescription();

  ToolResult execute(JsonNode input);
}
