package io.oryxos.core.mcp;

import java.util.List;

/** 一个已配置 MCP server 的运行时连接状态：管理台列表页用它展示"连没连上、连上给了哪些工具、失败原因"。 */
public record McpServerStatus(
    String name, boolean connected, String error, List<String> toolNames) {

  public McpServerStatus {
    toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
  }

  public static McpServerStatus disconnected(String name) {
    return new McpServerStatus(name, false, null, List.of());
  }
}
