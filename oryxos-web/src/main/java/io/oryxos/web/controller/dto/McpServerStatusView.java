package io.oryxos.web.controller.dto;

import io.oryxos.core.mcp.McpServerStatus;
import java.util.List;

/** MCP server 的运行时连接状态视图。 */
public record McpServerStatusView(
    String name, boolean connected, String error, List<String> toolNames) {

  public McpServerStatusView {
    toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
  }

  public static McpServerStatusView from(McpServerStatus s) {
    return new McpServerStatusView(s.name(), s.connected(), s.error(), s.toolNames());
  }
}
